package com.comics8.core.network

import com.comics8.core.source.SourceRegistry

object ImageReferer {
    fun forUrl(url: String, registry: SourceRegistry): String {
        if (url.startsWith("file:", true) ||
            url.startsWith("comics8-zip:", true) ||
            url.startsWith("comics8-net:", true) ||
            url.startsWith("content:", true)
        ) {
            return ""
        }
        return registry.sourceForUrl(url)?.imageReferer(url).orEmpty()
    }
}
