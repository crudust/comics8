package com.comics8.core.model

import com.comics8.core.source.ComicSource
import com.comics8.core.source.WorkId
import com.comics8.core.source.resolveSourceType

/** Pure transformations shared by the Android and desktop repositories. */
object RepositoryTransforms {
    data class EpisodeReadState(
        val readAt: Long,
        val lastPage: Int,
    )

    data class HistoryProgress(
        val lastReadOrder: Int,
        val hasNew: Boolean,
    )

    /**
     * Derives read progress from a counted total.
     *
     * A missing total means "not counted yet", not "fully read": [hasNew] stays false so the work
     * does not claim unread episodes, and nothing is clamped because there is nothing to clamp to.
     */
    fun historyProgress(lastReadOrder: Int, total: EpisodeListingTotal?): HistoryProgress {
        val order = lastReadOrder.coerceAtLeast(0)
        val valid = total?.takeIf { it.isValid }
        return HistoryProgress(
            lastReadOrder = order,
            hasNew = valid?.needsRefresh(order) ?: false,
        )
    }

    data class ReaderSettingValues(
        val viewMode: ViewMode,
        val readDirection: ReadDirection,
        val splitMode: SplitMode,
    )

    data class EpisodeMetadata(
        /** Null until the listing has been counted end to end. */
        val total: EpisodeListingTotal? = null,
        val thumbUrl: String? = null,
    )

    fun installedSources(
        allSources: List<ComicSource>,
        installedIds: Set<String>,
    ): List<ComicSource> {
        val byId = allSources.associateBy { it.id }
        val local = byId[WorkId.LOCAL_SOURCE]
        val orderedStorage = installedIds.mapNotNull { id ->
            if (id == WorkId.LOCAL_SOURCE) null
            else byId[id]?.takeIf { it.resolveSourceType().isStorage }
        }
        val remainingStorage = allSources.filter {
            it.id in installedIds &&
                it.resolveSourceType().isStorage &&
                it.id != WorkId.LOCAL_SOURCE &&
                it !in orderedStorage
        }
        val orderedOnline = installedIds.mapNotNull { id ->
            byId[id]?.takeIf { !it.resolveSourceType().isStorage }
        }
        val remainingOnline = allSources.filter {
            it.id in installedIds && !it.resolveSourceType().isStorage && it !in orderedOnline
        }

        return buildList {
            if (local != null && local.id in installedIds) add(local)
            addAll(orderedStorage)
            addAll(remainingStorage)
            addAll(orderedOnline)
            addAll(remainingOnline)
        }
    }

    fun enrichEpisodePage(
        page: EpisodePage,
        readByEpisodeId: Map<String, EpisodeReadState>,
    ): EpisodePage = page.copy(
        items = page.items.map { episode ->
            readByEpisodeId[episode.wrId]?.let { read ->
                episode.copy(isRead = true, readAt = read.readAt, lastReadPage = read.lastPage)
            } ?: episode
        }.distinctBy { it.wrId },
    )

    /**
     * Counts every page of an episode listing. This is the only way a total is ever produced.
     *
     * Returns null when any page cannot be read (or a middle page is unexpectedly empty): a partial
     * sweep must never be turned into a total, because the missing pages are exactly the ones that
     * would be guessed.
     */
    suspend fun countEpisodeListing(
        pageSize: Int,
        loadPage: suspend (Int) -> EpisodePage,
    ): EpisodeListingTotal? {
        val safePageSize = pageSize.coerceAtLeast(1)
        val first = loadPage(1)
        val lastPage = first.lastPage.coerceAtLeast(1)
        if (first.items.isEmpty()) return null

        var counted = first.items.size
        var tailItems = if (lastPage == 1) first.items.size else 0
        for (page in 2..lastPage) {
            val loaded = try {
                loadPage(page)
            } catch (_: Exception) {
                return null
            }
            if (loaded.items.isEmpty()) return null
            counted += loaded.items.size
            if (page == lastPage) tailItems = loaded.items.size
        }
        // naive = every page full except the tail. Anything shorter than that (middle pages) lands in
        // delta, while the tail itself stays a live value so added/removed newest episodes still move
        // the total even when the page count does not change.
        val naive = (lastPage - 1) * safePageSize + tailItems
        return EpisodeListingTotal(
            count = counted,
            lastPage = lastPage,
            pageSize = safePageSize,
            delta = counted - naive,
        )
    }

    /**
     * Recomputes the total from the freshly loaded tail page using the correction learned by
     * [countEpisodeListing]. When the listing has only one page, the page itself is the entire
     * listing and produces a verified total directly without a prior stored shape.
     *
     * Returns null when the page is not the listing's tail, or when its shape no longer matches the
     * count. Both mean the correction describes a different listing and it has to be recounted.
     */
    fun totalFromPage(page: EpisodePage, pageSize: Int, known: EpisodeListingTotal?): EpisodeListingTotal? {
        val lastPage = page.lastPage.coerceAtLeast(1)
        val safePageSize = pageSize.coerceAtLeast(1)
        if (page.currentPage.coerceIn(1, lastPage) != lastPage) return null
        if (lastPage == 1) {
            return EpisodeListingTotal(
                count = page.items.size,
                lastPage = 1,
                pageSize = safePageSize,
                delta = 0,
            ).takeIf { it.isValid }
        }
        if (known == null || !known.describes(lastPage, safePageSize)) return null

        return EpisodeListingTotal(
            count = ((lastPage - 1) * safePageSize + page.items.size + known.delta).coerceAtLeast(0),
            lastPage = lastPage,
            pageSize = safePageSize,
            delta = known.delta,
        ).takeIf { it.isValid }
    }

    /**
     * Whether a background refresh is worth a listing request for this work.
     *
     * The four inputs are separate axes, not aliases:
     * - [knownTotal] null means the listing was never counted. That is itself a reason to fetch:
     *   without a count there is no badge and no way to tell whether episodes are unread.
     * - [thumbUrl] blank means the listing cannot be rendered and the episode list can supply a cover.
     * - [isFavorite] keeps favorites accurate because they are the notification targets.
     *
     * `isNew` is intentionally absent: it is a *result* of comparing a work against its seen row, not
     * a reason to scrape, and works that qualify for it already qualify here.
     */
    fun needsEpisodeSync(
        knownTotal: EpisodeListingTotal?,
        lastReadOrder: Int,
        thumbUrl: String,
        isFavorite: Boolean,
    ): Boolean = knownTotal == null ||
        knownTotal.needsRefresh(lastReadOrder) ||
        thumbUrl.isBlank() ||
        isFavorite

    fun <Seen, History> applyListingFlags(
        items: List<ToonItem>,
        seenCount: Int,
        favoriteKeys: Set<String>,
        seenByKey: Map<String, Seen>,
        historyByKey: Map<String, History>,
        readCountsByKey: Map<String, Int>,
        totalsByKey: Map<String, EpisodeListingTotal> = emptyMap(),
        seenUpdatedAt: (Seen) -> String?,
        formatProgress: (ToonItem, History?, EpisodeListingTotal?, Int) -> String?,
    ): List<ToonItem> = items.map { item ->
        val key = item.workId().storageKey()
        val seen = seenByKey[key]
        val history = historyByKey[key]
        val total = totalsByKey[key]
        val isUpdated = seen != null &&
            !item.updatedAt.isNullOrBlank() &&
            seenUpdatedAt(seen) != item.updatedAt
        item.copy(
            isNew = item.isNew || (seenCount > 0 && (seen == null || isUpdated)),
            isFavorite = key in favoriteKeys,
            readProgress = formatProgress(item, history, total, readCountsByKey[key] ?: 0)?.ifBlank { null },
        )
    }

    fun parseReaderSetting(
        viewMode: String,
        readDirection: String,
        splitMode: String,
    ): ReaderSettingValues = ReaderSettingValues(
        viewMode = when (viewMode.uppercase()) {
            "SCROLL" -> ViewMode.SCROLL
            "DUAL" -> ViewMode.DUAL
            else -> ViewMode.PAGE
        },
        readDirection = enumValueOrDefault(readDirection, ReadDirection.RIGHT_TO_LEFT),
        splitMode = enumValueOrDefault(splitMode, SplitMode.FIT),
    )

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        try {
            enumValueOf(value)
        } catch (_: IllegalArgumentException) {
            default
        }
}
