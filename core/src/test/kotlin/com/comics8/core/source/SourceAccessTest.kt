package com.comics8.core.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceAccessTest {
    @Test
    fun defaultSourceIsAlwaysEnabled() {
        assertThat(SourceAccess.isEnabled("eleven") { false }).isTrue()
        assertThat(SourceAccess.isEnabled("") { false }).isTrue()
        assertThat(SourceAccess.writable("eleven", "1") { false }).isEqualTo(WorkId.eleven("1"))
    }

    @Test
    fun registeredForeignSourceFollowsFlag() {
        assertThat(SourceAccess.writable("hitomi", "artist:x") { false }).isNull()
        assertThat(SourceAccess.writable("hitomi", "artist:x") { it == "hitomi" })
            .isEqualTo(WorkId("hitomi", "artist:x"))
        assertThat(SourceAccess.writable("other", "1") { true }).isNull()
    }
}
