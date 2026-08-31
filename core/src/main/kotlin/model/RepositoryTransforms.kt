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

    data class ReaderSettingValues(
        val viewMode: ViewMode,
        val readDirection: ReadDirection,
        val splitMode: SplitMode,
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

    fun estimateTotalEpisodes(
        lastPage: Int,
        pageSize: Int,
        lastPageItemCount: Int? = null,
        fallbackCount: Int = 0,
    ): Int {
        val safeLast = lastPage.coerceAtLeast(1)
        if (safeLast == 1) return lastPageItemCount ?: fallbackCount
        val safePageSize = pageSize.coerceAtLeast(1)
        return (safeLast - 1) * safePageSize + (lastPageItemCount ?: fallbackCount)
    }

    fun estimateTotalEpisodes(
        firstPage: EpisodePage,
        pageSize: Int,
        lastPageItemCount: Int? = null,
    ): Int {
        if (firstPage.items.isEmpty()) return 0
        return estimateTotalEpisodes(
            lastPage = firstPage.lastPage,
            pageSize = pageSize,
            lastPageItemCount = lastPageItemCount,
            fallbackCount = firstPage.items.size,
        )
    }

    fun <Seen, History> applyListingFlags(
        items: List<ToonItem>,
        seenCount: Int,
        favoriteKeys: Set<String>,
        seenByKey: Map<String, Seen>,
        historyByKey: Map<String, History>,
        readCountsByKey: Map<String, Int>,
        seenUpdatedAt: (Seen) -> String?,
        formatProgress: (ToonItem, History, Int) -> String,
    ): List<ToonItem> = items.map { item ->
        val key = item.workId().storageKey()
        val seen = seenByKey[key]
        val history = historyByKey[key]
        val isUpdated = seen != null &&
            !item.updatedAt.isNullOrBlank() &&
            seenUpdatedAt(seen) != item.updatedAt
        item.copy(
            isNew = seenCount > 0 && (seen == null || isUpdated),
            isFavorite = key in favoriteKeys,
            readProgress = history?.let {
                formatProgress(item, it, readCountsByKey[key] ?: 0)
            },
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
