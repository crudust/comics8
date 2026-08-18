package com.comics8.core.source

import java.net.URI

class SourceRegistry(private val sources: List<ComicSource>) {
    init {
        require(sources.isNotEmpty()) { "at least one ComicSource is required" }
        require(sources.map { it.id }.toSet().size == sources.size) { "duplicate source id" }
    }

    private val byId = sources.associateBy { it.id }

    val default: ComicSource = byId[WorkId.DEFAULT_SOURCE] ?: sources.first()

    fun get(id: String): ComicSource {
        val key = id.ifBlank { WorkId.DEFAULT_SOURCE }
        return byId[key] ?: error("Unknown comic source: $id")
    }

    fun getOrNull(id: String): ComicSource? {
        val key = id.ifBlank { WorkId.DEFAULT_SOURCE }
        return byId[key]
    }

    fun all(): List<ComicSource> = sources

    fun knownIds(): Set<String> = byId.keys

    fun contains(id: String): Boolean = getOrNull(id) != null

    fun resolveActive(stored: String?): ComicSource =
        getOrNull(SourcePrefs.resolveActiveId(stored, knownIds())) ?: default

    fun sourceForUrl(url: String): ComicSource {
        val host = hostOf(url) ?: return default
        return sources.firstOrNull { it.ownsHost(host) } ?: default
    }

    fun applyConfig(config: SourceConfig) {
        sources.forEach { it.applyConfig(config) }
    }

    fun applyPreferences(languageFor: (String) -> String?) {
        sources.forEach { source ->
            val language = languageFor(source.id) ?: source.defaultLanguage
            if (language != null) {
                source.applyConfig(SourceConfig(language = language))
            }
        }
    }

    fun ownsHost(host: String): Boolean {
        val key = host.lowercase().trim().trim('.')
        if (key.isEmpty()) return false
        return sources.any { it.ownsHost(key) }
    }

    companion object {
        fun defaults(): SourceRegistry = SourceRegistry(BuiltInSources.all())

        fun isRegistered(sourceId: String): Boolean {
            val key = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
            return key in knownIds()
        }

        fun knownIds(): Set<String> = cachedKnownIds

        fun chipLabel(sourceId: String): String {
            val key = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
            return chipLabels[key] ?: chipLabels.getValue(WorkId.DEFAULT_SOURCE)
        }

        fun searchPlaceholder(sourceId: String): String {
            val key = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
            return searchPlaceholders[key] ?: cachedDefaults.default.searchPlaceholder
        }

        fun hostOf(url: String): String? =
            try {
                URI(url).host?.lowercase()
            } catch (_: Exception) {
                null
            }

        private val cachedDefaults by lazy { defaults() }

        private val cachedKnownIds by lazy { cachedDefaults.knownIds() }

        private val chipLabels by lazy {
            cachedDefaults.all().associate { it.id to it.displayName }
        }

        private val searchPlaceholders by lazy {
            cachedDefaults.all().associate { it.id to it.searchPlaceholder }
        }
    }
}
