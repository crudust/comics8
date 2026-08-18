package com.comics8.core.network

import com.comics8.core.model.EpisodeItem
import com.comics8.core.parser.ComicSite
import com.comics8.core.source.ElevenToonSource
import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceHttp
import com.comics8.core.sync.SyncConstants
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ToonClient(
    private val client: OkHttpClient = defaultClient(),
    @Volatile var proxyBaseUrl: String? = SyncConstants.proxyBaseUrl(),
    @Volatile var isProxyEnabled: Boolean = true,
) : SourceHttp {
    private val defaultHtmlPolicy = RequestPolicy(
        userAgent = USER_AGENT,
        extraHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.8",
        ),
    )

    fun fetch(url: String): String = fetchText(FetchSpec(url, defaultHtmlPolicy))

    override fun fetch(spec: FetchSpec): HttpResult {
        val proxyBase = proxyBaseUrl
        if (isProxyEnabled &&
            !proxyBase.isNullOrBlank() &&
            !spec.url.contains("/proxy?url=") &&
            shouldUseProxy(spec.url)
        ) {
            val proxiedUrl = SyncConstants.proxyUrl(spec.url, proxyBase)
            try {
                val proxied = executeFetch(spec.copy(url = proxiedUrl))
                if (proxied.code in 200..299) {
                    return proxied
                }
                System.err.println("ToonClient: Proxy fetch failed ($proxiedUrl): HTTP ${proxied.code}. Falling back to direct fetch.")
            } catch (e: Exception) {
                System.err.println("ToonClient: Proxy fetch failed ($proxiedUrl): ${e.message}. Falling back to direct fetch.")
            }
        }
        return executeFetch(spec)
    }

    override fun fetchText(spec: FetchSpec): String {
        val result = fetch(spec)
        if (result.code !in 200..299) {
            error("HTTP ${result.code}")
        }
        return result.body.toString(Charsets.UTF_8)
    }

    override fun isAccessible(url: String, policy: RequestPolicy): Boolean {
        if (url.isBlank()) return false
        return try {
            executeHead(FetchSpec(url, policy))
        } catch (_: Exception) {
            false
        }
    }

    fun isUrlAccessible(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return isAccessible(url, RequestPolicy(userAgent = USER_AGENT, referer = ImageReferer.forUrl(url)))
    }

    /**
     * Fetches an episode page and extracts the image URLs to display in a reader.
     * When multiple candidate lists (e.g. img_list and img_list_2) exist,
     * automatically verifies and selects the working CDN image list.
     */
    fun fetchImages(url: String): List<String> {
        val episode = EpisodeItem(wrId = "", title = "", date = null, thumbUrl = null, href = url)
        return ElevenToonSource().resolveImages(episode, this)
    }

    /**
     * Downloads raw bytes for an image URL.
     */
    fun fetchBytes(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .header("Referer", ImageReferer.forUrl(url))
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun executeFetch(spec: FetchSpec): HttpResult {
        val request = buildRequest(spec, head = false)
        client.newCall(request).execute().use { response ->
            val headers = linkedMapOf<String, String>()
            for (name in response.headers.names()) {
                val value = response.header(name) ?: continue
                headers[name] = value
            }
            val rawBody = response.body?.bytes() ?: ByteArray(0)
            val result = HttpResult(response.code, headers, rawBody)
            return applyRangeSlice(spec, result)
        }
    }

    private fun executeHead(spec: FetchSpec): Boolean {
        val request = buildRequest(spec, head = true)
        return client.newCall(request).execute().use { it.isSuccessful }
    }

    private fun buildRequest(spec: FetchSpec, head: Boolean): Request {
        val builder = Request.Builder().url(spec.url)
        builder.header("User-Agent", spec.policy.userAgent)
        val referer = spec.policy.referer
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        for ((key, value) in spec.policy.extraHeaders) {
            if (key.equals("Range", ignoreCase = true)) continue
            builder.header(key, value)
        }
        for ((key, value) in spec.headers) {
            builder.header(key, value)
        }
        if (head) builder.head() else builder.get()
        return builder.build()
    }

    private fun applyRangeSlice(spec: FetchSpec, result: HttpResult): HttpResult {
        val rangeHeader = spec.headers.entries
            .firstOrNull { it.key.equals("Range", ignoreCase = true) }
            ?.value
            ?: return result
        if (result.code != 200) return result
        val total = result.body.size
        val bounds = parseBytesRange(rangeHeader, total) ?: return result
        val (start, endInclusive) = bounds
        if (start == 0 && endInclusive == total - 1) return result
        val sliced = result.body.copyOfRange(start, endInclusive + 1)
        val headers = LinkedHashMap(result.headers)
        putHeader(headers, "Content-Length", sliced.size.toString())
        putHeader(headers, "Content-Range", "bytes $start-$endInclusive/$total")
        return HttpResult(result.code, headers, sliced)
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

        internal fun shouldUseProxy(url: String): Boolean {
            val host = hostOf(url) ?: return true
            if (host == "hitomi.la" || host.endsWith(".hitomi.la")) return false
            if (host == "gold-usergeneratedcontent.net" || host.endsWith(".gold-usergeneratedcontent.net")) {
                return false
            }
            return true
        }

        private fun hostOf(url: String): String? {
            return try {
                java.net.URI(url).host?.lowercase()
            } catch (_: Exception) {
                null
            }
        }

        private fun parseBytesRange(header: String, total: Int): Pair<Int, Int>? {
            if (total <= 0) return null
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true)) return null
            val spec = value.substringAfter('=')
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val startRaw = spec.substring(0, dash)
            val endRaw = spec.substring(dash + 1)
            return when {
                startRaw.isEmpty() && endRaw.isNotEmpty() -> {
                    val suffix = endRaw.toIntOrNull() ?: return null
                    if (suffix <= 0) return null
                    val start = (total - suffix).coerceAtLeast(0)
                    start to (total - 1)
                }
                startRaw.isNotEmpty() -> {
                    val start = startRaw.toIntOrNull() ?: return null
                    val end = if (endRaw.isEmpty()) total - 1 else endRaw.toIntOrNull() ?: return null
                    if (start < 0 || start >= total || end < start) return null
                    start to end.coerceAtMost(total - 1)
                }
                else -> null
            }
        }

        private fun putHeader(headers: MutableMap<String, String>, name: String, value: String) {
            val existing = headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (existing != null) {
                headers[existing] = value
            } else {
                headers[name] = value
            }
        }
    }
}
