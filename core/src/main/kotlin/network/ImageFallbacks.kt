package com.comics8.core.network

import com.comics8.core.source.SourceRegistry

object ImageFallbacks {
    fun forUrl(url: String, registry: SourceRegistry): List<String> =
        registry.sourceForUrl(url)?.imageFallbacks(url).orEmpty()

    fun urlsToTry(url: String, registry: SourceRegistry): List<String> {
        val out = LinkedHashSet<String>()
        out.add(url)
        out.addAll(forUrl(url, registry))
        return out.toList()
    }

    fun fetchBytes(
        url: String,
        registry: SourceRegistry,
        fetch: (String) -> ByteArray,
    ): ByteArray {
        var lastError: Exception? = null
        for (candidate in urlsToTry(url, registry)) {
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
