package com.comics8.core.source

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkIdTest {
    private val installed = setOf("eleven", "hitomi", "local")

    @Test
    fun parseUnprefixedStillElevenForStorage() {
        val id = WorkId.parse("7883")
        assertThat(id.sourceId).isEqualTo(WorkId.DEFAULT_SOURCE)
        assertThat(id.toonId).isEqualTo("7883")
        assertThat(id.storageKey()).isEqualTo("eleven:7883")
    }

    @Test
    fun parseBlankSourceUsesEleven() {
        val id = WorkId.parse(":1827530")
        assertThat(id.sourceId).isEqualTo("eleven")
        assertThat(id.toonId).isEqualTo("1827530")
    }

    @Test
    fun parseUsesFirstColonOnly() {
        val id = WorkId.parse("hitomi:123:extra")
        assertThat(id.sourceId).isEqualTo("hitomi")
        assertThat(id.toonId).isEqualTo("123:extra")
        assertThat(id.storageKey()).isEqualTo("hitomi:123:extra")
    }

    @Test
    fun parsePrefixedEleven() {
        val id = WorkId.parse("eleven:7883")
        assertThat(id.sourceId).isEqualTo("eleven")
        assertThat(id.toonId).isEqualTo("7883")
    }

    @Test
    fun rejectsColonInSourceId() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkId("ele:ven", "1")
        }
    }

    @Test
    fun rejectsBlankSourceId() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkId("", "1")
        }
    }

    @Test
    fun writableRejectsBlankAndDisabledAndUnknown() {
        assertThat(WorkId.writable("", "1", sourceEnabled = true, installedIds = installed)).isNull()
        assertThat(WorkId.writable("eleven", "1", sourceEnabled = false, installedIds = installed)).isNull()
        assertThat(WorkId.writable("eleven", "1", sourceEnabled = true, installedIds = setOf("eleven")))
            .isEqualTo(WorkId.eleven("1"))
        assertThat(WorkId.writable("hitomi", "1", sourceEnabled = false, installedIds = installed)).isNull()
        assertThat(WorkId.writable("other", "1", sourceEnabled = true, installedIds = installed)).isNull()
    }

    @Test
    fun writableAllowsInstalledSourceWhenEnabled() {
        assertThat(WorkId.writable("hitomi", "artist:demo", sourceEnabled = true, installedIds = installed))
            .isEqualTo(WorkId("hitomi", "artist:demo"))
        assertThat(WorkId.writable("hitomi", "", sourceEnabled = true, installedIds = installed)).isNull()
        assertThat(WorkId.writable("local", "/a.zip", sourceEnabled = true, installedIds = setOf("local")))
            .isEqualTo(WorkId.local("/a.zip"))
    }

    @Test
    fun storedAcceptsAnySource() {
        assertThat(WorkId.stored("hitomi", "1")).isEqualTo(WorkId("hitomi", "1"))
        assertThat(WorkId.stored("", "1")).isEqualTo(WorkId.eleven("1"))
        assertThat(WorkId.stored("ele:ven", "1")).isNull()
        assertThat(WorkId.stored("local", "/a.zip")).isEqualTo(WorkId.local("/a.zip"))
    }
}
