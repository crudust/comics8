package com.comics8.core.model

import com.comics8.core.source.ComicSource
import com.comics8.core.source.SourceCatalog

sealed interface BrowseTab {
    val label: String
    val paginated: Boolean

    data object Favorite : BrowseTab {
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

        fun forSource(source: ComicSource): List<BrowseTab> =
            source.catalogs.map { Remote(source.id, it) } + Favorite

        fun resolveLaunchTarget(targetId: String?, tabs: List<BrowseTab>): BrowseTab {
            val key = targetId?.trim().orEmpty()
            if (key.isEmpty() || key.equals(FAVORITE_ID, ignoreCase = true)) {
                return Favorite
            }
            return tabs.firstOrNull { tab ->
                tab is Remote && tab.catalog.id.equals(key, ignoreCase = true)
            } ?: Favorite
        }

        fun afterSourceChange(current: BrowseTab, nextTabs: List<BrowseTab>): BrowseTab {
            if (current is Favorite) return Favorite
            if (current is Remote) {
                return nextTabs.firstOrNull { tab ->
                    tab is Remote && tab.catalog.id == current.catalog.id
                } ?: nextTabs.firstOrNull() ?: Favorite
            }
            return nextTabs.firstOrNull() ?: Favorite
        }
    }
}
