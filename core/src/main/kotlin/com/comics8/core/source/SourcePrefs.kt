package com.comics8.core.source

object SourcePrefs {
    const val ACTIVE_SOURCE_KEY = "pref_active_source_id"

    fun enabledKey(sourceId: String): String = "sources.$sourceId.enabled"

    fun languageKey(sourceId: String): String = "$sourceId.language"

    /**
     * Active source. Blank or not installed → null. No eleven fallback.
     */
    fun resolveActiveId(stored: String?, installed: Set<String>): String? {
        val key = stored?.trim().orEmpty()
        if (key.isEmpty() || key !in installed) return null
        return key
    }
}
