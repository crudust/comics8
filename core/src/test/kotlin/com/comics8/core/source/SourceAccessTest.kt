package com.comics8.core.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceAccessTest {
    private val installed = setOf("eleven", "hitomi")

    @Test
    fun defaultSourceIsNotAlwaysEnabled() {
        assertThat(SourceAccess.isEnabled("eleven") { false }).isFalse()
        assertThat(SourceAccess.isEnabled("") { false }).isFalse()
        assertThat(SourceAccess.isEnabled("eleven") { it == "eleven" }).isTrue()
        assertThat(SourceAccess.writable("eleven", "1", { false }, installed)).isNull()
        assertThat(SourceAccess.writable("eleven", "1", { it == "eleven" }, installed))
            .isEqualTo(WorkId.eleven("1"))
        assertThat(SourceAccess.writable("", "1", { true }, installed)).isNull()
    }

    @Test
    fun registeredForeignSourceFollowsFlag() {
        assertThat(SourceAccess.writable("hitomi", "artist:x", { false }, installed)).isNull()
        assertThat(SourceAccess.writable("hitomi", "artist:x", { it == "hitomi" }, installed))
            .isEqualTo(WorkId("hitomi", "artist:x"))
        assertThat(SourceAccess.writable("other", "1", { true }, installed)).isNull()
    }

    @Test
    fun uninstalledElevenIsNotWritable() {
        assertThat(SourceAccess.writable("eleven", "1", { true }, emptySet())).isNull()
    }
}
