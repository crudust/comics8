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
        fun forSource(source: ComicSource): List<BrowseTab> =
            source.catalogs.map { Remote(source.id, it) } + Favorite
    }
}
