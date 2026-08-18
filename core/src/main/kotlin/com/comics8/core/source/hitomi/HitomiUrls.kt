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
    const val LANGUAGE_PREF_KEY = "hitomi.language"
    const val DEFAULT_LANGUAGE = "korean"
    const val ACTIVE_SOURCE_PREF_KEY = "pref_active_source_id"

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
        val s = GgJsRouter.pathFromHash(hash)
        return "https://tn.$CDN_HOST/webpsmalltn/$s/$hash.webp"
    }

    fun thumbFallbacks(url: String): List<String> {
        if (url.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        val rewritten = url
            .replace("://tn.hitomi.la/", "://tn.$CDN_HOST/")
            .replace("://atn.hitomi.la/", "://atn.$CDN_HOST/")
        if (rewritten != url) out.add(rewritten)
        val hostSwap = rewritten
            .replace("://tn.$CDN_HOST/", "://atn.$CDN_HOST/")
            .replace("://tn.hitomi.la/", "://atn.$CDN_HOST/")
        if (hostSwap != rewritten && hostSwap != url) out.add(hostSwap)
        if (rewritten.contains("/webpsmalltn/") && rewritten.endsWith(".webp")) {
            out.add(rewritten.replace("/webpsmalltn/", "/avifsmalltn/").replace(Regex("""\.webp$"""), ".avif"))
            out.add(rewritten.replace("/webpsmalltn/", "/webpbigtn/"))
        }
        return out.toList()
    }

    fun imageUrl(hash: String, b: String, n: Int, ext: String = "webp"): String {
        val hostPrefix = if (ext.equals("avif", ignoreCase = true)) "a" else "w"
        val s = GgJsRouter.sFromHash(hash)
        val prefix = if (b.endsWith("/")) b else if (b.isEmpty()) "" else "$b/"
        return "https://$hostPrefix$n.$CDN_HOST/$prefix$s/$hash.$ext"
    }

    fun avifFallback(webpUrl: String): String? = altImageUrl(webpUrl)?.takeIf { it.endsWith(".avif") }

    fun altImageUrl(url: String): String? {
        if (!url.contains(CDN_HOST)) return null
        return when {
            url.contains("://w") && url.endsWith(".webp") ->
                url.replace(Regex("""://w(\d+)\."""), "://a$1.").replace(Regex("""\.webp$"""), ".avif")
            url.contains("://a") && url.endsWith(".avif") ->
                url.replace(Regex("""://a(\d+)\."""), "://w$1.").replace(Regex("""\.avif$"""), ".webp")
            else -> null
        }
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
