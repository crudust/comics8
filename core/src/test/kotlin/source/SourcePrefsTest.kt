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
}
