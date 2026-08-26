package com.comics8.core.source

import java.net.URI
import java.util.concurrent.atomic.AtomicReference

fun interface SourceLocator {
    fun registry(): SourceRegistry
}

class SourceRegistry(initial: List<ComicSource> = emptyList()) {
    private val snapshot = AtomicReference(Snapshot.from(initial))

    fun replaceAll(sources: List<ComicSource>) {
        snapshot.set(Snapshot.from(sources))
    }

    /**
     * Mutate only on the UI thread. Image/proxy readers use [all]/[sourceForUrl]/[getOrNull].
     */
    fun add(source: ComicSource): Boolean {
        while (true) {
            val prev = snapshot.get()
            if (prev.byId[source.id] != null) return false
            val next = Snapshot.from(prev.sources + source)
            if (snapshot.compareAndSet(prev, next)) return true
        }
    }

    fun remove(id: String): ComicSource? {
        while (true) {
            val prev = snapshot.get()
            val removed = prev.byId[id] ?: return null
            val next = Snapshot.from(prev.sources.filterNot { it.id == id })
            if (snapshot.compareAndSet(prev, next)) return removed
        }
    }

    fun get(id: String): ComicSource =
        getOrNull(id) ?: error("Unknown comic source: $id")

    fun getOrNull(id: String): ComicSource? {
        if (id.isBlank()) return null
        return snapshot.get().byId[id]
    }

    fun all(): List<ComicSource> = snapshot.get().sources.toList()
    fun knownIds(): Set<String> = snapshot.get().byId.keys.toSet()
    fun contains(id: String): Boolean = getOrNull(id) != null

    fun chipLabel(sourceId: String): String =
        getOrNull(sourceId)?.displayName ?: sourceId.ifBlank { "" }

    fun searchPlaceholder(sourceId: String): String =
        getOrNull(sourceId)?.searchPlaceholder ?: "제목 검색"

    fun defaultProgressDisplayMode(sourceId: String): com.comics8.core.model.ProgressDisplayMode =
        getOrNull(sourceId)?.defaultProgressDisplayMode
            ?: com.comics8.core.model.ProgressDisplayMode.defaultFor(sourceId)

    fun progressDisplayMode(sourceId: String, settings: SourceSettings? = null): com.comics8.core.model.ProgressDisplayMode =
        settings?.progressDisplayMode(sourceId) ?: defaultProgressDisplayMode(sourceId)

    fun progressDisplay(sourceId: String): ProgressDisplay =
        getOrNull(sourceId)?.progressDisplay ?: ProgressDisplay.LAST_READ_ORDER

    fun formatReadProgress(
        sourceId: String,
        lastReadOrder: Int,
        totalEpisodes: Int,
        readCount: Int,
        mode: com.comics8.core.model.ProgressDisplayMode? = null,
    ): String = (mode ?: defaultProgressDisplayMode(sourceId)).format(lastReadOrder, totalEpisodes, readCount).orEmpty()

    /**
     * Restore a stored active id only if it is loaded (and installed, when given).
     * [installedIds] null means loaded ids only (tests).
     */
    fun resolveActive(stored: String?, installedIds: Set<String>? = null): ComicSource? {
        val key = stored?.trim().orEmpty()
        if (key.isEmpty()) return null
        if (installedIds != null && key !in installedIds) return null
        return getOrNull(key)
    }

    fun sourceForUrl(url: String): ComicSource? {
        val host = hostOf(url) ?: return null
        return snapshot.get().sources.firstOrNull { it.ownsHost(host) }
    }

    fun applyConfig(config: SourceConfig) {
        snapshot.get().sources.forEach { it.applyConfig(config) }
    }

    fun applyPreferences(languageFor: (String) -> String?) {
        snapshot.get().sources.forEach { source ->
            val language = languageFor(source.id) ?: source.defaultLanguage
            if (language != null) source.applyConfig(SourceConfig(language = language))
        }
    }

    fun ownsHost(host: String): Boolean {
        val key = host.lowercase().trim().trim('.')
        if (key.isEmpty()) return false
        return snapshot.get().sources.any { it.ownsHost(key) }
    }

    fun syncParticipates(sourceId: String): Boolean =
        getOrNull(sourceId)?.syncParticipates == true

    private data class Snapshot(
        val sources: List<ComicSource>,
        val byId: Map<String, ComicSource>,
    ) {
        companion object {
            fun from(sources: List<ComicSource>): Snapshot {
                require(sources.map { it.id }.toSet().size == sources.size) { "duplicate source id" }
                return Snapshot(sources.toList(), sources.associateBy { it.id })
            }
        }
    }

    companion object {
        fun hostOf(url: String): String? =
            try {
                URI(url).host?.lowercase()
            } catch (_: Exception) {
                null
            }

        /** Test helper. Production ToonClient, image stack, and UI must not call this. */
        fun forTests(sources: List<ComicSource> = emptyList()): SourceRegistry =
            SourceRegistry(sources)
    }
}
