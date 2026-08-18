package com.comics8.core.source.hitomi

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object ArtistSlug {
    private val artistUrlRegex = Regex("""/artist/([^/?#]+)""", RegexOption.IGNORE_CASE)
    private val langSuffix = Regex(
        """-(all|korean|english|japanese|chinese|spanish|french|russian|indonesian|vietnamese|thai|italian|german|polish)$""",
        RegexOption.IGNORE_CASE,
    )

    fun normalize(raw: String): String =
        raw.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), "_")

    fun fromArtistUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val encoded = artistUrlRegex.find(url)?.groupValues?.get(1) ?: return null
        val decoded = try {
            URLDecoder.decode(encoded, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            encoded
        }
        val withoutExt = decoded.removeSuffix(".html")
        return langSuffix.replace(withoutExt, "").ifBlank { null }
    }
}
