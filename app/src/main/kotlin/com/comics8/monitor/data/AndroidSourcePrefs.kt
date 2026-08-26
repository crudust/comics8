package com.comics8.monitor.data

import android.content.SharedPreferences
import com.comics8.core.source.SourcePrefs
import com.comics8.core.source.SourceSettings
import com.comics8.core.source.WorkId

class AndroidSourcePrefs(
    private val prefs: SharedPreferences,
) : SourceSettings {
    fun migrateIfNeeded() {
        val storedInstalled = rawPref(SourcePrefs.INSTALLED_KEY)
        val result = SourcePrefs.migrateInstalled(
            storedInstalled = storedInstalled,
            storedActive = storedActiveRaw(),
        )
        val editor = prefs.edit()
        if (result.wrote) {
            editor.putString(SourcePrefs.INSTALLED_KEY, SourcePrefs.formatIdList(result.installed))
            editor.putString(SourcePrefs.INSTALL_MIGRATED_KEY, "1")
        }
        if (result.activeId == null) {
            editor.remove(SourcePrefs.ACTIVE_SOURCE_KEY)
        } else if (storedActiveRaw() != result.activeId) {
            editor.putString(SourcePrefs.ACTIVE_SOURCE_KEY, result.activeId)
        }
        editor.apply()
    }

    override fun installedIds(): Set<String> =
        SourcePrefs.withLocal(SourcePrefs.parseIdList(rawPref(SourcePrefs.INSTALLED_KEY)))

    override fun setInstalledIds(ids: Set<String>) {
        prefs.edit().putString(
            SourcePrefs.INSTALLED_KEY,
            SourcePrefs.formatIdList(SourcePrefs.withLocal(ids)),
        ).apply()
    }

    override fun storedActiveRaw(): String? = rawPref(SourcePrefs.ACTIVE_SOURCE_KEY)

    override fun activeSourceId(): String? =
        SourcePrefs.resolveActiveId(storedActiveRaw(), installedIds()) ?: WorkId.LOCAL_SOURCE

    override fun setActiveSourceId(id: String?) {
        val key = id?.trim().orEmpty()
        val editor = prefs.edit()
        if (key.isEmpty()) {
            editor.remove(SourcePrefs.ACTIVE_SOURCE_KEY)
        } else {
            editor.putString(SourcePrefs.ACTIVE_SOURCE_KEY, key)
        }
        editor.apply()
    }

    override fun language(sourceId: String): String? =
        rawPref(SourcePrefs.languageKey(sourceId))?.ifBlank { null }

    override fun setLanguage(sourceId: String, value: String) {
        prefs.edit().putString(SourcePrefs.languageKey(sourceId), value).apply()
    }

    override fun libraryRoots(): List<String> =
        SourcePrefs.parseIdList(rawPref(SourcePrefs.LIBRARY_ROOTS_KEY))

    override fun setLibraryRoots(paths: List<String>) {
        prefs.edit().putString(SourcePrefs.LIBRARY_ROOTS_KEY, SourcePrefs.formatIdList(paths)).apply()
    }

    override fun progressDisplayMode(sourceId: String): com.comics8.core.model.ProgressDisplayMode {
        val raw = rawPref(SourcePrefs.progressDisplayModeKey(sourceId))
        return com.comics8.core.model.ProgressDisplayMode.fromName(raw, sourceId)
    }

    override fun setProgressDisplayMode(sourceId: String, mode: com.comics8.core.model.ProgressDisplayMode) {
        prefs.edit().putString(SourcePrefs.progressDisplayModeKey(sourceId), mode.name).apply()
    }

    private fun rawPref(key: String): String? =
        SourcePrefs.storedActiveRaw(prefs.contains(key), prefs.getString(key, null))
}
