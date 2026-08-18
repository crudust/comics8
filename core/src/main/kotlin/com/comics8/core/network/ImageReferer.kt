package com.comics8.core.network

import com.comics8.core.source.SourceRegistry

object ImageReferer {
    fun forUrl(url: String, registry: SourceRegistry = SourceRegistry.defaults()): String =
        registry.sourceForUrl(url).imageReferer(url)
}
