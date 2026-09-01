package com.comics8.core.source

import com.comics8.core.model.EpisodeSortOrder
import com.comics8.core.model.ProgressDisplayMode
import org.json.JSONArray

object SourcePrefs {
    const val ACTIVE_SOURCE_KEY = "pref_active_source_id"
    const val INSTALLED_KEY = "sources.installed"
    const val INSTALL_MIGRATED_KEY = "sources.install_migrated"
    const val LIBRARY_ROOTS_KEY = "local.library_roots"
    const val EPISODE_SORT_ORDER_KEY = "pref_episode_sort_order"

    fun enabledKey(sourceId: String): String = "sources.$sourceId.enabled"

    fun languageKey(sourceId: String): String = "$sourceId.language"

    fun progressDisplayModeKey(sourceId: String): String = "$sourceId.progress_display_mode"

    fun notificationKey(sourceId: String): String = "sources.$sourceId.notification_enabled"

    /**
     * Disk value for a string pref. Null if and only if the key is absent —
     * never substitute a getter default such as `"eleven"`.
     */
    fun storedActiveRaw(containsKey: Boolean, storedValue: String?): String? =
        if (!containsKey) null else storedValue

    fun parseIdList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i).trim()
                    if (id.isNotEmpty()) add(id)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun formatIdList(ids: Collection<String>): String {
        val arr = JSONArray()
        val seen = linkedSetOf<String>()
        for (id in ids) {
            val key = id.trim()
            if (key.isEmpty() || !seen.add(key)) continue
            arr.put(key)
        }
        return arr.toString()
    }

    /** Blank or not in [installed] → null. */
    fun resolveActiveId(stored: String?, installed: Set<String>): String? {
        val key = stored?.trim().orEmpty()
        if (key.isEmpty() || key !in installed) return null
        return key
    }

    fun withLocal(ids: Collection<String>): Set<String> {
        val next = linkedSetOf(WorkId.LOCAL_SOURCE)
        for (id in ids) {
            val key = id.trim()
            if (key.isNotEmpty()) next += key
        }
        return next
    }

    data class Migration(
        val installed: Set<String>,
        val activeId: String?,
        val wrote: Boolean,
    )

    /**
     * Local is always installed. Resolves active source against installed list.
     */
    fun migrateInstalled(
        storedInstalled: String?,
        storedActive: String?,
    ): Migration {
        val parsed = parseIdList(storedInstalled)
        val installed = withLocal(parsed)
        val wrote = storedInstalled == null || WorkId.LOCAL_SOURCE !in parsed.toSet()
        val active = resolveActiveId(storedActive, installed) ?: WorkId.LOCAL_SOURCE
        return Migration(installed, active, wrote)
    }
}

interface SourceSettings {
    fun installedIds(): Set<String>
    fun setInstalledIds(ids: Set<String>)
    fun storedActiveRaw(): String?
    fun activeSourceId(): String?
    fun setActiveSourceId(id: String?)
    fun language(sourceId: String): String?
    fun setLanguage(sourceId: String, value: String)
    fun progressDisplayMode(sourceId: String): ProgressDisplayMode =
        ProgressDisplayMode.defaultFor(sourceId)
    fun setProgressDisplayMode(sourceId: String, mode: ProgressDisplayMode) {}
    fun isEnabled(sourceId: String): Boolean = sourceId.isNotBlank() && sourceId in installedIds()
    fun setEnabled(sourceId: String, enabled: Boolean) {
        val id = sourceId.trim()
        if (id.isEmpty()) return
        if (!enabled && id == WorkId.LOCAL_SOURCE) return
        val next = installedIds().toMutableSet()
        if (enabled) next += id else next -= id
        setInstalledIds(next)
        if (!enabled && activeSourceId() == id) setActiveSourceId(WorkId.LOCAL_SOURCE)
    }
    fun libraryRoots(): List<String> = emptyList()
    fun setLibraryRoots(paths: List<String>) {}
    fun isNotificationEnabled(sourceId: String): Boolean = true
    fun setNotificationEnabled(sourceId: String, enabled: Boolean) {}
    fun implementationOverride(sourceId: String): String? = null
    fun episodeSortOrder(): EpisodeSortOrder = EpisodeSortOrder.DEFAULT
    fun setEpisodeSortOrder(order: EpisodeSortOrder) {}
}

interface SourcePreferenceStore {
    fun contains(key: String): Boolean
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/** Platform-neutral implementation; platform classes only adapt their native preference API. */
class StoredSourceSettings(
    private val store: SourcePreferenceStore,
    private val languageFallback: (String) -> String? = { null },
) : SourceSettings {
    fun migrateIfNeeded() {
        val result = SourcePrefs.migrateInstalled(raw(SourcePrefs.INSTALLED_KEY), storedActiveRaw())
        if (result.wrote) {
            setInstalledIds(result.installed)
            store.putString(SourcePrefs.INSTALL_MIGRATED_KEY, "1")
        }
        if (result.activeId == null) store.remove(SourcePrefs.ACTIVE_SOURCE_KEY)
        else if (storedActiveRaw() != result.activeId) setActiveSourceId(result.activeId)
    }

    override fun installedIds(): Set<String> =
        SourcePrefs.withLocal(SourcePrefs.parseIdList(raw(SourcePrefs.INSTALLED_KEY)))

    override fun setInstalledIds(ids: Set<String>) = store.putString(
        SourcePrefs.INSTALLED_KEY,
        SourcePrefs.formatIdList(SourcePrefs.withLocal(ids)),
    )

    override fun storedActiveRaw(): String? = raw(SourcePrefs.ACTIVE_SOURCE_KEY)

    override fun activeSourceId(): String? =
        SourcePrefs.resolveActiveId(storedActiveRaw(), installedIds()) ?: WorkId.LOCAL_SOURCE

    override fun setActiveSourceId(id: String?) {
        val value = id?.trim().orEmpty()
        if (value.isEmpty()) store.remove(SourcePrefs.ACTIVE_SOURCE_KEY)
        else store.putString(SourcePrefs.ACTIVE_SOURCE_KEY, value)
    }

    override fun language(sourceId: String): String? =
        raw(SourcePrefs.languageKey(sourceId))?.ifBlank { null } ?: languageFallback(sourceId)

    override fun setLanguage(sourceId: String, value: String) {
        store.putString(SourcePrefs.languageKey(sourceId), value.ifBlank { languageFallback(sourceId).orEmpty() })
    }

    override fun libraryRoots(): List<String> =
        SourcePrefs.parseIdList(raw(SourcePrefs.LIBRARY_ROOTS_KEY))

    override fun setLibraryRoots(paths: List<String>) =
        store.putString(SourcePrefs.LIBRARY_ROOTS_KEY, SourcePrefs.formatIdList(paths))

    override fun progressDisplayMode(sourceId: String): ProgressDisplayMode =
        ProgressDisplayMode.fromName(raw(SourcePrefs.progressDisplayModeKey(sourceId)), sourceId)

    override fun setProgressDisplayMode(sourceId: String, mode: ProgressDisplayMode) =
        store.putString(SourcePrefs.progressDisplayModeKey(sourceId), mode.name)

    override fun isNotificationEnabled(sourceId: String): Boolean {
        val key = SourcePrefs.notificationKey(sourceId)
        return if (store.contains(key)) store.getBoolean(key, true) else true
    }

    override fun setNotificationEnabled(sourceId: String, enabled: Boolean) =
        store.putBoolean(SourcePrefs.notificationKey(sourceId), enabled)

    override fun episodeSortOrder(): EpisodeSortOrder =
        EpisodeSortOrder.fromKey(raw(SourcePrefs.EPISODE_SORT_ORDER_KEY))

    override fun setEpisodeSortOrder(order: EpisodeSortOrder) =
        store.putString(SourcePrefs.EPISODE_SORT_ORDER_KEY, order.key)

    private fun raw(key: String): String? =
        SourcePrefs.storedActiveRaw(store.contains(key), store.getString(key))
}
