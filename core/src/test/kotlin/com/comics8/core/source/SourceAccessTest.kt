package com.comics8.core.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceAccessTest {
    private val installed = setOf("eleven", "hitomi")

    @Test
    fun defaultSourceIsAlwaysEnabled() {
        assertThat(SourceAccess.isEnabled("eleven") { false }).isTrue()
        assertThat(SourceAccess.isEnabled("") { false }).isTrue()
        assertThat(SourceAccess.writable("eleven", "1", { false }, installed)).isEqualTo(WorkId.eleven("1"))
        assertThat(SourceAccess.writable("", "1", { false }, installed)).isNull()
    }

    @Test
    fun registeredForeignSourceFollowsFlag() {
        assertThat(SourceAccess.writable("hitomi", "artist:x", { false }, installed)).isNull()
        assertThat(SourceAccess.writable("hitomi", "artist:x", { it == "hitomi" }, installed))
            .isEqualTo(WorkId("hitomi", "artist:x"))
        assertThat(SourceAccess.writable("other", "1", { true }, installed)).isNull()
    }
}
