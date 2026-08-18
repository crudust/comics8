package com.comics8.core.source

object SourcePrefs {
    const val ACTIVE_SOURCE_KEY = "pref_active_source_id"

    fun enabledKey(sourceId: String): String = "sources.$sourceId.enabled"

    fun languageKey(sourceId: String): String = "$sourceId.language"

    fun resolveActiveId(stored: String?, knownIds: Collection<String>): String {
        val key = stored?.trim().orEmpty().ifBlank { WorkId.DEFAULT_SOURCE }
        return if (key in knownIds) key else WorkId.DEFAULT_SOURCE
    }
}
