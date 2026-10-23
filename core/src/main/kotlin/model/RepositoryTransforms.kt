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
        val total: EpisodeTotal,
        val lastReadOrder: Int,
        val hasNew: Boolean,
    )

    /**
     * Single merge rule for stored history vs. a fresh observation.
     *
     * A lower bound of the total must not clamp [lastReadOrder]: clamping against an incomplete
     * count truncates how far the user actually read. Only an exact total bounds the order.
     */
    fun mergeHistoryProgress(
        lastReadOrder: Int,
        storedTotal: Int,
        observed: EpisodeTotal = EpisodeTotal.Unknown,
    ): HistoryProgress {
        val total = EpisodeTotal.merge(EpisodeTotal.fromStored(storedTotal), observed)
        val order = lastReadOrder.coerceAtLeast(0)
        // An exact total may bound the order: a verified sweep is the authority on how many
        // episodes a work actually has.
        val boundedOrder = if (total.isExact) order.coerceAtMost(total.count) else order
        return HistoryProgress(total, boundedOrder, total.needsRefresh(boundedOrder))
    }

    data class ReaderSettingValues(
        val viewMode: ViewMode,
        val readDirection: ReadDirection,
        val splitMode: SplitMode,
    )

    data class EpisodeMetadata(
        val total: EpisodeTotal = EpisodeTotal.Unknown,
        val thumbUrl: String? = null,
        val snapshot: EpisodeListingSnapshot? = null,
    ) {
        val totalEpisodes: Int get() = total.count

        companion object {
            /** Builds metadata from a fresh full sweep, keeping the learned shape with the total. */
            fun fromSweep(
                sweep: EpisodeTotalObservation,
                stored: EpisodeTotal = EpisodeTotal.Unknown,
                thumbUrl: String? = null,
            ): EpisodeMetadata = EpisodeMetadata(
                total = EpisodeTotal.merge(stored, sweep.total),
                thumbUrl = thumbUrl,
                snapshot = sweep.snapshot,
            )
        }
    }

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
     * Single request path: derive the total from one page plus a verified [snapshot].
     */
    fun totalFromPage(
        page: EpisodePage,
        pageSize: Int,
        snapshot: EpisodeListingSnapshot?,
    ): EpisodeTotal = EpisodeTotal.from(page, pageSize, snapshot)

    /**
     * Full sweep used to *learn* a listing shape. Reading every page is the only way to prove that
     * [EpisodeListingSnapshot.lastPageItemCount] is right, and it is done rarely: only when no
     * snapshot exists or when the reported page count changed.
     *
     * Returns a lower bound (no snapshot) if any page fails, so a partial sweep can never rewrite a
     * verified total.
     */
    suspend fun verifyEpisodeListing(
        pageSize: Int,
        loadPage: suspend (Int) -> EpisodePage,
    ): EpisodeTotalObservation {
        val first = loadPage(1)
        val lastPage = first.lastPage.coerceAtLeast(1)
        if (first.items.isEmpty()) {
            return EpisodeTotalObservation(EpisodeTotal.Unknown, null)
        }
        if (lastPage <= 1) {
            val total = EpisodeTotal.Exact(first.items.size)
            return EpisodeTotalObservation(total, EpisodeListingSnapshot(lastPage = 1, lastPageItemCount = first.items.size))
        }

        val safePageSize = pageSize.coerceAtLeast(1)
        var observed = first.items.size
        var lastPageItemCount: Int? = null
        for (page in 2..lastPage) {
            val loaded = try {
                loadPage(page)
            } catch (_: Exception) {
                return EpisodeTotalObservation(EpisodeTotal.AtLeast(observed.coerceAtLeast(1)), null)
            }
            if (loaded.items.isEmpty() && page < lastPage) {
                // A hole in the middle means the sweep proved nothing about the tail.
                return EpisodeTotalObservation(EpisodeTotal.AtLeast(observed.coerceAtLeast(1)), null)
            }
            observed += loaded.items.size
            if (page == lastPage) lastPageItemCount = loaded.items.size
        }

        val tail = lastPageItemCount
        if (tail == null || tail > safePageSize) {
            return EpisodeTotalObservation(EpisodeTotal.AtLeast(observed.coerceAtLeast(1)), null)
        }
        // The sweep is authoritative for both the total and the shape it was derived from.
        return EpisodeTotalObservation(
            total = EpisodeTotal.Exact(observed),
            snapshot = EpisodeListingSnapshot(lastPage = lastPage, lastPageItemCount = tail),
        )
    }

    /**
     * Whether a background refresh is worth a listing request for this work.
     *
     * The four inputs are deliberately separate axes, not aliases:
     * - [knownTotal] unknown or below the read order means unread episodes may exist. An unknown
     *   total already implies this (`needsRefresh` is true for any lower bound), so a missing
     *   catalog needs no separate condition.
     * - [thumbUrl] blank means the listing cannot be rendered and the episode list can supply a
     *   cover instead.
     * - [isFavorite] keeps favorites accurate because they are the notification targets.
     *
     * `isNew` is intentionally absent: it is a *result* of comparing a work against its seen row,
     * not a reason to scrape, and works that qualify for it already qualify here.
     */
    fun needsEpisodeSync(
        knownTotal: EpisodeTotal,
        lastReadOrder: Int,
        thumbUrl: String,
        isFavorite: Boolean,
    ): Boolean = knownTotal.needsRefresh(lastReadOrder) || thumbUrl.isBlank() || isFavorite

    /** One catalog observation applied to the cached totals and to a persisted history row. */
    data class CatalogApplication<History>(
        val total: EpisodeTotal,
        val history: History?,
        val changed: Boolean,
    )

    /**
     * Applies a catalog observation (server batch row, crowdsourced report or local sweep) to the
     * totals already known for a work.
     *
     * Both platforms previously duplicated this merge plus the "history total/hasNew drifted" check.
     * Keeping it here means a server row, a local sweep and a history-derived lower bound all resolve
     * through exactly one rule.
     */
    fun <History> applyCatalogTotal(
        known: EpisodeTotal?,
        storedHistoryTotal: Int,
        observation: EpisodeTotal,
        history: History?,
        readHistoryOrder: (History) -> Int,
        readHistoryTotal: (History) -> Int,
        readHistoryHasNew: (History) -> Boolean,
        copyHistory: (History, total: Int, order: Int, hasNew: Boolean) -> History,
    ): CatalogApplication<History> {
        val effective = EpisodeTotal.merge(
            EpisodeTotal.merge(known ?: EpisodeTotal.Unknown, EpisodeTotal.fromStored(storedHistoryTotal)),
            observation,
        )
        if (history == null) {
            return CatalogApplication(effective, null, false)
        }
        val merged = mergeHistoryProgress(readHistoryOrder(history), readHistoryTotal(history), effective)
        val changed = readHistoryTotal(history) != merged.total.count || readHistoryHasNew(history) != merged.hasNew
        val updated = if (changed) {
            copyHistory(history, merged.total.count, merged.lastReadOrder, merged.hasNew)
        } else {
            history
        }
        return CatalogApplication(effective, updated, changed)
    }

    fun <Seen, History> applyListingFlags(
        items: List<ToonItem>,
        seenCount: Int,
        favoriteKeys: Set<String>,
        seenByKey: Map<String, Seen>,
        historyByKey: Map<String, History>,
        readCountsByKey: Map<String, Int>,
        catalogTotalsByKey: Map<String, EpisodeTotal> = emptyMap(),
        seenUpdatedAt: (Seen) -> String?,
        formatProgress: (ToonItem, History?, Int, EpisodeTotal) -> String?,
    ): List<ToonItem> = items.map { item ->
        val key = item.workId().storageKey()
        val seen = seenByKey[key]
        val history = historyByKey[key]
        val catalogTotal = catalogTotalsByKey[key] ?: EpisodeTotal.Unknown
        val isUpdated = seen != null &&
            !item.updatedAt.isNullOrBlank() &&
            seenUpdatedAt(seen) != item.updatedAt
        item.copy(
            isNew = item.isNew || (seenCount > 0 && (seen == null || isUpdated)),
            isFavorite = key in favoriteKeys,
            readProgress = formatProgress(item, history, readCountsByKey[key] ?: 0, catalogTotal)?.ifBlank { null },
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
