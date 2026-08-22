package com.comics8.monitor.data

import android.content.SharedPreferences
import com.comics8.core.source.SourcePrefs
import com.comics8.core.source.SourceSettings

class AndroidSourcePrefs(
    private val prefs: SharedPreferences,
) : SourceSettings {
    fun migrateIfNeeded() {
        val storedInstalled = rawPref(SourcePrefs.INSTALLED_KEY)
        val result = SourcePrefs.migrateInstalled(
            storedInstalled = storedInstalled,
            storedActive = storedActiveRaw(),
            hitomiEnabledPref = hitomiEnabledRaw(),
            hasLegacyUserData = false,
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
        SourcePrefs.parseIdList(rawPref(SourcePrefs.INSTALLED_KEY)).toSet()

    override fun setInstalledIds(ids: Set<String>) {
        prefs.edit().putString(SourcePrefs.INSTALLED_KEY, SourcePrefs.formatIdList(ids)).apply()
    }

    override fun storedActiveRaw(): String? = rawPref(SourcePrefs.ACTIVE_SOURCE_KEY)

    override fun activeSourceId(): String? =
        SourcePrefs.resolveActiveId(storedActiveRaw(), installedIds())

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

    private fun hitomiEnabledRaw(): Boolean? {
        val key = SourcePrefs.enabledKey("hitomi")
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    private fun rawPref(key: String): String? =
        SourcePrefs.storedActiveRaw(prefs.contains(key), prefs.getString(key, null))
}
