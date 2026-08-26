package com.comics8.core.model

import com.comics8.core.source.ComicSource
import com.comics8.core.source.SourceCatalog

sealed interface BrowseTab {
    val label: String
    val paginated: Boolean

    data class Favorite(val sourceId: String) : BrowseTab {
        override val label: String = "즐겨찾기"
        override val paginated: Boolean = false
    }

    data class Remote(
        val sourceId: String,
        val catalog: SourceCatalog,
    ) : BrowseTab {
        override val label: String get() = catalog.label
        override val paginated: Boolean get() = catalog.paginated
    }

    companion object {
        const val FAVORITE_ID = "FAVORITE"

        fun forSource(source: ComicSource): List<BrowseTab> {
            val remotes = source.catalogs.map { Remote(source.id, it) }
            val favorite = Favorite(source.id)
            val latestAt = remotes.indexOfFirst { it.catalog.id.equals("LATEST", ignoreCase = true) }
            return if (latestAt >= 0) {
                remotes.take(latestAt + 1) + favorite + remotes.drop(latestAt + 1)
            } else {
                remotes + favorite
            }
        }

        fun resolveLaunchTarget(targetId: String?, tabs: List<BrowseTab>): BrowseTab? {
            if (tabs.isEmpty()) return null
            val key = targetId?.trim().orEmpty()
            val favorite = tabs.firstOrNull { it is Favorite }
            if (key.isEmpty() || key.equals(FAVORITE_ID, ignoreCase = true)) {
                return favorite ?: tabs.first()
            }
            return tabs.firstOrNull { tab ->
                tab is Remote && tab.catalog.id.equals(key, ignoreCase = true)
            } ?: favorite ?: tabs.first()
        }

        fun afterSourceChange(current: BrowseTab?, nextTabs: List<BrowseTab>): BrowseTab? {
            if (nextTabs.isEmpty()) return null
            if (current is Favorite) {
                return nextTabs.firstOrNull { it is Favorite } ?: nextTabs.first()
            }
            if (current is Remote) {
                return nextTabs.firstOrNull { tab ->
                    tab is Remote && tab.catalog.id == current.catalog.id
                } ?: nextTabs.first()
            }
            return nextTabs.first()
        }
    }
}
