package com.comics8.core.source.hitomi

data class SearchToken(
    val ns: String,
    val value: String,
)

object HitomiSearch {
    val namespaces = setOf(
        "language", "type", "female", "male", "tag", "artist", "series", "character", "group",
    )
    val seriesNamespaces = setOf("artist", "series", "character", "group")

    private val colonSpace = Regex(
        """(?i)(language|type|female|male|tag|artist|series|character|group):\s+""",
    )

    fun parse(text: String): List<SearchToken> {
        val normalized = colonSpace.replace(text.trim(), "$1:")
        val parts = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val tokens = ArrayList<SearchToken>(parts.size)
        val bare = ArrayList<String>()
        fun flushBare() {
            if (bare.isEmpty()) return
            tokens += SearchToken("artist", slug(bare.joinToString(" ")))
            bare.clear()
        }
        for (part in parts) {
            if (part.startsWith("-")) continue
            val idx = part.indexOf(':')
            if (idx > 0) {
                val ns = part.substring(0, idx).lowercase()
                val value = slug(part.substring(idx + 1))
                if (ns in namespaces && value.isNotEmpty()) {
                    flushBare()
                    tokens += SearchToken(ns, value)
                    continue
                }
            }
            bare += part
        }
        flushBare()
        return tokens
    }

    fun slug(raw: String): String =
        raw.trim().lowercase().replace('_', ' ').replace(Regex("\\s+"), "_")
        .trim('_')

    fun nozomiUrl(token: SearchToken, lang: String): String {
        val slug = ArtistSlug.normalize(token.value)
        return when (token.ns) {
            "language" -> HitomiUrls.indexNozomi(ArtistSlug.normalize(token.value))
            "type" -> HitomiUrls.typeNozomi(slug, lang)
            else -> HitomiUrls.nsNozomi(token.ns, slug, lang)
        }
    }
}
