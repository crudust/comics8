package com.comics8.core.source

import com.comics8.core.source.hitomi.HitomiSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceRegistryTest {
    @Test
    fun defaultsConstructHitomiButKeepElevenDefault() {
        val registry = SourceRegistry.defaults()
        assertThat(registry.default.id).isEqualTo(WorkId.DEFAULT_SOURCE)
        assertThat(registry.get(HitomiSource.ID)).isInstanceOf(HitomiSource::class.java)
        assertThat(HitomiSource.ENABLED_BY_DEFAULT).isFalse()
        assertThat(HitomiSource.ENABLED_PREF_KEY).isEqualTo("sources.hitomi.enabled")
        assertThat(HitomiSource.LANGUAGE_PREF_KEY).isEqualTo("hitomi.language")
        assertThat(HitomiSource.ACTIVE_SOURCE_PREF_KEY).isEqualTo("pref_active_source_id")
        assertThat(WorkId.writable(HitomiSource.ID, "artist:demo")).isNull()
        assertThat(WorkId.writable(HitomiSource.ID, "artist:demo", hitomiEnabled = true))
            .isEqualTo(WorkId(HitomiSource.ID, "artist:demo"))
    }
}
