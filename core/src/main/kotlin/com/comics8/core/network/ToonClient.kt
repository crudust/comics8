package com.comics8.core.network

import com.comics8.core.parser.ComicSite
import com.comics8.core.parser.HtmlParsers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ToonClient(
    private val client: OkHttpClient = defaultClient(),
) {
    fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    fun isUrlAccessible(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", ComicSite.ORIGIN)
                .head()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fetches an episode page and extracts the image URLs to display in a reader.
     * When multiple candidate lists (e.g. img_list and img_list_2) exist,
     * automatically verifies and selects the working CDN image list.
     */
    fun fetchImages(url: String): List<String> {
        val html = fetch(url)
        val candidateLists = HtmlParsers.parseAllImageCandidateLists(html, ComicSite.ORIGIN)
        if (candidateLists.isEmpty()) {
            val parsed = HtmlParsers.parseImages(html, ComicSite.ORIGIN)
            if (parsed.isEmpty()) {
                error("만화 이미지를 불러오지 못했습니다.")
            }
            return parsed
        }
        if (candidateLists.size == 1) {
            return candidateLists[0]
        }
        // Check primary candidate list first
        val primary = candidateLists[0]
        if (isUrlAccessible(primary.firstOrNull())) {
            return primary
        }
        // Try fallback lists (e.g. img_list_2)
        for (i in 1 until candidateLists.size) {
            val candidate = candidateLists[i]
            if (isUrlAccessible(candidate.firstOrNull())) {
                return candidate
            }
        }
        return primary
    }

    /**
     * Downloads raw bytes for an image URL.
     */
    fun fetchBytes(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .header("Referer", ComicSite.ORIGIN)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
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
    }
}
