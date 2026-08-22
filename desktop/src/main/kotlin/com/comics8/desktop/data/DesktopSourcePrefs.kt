package com.comics8.desktop.data

import com.comics8.core.source.SourcePrefs
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import java.util.prefs.Preferences

object DesktopSourcePrefs {
    private val prefs: Preferences = Preferences.userRoot().node("com.comics8.desktop")

    @Volatile
    lateinit var registry: SourceRegistry

    fun isEnabled(sourceId: String): Boolean {
        val id = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        if (id == WorkId.DEFAULT_SOURCE) return true
        val default = registry.getOrNull(id)?.enabledByDefault ?: false
        return prefs.getBoolean(SourcePrefs.enabledKey(id), default)
    }

    fun setEnabled(sourceId: String, enabled: Boolean) {
        val id = sourceId.ifBlank { return }
        if (id == WorkId.DEFAULT_SOURCE) return
        prefs.putBoolean(SourcePrefs.enabledKey(id), enabled)
    }

    fun language(sourceId: String): String {
        val fallback = registry.getOrNull(sourceId)?.defaultLanguage.orEmpty()
        return prefs.get(SourcePrefs.languageKey(sourceId), fallback).ifBlank { fallback }
    }

    fun setLanguage(sourceId: String, value: String) {
        val fallback = registry.getOrNull(sourceId)?.defaultLanguage.orEmpty()
        prefs.put(SourcePrefs.languageKey(sourceId), value.ifBlank { fallback })
    }

    var activeSourceId: String
        get() = SourcePrefs.resolveActiveId(
            prefs.get(SourcePrefs.ACTIVE_SOURCE_KEY, WorkId.DEFAULT_SOURCE),
            registry.knownIds(),
        ) ?: WorkId.DEFAULT_SOURCE
        set(value) {
            prefs.put(SourcePrefs.ACTIVE_SOURCE_KEY, value.ifBlank { WorkId.DEFAULT_SOURCE })
        }
}
