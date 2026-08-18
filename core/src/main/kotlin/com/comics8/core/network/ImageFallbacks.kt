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
}
