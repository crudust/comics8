package com.comics8.core.parser

import com.comics8.core.model.Catalog

object ComicSite {
    const val ORIGIN = "http://103.204.13.68:8904"
    const val COVER_HOST = "https://11toon8.com"

    fun listingUrl(catalog: Catalog, page: Int): String = ORIGIN + catalog.listingPath(page)

    fun searchUrl(query: String): String =
        "$ORIGIN/bbs/ajax.search.php?search_key=${Catalog.enc(query)}"

    fun seriesUrl(id: String, title: String, page: Int = 1): String {
        val stx = Catalog.enc(title)
        return "$ORIGIN/bbs/board.php?bo_table=toons&stx=$stx&is=$id&page=$page"
    }

    fun coverUrl(id: String): String = "$COVER_HOST/data/toon_category/$id.webp"

    fun absUrl(raw: String, base: String = ORIGIN): String {
        val value = raw.trim().trim('\'', '"')
        if (value.isEmpty()) return value
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> base.trimEnd('/') + value
            else -> "$base/$value"
        }
    }
}
