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

    /** Nozomi/HTML path segment: Hitomi uses spaces (`%20`), not underscores. */
    fun forIndexPath(raw: String): String =
        normalize(raw).replace('_', ' ')

    fun fromArtistUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val encoded = artistUrlRegex.find(url)?.groupValues?.get(1) ?: return null
        val decoded = try {
            URLDecoder.decode(encoded, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            encoded
        }
        val withoutExt = decoded.replace('+', ' ').removeSuffix(".html")
        val stripped = langSuffix.replace(withoutExt, "")
        return stripped.takeIf { it.isNotBlank() }?.let(::normalize)
    }
}
