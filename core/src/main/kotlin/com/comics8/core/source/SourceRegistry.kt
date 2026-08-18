package com.comics8.core.source

import com.comics8.core.source.hitomi.HitomiSource

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

    fun all(): List<ComicSource> = sources

    companion object {
        fun defaults(): SourceRegistry = SourceRegistry(
            listOf(ElevenToonSource(), HitomiSource()),
        )

        private val chipLabels by lazy {
            defaults().all().associate { it.id to it.displayName }
        }

        fun chipLabel(sourceId: String): String {
            val key = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
            return chipLabels[key] ?: chipLabels.getValue(WorkId.DEFAULT_SOURCE)
        }
    }
}
