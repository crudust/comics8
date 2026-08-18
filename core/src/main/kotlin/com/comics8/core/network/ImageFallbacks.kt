package com.comics8.core.network

import com.comics8.core.parser.ComicSite
import com.comics8.core.source.hitomi.HitomiUrls

object ImageFallbacks {
    fun forUrl(url: String): List<String> {
        val out = LinkedHashSet<String>()
        out.addAll(ComicSite.getFallbackImageUrls(url))
        HitomiUrls.altImageUrl(url)?.let { out.add(it) }
        return out.toList()
    }

    fun urlsToTry(url: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(url)
        out.addAll(forUrl(url))
        return out.toList()
    }

    fun fetchBytes(url: String, fetch: (String) -> ByteArray): ByteArray {
        var lastError: Exception? = null
        for (candidate in urlsToTry(url)) {
            try {
                val bytes = fetch(candidate)
                if (bytes.isNotEmpty()) return bytes
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("empty image response: $url")
    }
}
