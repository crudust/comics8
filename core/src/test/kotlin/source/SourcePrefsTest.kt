package com.comics8.core.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourcePrefsTest {
    @Test
    fun storedActiveRawIsNullIffKeyAbsent() {
        assertThat(SourcePrefs.storedActiveRaw(containsKey = false, storedValue = null)).isNull()
        assertThat(SourcePrefs.storedActiveRaw(containsKey = false, storedValue = "eleven")).isNull()
        assertThat(SourcePrefs.storedActiveRaw(containsKey = false, storedValue = "")).isNull()
        assertThat(SourcePrefs.storedActiveRaw(containsKey = true, storedValue = "eleven"))
            .isEqualTo("eleven")
        assertThat(SourcePrefs.storedActiveRaw(containsKey = true, storedValue = "")).isEqualTo("")
        assertThat(SourcePrefs.storedActiveRaw(containsKey = true, storedValue = null)).isNull()
    }

    @Test
    fun migrateFreshInstallUsesLocal() {
        val result = SourcePrefs.migrateInstalled(
            storedInstalled = null,
            storedActive = null,
        )
        assertThat(result.installed).containsExactly(WorkId.LOCAL_SOURCE)
        assertThat(result.activeId).isEqualTo(WorkId.LOCAL_SOURCE)
        assertThat(result.wrote).isTrue()
    }

    @Test
    fun migrateKeepsExistingInstalledSet() {
        val result = SourcePrefs.migrateInstalled(
            storedInstalled = """["local","custom"]""",
            storedActive = "custom",
        )
        assertThat(result.installed).containsExactly("local", "custom")
        assertThat(result.activeId).isEqualTo("custom")
        assertThat(result.wrote).isFalse()
    }

    @Test
    fun migrateExistingInstalledFallsBackToLocalIfActiveIsNotInstalled() {
        val result = SourcePrefs.migrateInstalled(
            storedInstalled = "[]",
            storedActive = "unknown",
        )
        assertThat(result.installed).containsExactly(WorkId.LOCAL_SOURCE)
        assertThat(result.activeId).isEqualTo(WorkId.LOCAL_SOURCE)
        assertThat(result.wrote).isTrue()
    }

    @Test
    fun namedSourcePrefKeys() {
        assertThat(SourcePrefs.ACTIVE_SOURCE_KEY).isEqualTo("pref_active_source_id")
        assertThat(SourcePrefs.enabledKey("hitomi")).isEqualTo("sources.hitomi.enabled")
        assertThat(SourcePrefs.languageKey("hitomi")).isEqualTo("hitomi.language")
    }

    @Test
    fun parseAndFormatIdListRoundTrip() {
        assertThat(SourcePrefs.parseIdList(null)).isEmpty()
        assertThat(SourcePrefs.parseIdList("")).isEmpty()
        assertThat(SourcePrefs.parseIdList("not-json")).isEmpty()
        assertThat(SourcePrefs.parseIdList("""["eleven"," hitomi ",""]"""))
            .containsExactly("eleven", "hitomi")
            .inOrder()
        assertThat(SourcePrefs.formatIdList(listOf("local", " local ", "", "js")))
            .isEqualTo("""["local","js"]""")
    }

    @Test
    fun libraryRootsJsonRoundTrip() {
        val paths = listOf("/Users/me/Comics", "/tmp/lib with space")
        val encoded = SourcePrefs.formatIdList(paths)
        assertThat(SourcePrefs.parseIdList(encoded)).containsExactlyElementsIn(paths).inOrder()
        assertThat(SourcePrefs.LIBRARY_ROOTS_KEY).isEqualTo("local.library_roots")
    }

    @Test
    fun episodeSortOrderRoundTrip() {
        val store = object : SourcePreferenceStore {
            val map = mutableMapOf<String, Any>()
            override fun contains(key: String) = key in map
            override fun getString(key: String) = map[key] as? String
            override fun putString(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
            override fun getBoolean(key: String, default: Boolean) = (map[key] as? Boolean) ?: default
            override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        }
        val settings = StoredSourceSettings(store)
        assertThat(settings.episodeSortOrder()).isEqualTo(com.comics8.core.model.EpisodeSortOrder.NAME_ASC)
        settings.setEpisodeSortOrder(com.comics8.core.model.EpisodeSortOrder.DATE_DESC)
        assertThat(settings.episodeSortOrder()).isEqualTo(com.comics8.core.model.EpisodeSortOrder.DATE_DESC)
        assertThat(store.getString(SourcePrefs.EPISODE_SORT_ORDER_KEY)).isEqualTo("date_desc")
    }
}
