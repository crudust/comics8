package com.comics8.core.source.eleven

object ComicSite {
    const val ORIGIN = "http://103.204.13.68:8904"
    const val COVER_HOST = "https://11toon8.com"

    fun listingUrl(catalog: ElevenCatalog, page: Int): String = ORIGIN + catalog.listingPath(page)

    fun searchUrl(query: String): String =
        "$ORIGIN/bbs/ajax.search.php?search_key=${ElevenCatalog.enc(query)}"

    fun seriesUrl(id: String, title: String, page: Int = 1): String {
        val stx = ElevenCatalog.enc(title)
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

    fun getFallbackImageUrls(url: String): List<String> {
        if (url.isBlank()) return emptyList()
        val fallbacks = LinkedHashSet<String>()
        val knownDomains = listOf("pl4050.com", "pl3040.com", "pl3030.com", "pl4040.com", "pl1020.com", "pl2030.com", "pl5060.com")
        val domainRegex = Regex("""pl\d{4}\.com""")
        for (domain in knownDomains) {
            if (!url.contains(domain)) {
                val replaced = url.replace(domainRegex, domain)
                if (replaced != url) {
                    fallbacks.add(replaced)
                    val withParam = if (replaced.contains("?")) {
                        if (!replaced.contains("v=ei")) "$replaced&v=ei" else replaced
                    } else {
                        "$replaced?v=ei"
                    }
                    fallbacks.add(withParam)
                }
            }
        }
        return fallbacks.toList()
    }
}
