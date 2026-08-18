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
        assertThat(WorkId.writable(HitomiSource.ID, "artist:demo", sourceEnabled = true))
            .isEqualTo(WorkId(HitomiSource.ID, "artist:demo"))
        assertThat(SourceRegistry.chipLabel(HitomiSource.ID)).isEqualTo(registry.get(HitomiSource.ID).displayName)
        assertThat(SourceRegistry.chipLabel("")).isEqualTo(registry.default.displayName)
        assertThat(HitomiSource.ACTIVE_SOURCE_PREF_KEY).isEqualTo(SourcePrefs.ACTIVE_SOURCE_KEY)
        assertThat(SourcePrefs.enabledKey(HitomiSource.ID)).isEqualTo(HitomiSource.ENABLED_PREF_KEY)
        assertThat(SourcePrefs.languageKey(HitomiSource.ID)).isEqualTo(HitomiSource.LANGUAGE_PREF_KEY)
        assertThat(registry.resolveActive("hitomi").id).isEqualTo(HitomiSource.ID)
        assertThat(registry.resolveActive("unknown").id).isEqualTo(WorkId.DEFAULT_SOURCE)
        assertThat(registry.sourceForUrl("https://tn.gold-usergeneratedcontent.net/x.webp").id)
            .isEqualTo(HitomiSource.ID)
        assertThat(registry.sourceForUrl("https://www.pl3040.com/kr/1.png").id)
            .isEqualTo(WorkId.DEFAULT_SOURCE)
    }

    @Test
    fun ownsHostMatchesServerProxyAllowlist() {
        val registry = SourceRegistry.defaults()
        assertThat(registry.ownsHost("aa.gold-usergeneratedcontent.net")).isTrue()
        assertThat(registry.ownsHost("notevilgold-usergeneratedcontent.net")).isFalse()
        assertThat(registry.ownsHost("hitomi.la")).isTrue()
        assertThat(registry.ownsHost("tn.hitomi.la")).isTrue()
        assertThat(registry.ownsHost("www.pl4050.com")).isTrue()
        assertThat(registry.ownsHost("11toon8.com")).isTrue()
        assertThat(registry.ownsHost("103.204.13.68")).isTrue()
        assertThat(registry.ownsHost("evil.example.com")).isFalse()
    }

    @Test
    fun applyPreferencesUsesSourceDefaultLanguage() {
        val registry = SourceRegistry.defaults()
        val hitomi = registry.get(HitomiSource.ID) as HitomiSource
        registry.applyPreferences { null }
        assertThat(hitomi.language).isEqualTo(HitomiSource.DEFAULT_LANGUAGE)
        registry.applyPreferences { id -> if (id == HitomiSource.ID) "english" else null }
        assertThat(hitomi.language).isEqualTo("english")
    }
}
