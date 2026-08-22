package com.comics8.desktop.data

import com.comics8.core.source.SourcePrefs
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.SourceSettings
import java.util.prefs.Preferences

object DesktopSourcePrefs : SourceSettings {
    private val prefs: Preferences = Preferences.userRoot().node("com.comics8.desktop")

    @Volatile
    lateinit var registry: SourceRegistry

    fun migrateIfNeeded() {
        val storedInstalled = rawPref(SourcePrefs.INSTALLED_KEY)
        val result = SourcePrefs.migrateInstalled(
            storedInstalled = storedInstalled,
            storedActive = storedActiveRaw(),
            hitomiEnabledPref = hitomiEnabledRaw(),
            hasLegacyUserData = false,
        )
        if (result.wrote) {
            setInstalledIds(result.installed)
            prefs.put(SourcePrefs.INSTALL_MIGRATED_KEY, "1")
        }
        if (result.activeId == null) {
            prefs.remove(SourcePrefs.ACTIVE_SOURCE_KEY)
        } else if (storedActiveRaw() != result.activeId) {
            setActiveSourceId(result.activeId)
        }
    }

    override fun installedIds(): Set<String> =
        SourcePrefs.parseIdList(rawPref(SourcePrefs.INSTALLED_KEY)).toSet()

    override fun setInstalledIds(ids: Set<String>) {
        prefs.put(SourcePrefs.INSTALLED_KEY, SourcePrefs.formatIdList(ids))
    }

    override fun storedActiveRaw(): String? = rawPref(SourcePrefs.ACTIVE_SOURCE_KEY)

    override fun activeSourceId(): String? =
        SourcePrefs.resolveActiveId(storedActiveRaw(), installedIds())

    override fun setActiveSourceId(id: String?) {
        val key = id?.trim().orEmpty()
        if (key.isEmpty()) {
            prefs.remove(SourcePrefs.ACTIVE_SOURCE_KEY)
        } else {
            prefs.put(SourcePrefs.ACTIVE_SOURCE_KEY, key)
        }
    }

    override fun isEnabled(sourceId: String): Boolean {
        if (sourceId.isBlank()) return false
        return sourceId in installedIds()
    }

    override fun language(sourceId: String): String? {
        val fallback = registry.getOrNull(sourceId)?.defaultLanguage
        val stored = rawPref(SourcePrefs.languageKey(sourceId))
        return stored?.ifBlank { null } ?: fallback
    }

    override fun setLanguage(sourceId: String, value: String) {
        val fallback = registry.getOrNull(sourceId)?.defaultLanguage.orEmpty()
        prefs.put(SourcePrefs.languageKey(sourceId), value.ifBlank { fallback })
    }

    override fun libraryRoots(): List<String> =
        SourcePrefs.parseIdList(rawPref(SourcePrefs.LIBRARY_ROOTS_KEY))

    override fun setLibraryRoots(paths: List<String>) {
        prefs.put(SourcePrefs.LIBRARY_ROOTS_KEY, SourcePrefs.formatIdList(paths))
    }

    private fun hitomiEnabledRaw(): Boolean? {
        val key = SourcePrefs.enabledKey("hitomi")
        return if (hasKey(key)) prefs.getBoolean(key, false) else null
    }

    private fun rawPref(key: String): String? {
        val present = hasKey(key)
        val value = if (present) prefs.get(key, "") else null
        return SourcePrefs.storedActiveRaw(present, value)
    }

    private fun hasKey(key: String): Boolean = try {
        key in prefs.keys()
    } catch (_: Exception) {
        false
    }
}
