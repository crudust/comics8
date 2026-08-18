package com.comics8.core.source.hitomi

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object HitomiUrls {
    const val SOURCE_ID = "hitomi"
    const val ORIGIN = "https://hitomi.la"
    const val REFERER = "https://hitomi.la/"
    const val LTN = "https://ltn.gold-usergeneratedcontent.net"
    const val CDN_HOST = "gold-usergeneratedcontent.net"
    const val PAGE_SIZE = 25
    const val ID_BYTES = 4
    const val ENABLED_PREF_KEY = "sources.hitomi.enabled"
    const val ENABLED_BY_DEFAULT = false
    const val DEFAULT_LANGUAGE = "korean"

    fun indexNozomi(lang: String): String {
        val key = lang.ifBlank { DEFAULT_LANGUAGE }
        return if (key.equals("all", ignoreCase = true)) {
            "$LTN/index-all.nozomi"
        } else {
            "$LTN/index-$key.nozomi"
        }
    }

    fun typeNozomi(type: String, lang: String): String =
        "$LTN/$type-${lang.ifBlank { DEFAULT_LANGUAGE }}.nozomi"

    fun popularNozomi(period: String, lang: String): String =
        "$LTN/popular/$period-${lang.ifBlank { DEFAULT_LANGUAGE }}.nozomi"

    fun nsNozomi(ns: String, slug: String, lang: String): String =
        "$LTN/$ns/${enc(slug)}-${lang.ifBlank { DEFAULT_LANGUAGE }}.nozomi"

    fun galleryJs(id: String): String = "$LTN/galleries/$id.js"

    fun ggJs(): String = "$LTN/gg.js"

    fun reader(id: String): String = "$ORIGIN/reader/$id.html"

    fun galleryPage(id: String): String = "$ORIGIN/galleries/$id.html"

    fun artistPage(slug: String): String = "$ORIGIN/artist/${enc(slug)}-all.html"

    fun rangeHeader(page: Int): String {
        val p = page.coerceAtLeast(1)
        val start = (p - 1) * PAGE_SIZE * ID_BYTES
        val end = p * PAGE_SIZE * ID_BYTES - 1
        return "bytes=$start-$end"
    }

    fun thumbUrl(hash: String): String {
        if (hash.length < 3) return ""
        val last = hash.last()
        val mid = hash.takeLast(3).dropLast(1)
        return "https://tn.hitomi.la/webpsmalltn/$last/$mid/$hash.webp"
    }

    fun imageUrl(hash: String, b: String, n: Int, ext: String = "webp"): String {
        val hostPrefix = if (ext.equals("avif", ignoreCase = true)) "a" else "w"
        val s = GgJsRouter.pathFromHash(hash)
        val prefix = if (b.endsWith("/")) b else if (b.isEmpty()) "" else "$b/"
        return "https://$hostPrefix$n.$CDN_HOST/$prefix$s/$hash.$ext"
    }

    fun avifFallback(webpUrl: String): String? {
        if (!webpUrl.contains(CDN_HOST) || !webpUrl.endsWith(".webp")) return null
        val avif = webpUrl.replace(Regex("""://w(\d+)\."""), "://a$1.").replace(Regex("""\.webp$"""), ".avif")
        return avif.takeIf { it != webpUrl }
    }

    fun isHitomiHost(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "hitomi.la" ||
            host.endsWith(".hitomi.la") ||
            host == CDN_HOST ||
            host.endsWith(".$CDN_HOST")
    }

    fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun hostOf(url: String): String? =
        try {
            URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        }
}
