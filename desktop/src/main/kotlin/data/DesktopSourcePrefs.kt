package com.comics8.desktop.data

import com.comics8.core.source.SourcePrefs
import com.comics8.core.source.SourcePreferenceStore
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.SourceSettings
import com.comics8.core.source.StoredSourceSettings
import java.util.prefs.Preferences

private val desktopPrefs: Preferences = Preferences.userRoot().node("com.comics8.desktop")
private val desktopStored = StoredSourceSettings(DesktopPreferenceStore(desktopPrefs)) { sourceId ->
    DesktopSourcePrefs.registry.getOrNull(sourceId)?.defaultLanguage
}

object DesktopSourcePrefs : SourceSettings by desktopStored {

    @Volatile
    lateinit var registry: SourceRegistry

    fun migrateIfNeeded() = desktopStored.migrateIfNeeded()
}

private class DesktopPreferenceStore(private val prefs: Preferences) : SourcePreferenceStore {
    override fun contains(key: String): Boolean = try { key in prefs.keys() } catch (_: Exception) { false }
    override fun getString(key: String): String? = if (contains(key)) prefs.get(key, "") else null
    override fun putString(key: String, value: String) = prefs.put(key, value)
    override fun remove(key: String) = prefs.remove(key)
    override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) = prefs.putBoolean(key, value)
}
