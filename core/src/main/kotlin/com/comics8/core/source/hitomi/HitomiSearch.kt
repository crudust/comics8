package com.comics8.core.source.hitomi

data class SearchToken(
    val ns: String,
    val value: String,
)

object HitomiSearch {
    private val allowed = setOf(
        "language", "type", "female", "male", "tag", "artist", "series", "character", "group",
    )
    private val tokenRegex = Regex(
        """^(language|type|female|male|tag|artist|series|character|group):(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): List<SearchToken>? {
        val parts = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val tokens = ArrayList<SearchToken>(parts.size)
        for (part in parts) {
            if (part.startsWith("-")) return null
            val match = tokenRegex.matchEntire(part) ?: return null
            val ns = match.groupValues[1].lowercase()
            val value = match.groupValues[2].trim()
            if (ns !in allowed || value.isEmpty()) return null
            tokens += SearchToken(ns, value)
        }
        return tokens
    }

    fun nozomiUrl(token: SearchToken, lang: String): String {
        val slug = ArtistSlug.normalize(token.value)
        return when (token.ns) {
            "language" -> HitomiUrls.indexNozomi(ArtistSlug.normalize(token.value))
            "type" -> HitomiUrls.typeNozomi(slug, lang)
            else -> HitomiUrls.nsNozomi(token.ns, slug, lang)
        }
    }

    fun intersectInOrder(order: List<Long>, sets: List<Set<Long>>): List<Long> {
        if (sets.isEmpty()) return order
        return order.filter { id -> sets.all { id in it } }
    }
}
