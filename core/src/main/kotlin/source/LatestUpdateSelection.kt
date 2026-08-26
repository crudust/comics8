package com.comics8.core.source

import com.comics8.core.model.ToonItem

object LatestUpdateSelection {
    fun candidates(
        source: ComicSource,
        favorites: List<ToonItem>,
        latestItems: List<ToonItem>?,
        sourceEnabled: Boolean,
        notificationEnabled: Boolean = true,
    ): List<ToonItem> {
        if (favorites.isEmpty()) return emptyList()
        if (!sourceEnabled || !notificationEnabled) return emptyList()
        return when (source.notificationMode) {
            NotificationMode.NONE -> emptyList()
            NotificationMode.LATEST_INTERSECTION -> {
                val page = latestItems ?: return emptyList()
                val keys = favorites.map { it.workId().storageKey() }.toSet()
                page.filter { it.workId().storageKey() in keys }
            }
            NotificationMode.PER_FAVORITE -> source.notificationCandidates(favorites)
        }
    }

    fun intersection(latest: List<ToonItem>, favoriteKeys: Set<String>): List<ToonItem> =
        latest.filter { it.workId().storageKey() in favoriteKeys }
}
