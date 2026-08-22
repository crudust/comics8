package com.comics8.core.source

import com.comics8.core.source.hitomi.HitomiSource
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceRegistryTest {
    @Test
    fun forTestsConstructsHitomiAndEleven() {
        val registry = SourceRegistry.forTests()
        assertThat(registry.get(WorkId.DEFAULT_SOURCE).id).isEqualTo(WorkId.DEFAULT_SOURCE)
        assertThat(registry.get(HitomiSource.ID)).isInstanceOf(HitomiSource::class.java)
        assertThat(HitomiSource.ENABLED_BY_DEFAULT).isFalse()
        assertThat(HitomiSource.ENABLED_PREF_KEY).isEqualTo("sources.hitomi.enabled")
        assertThat(HitomiSource.LANGUAGE_PREF_KEY).isEqualTo("hitomi.language")
        assertThat(HitomiSource.ACTIVE_SOURCE_PREF_KEY).isEqualTo("pref_active_source_id")
        val installed = registry.knownIds()
        assertThat(WorkId.writable(HitomiSource.ID, "artist:demo", sourceEnabled = false, installedIds = installed)).isNull()
        assertThat(WorkId.writable(HitomiSource.ID, "artist:demo", sourceEnabled = true, installedIds = installed))
            .isEqualTo(WorkId(HitomiSource.ID, "artist:demo"))
        assertThat(registry.chipLabel(HitomiSource.ID)).isEqualTo(registry.get(HitomiSource.ID).displayName)
        assertThat(registry.chipLabel("")).isEqualTo("")
        assertThat(HitomiSource.ACTIVE_SOURCE_PREF_KEY).isEqualTo(SourcePrefs.ACTIVE_SOURCE_KEY)
        assertThat(SourcePrefs.enabledKey(HitomiSource.ID)).isEqualTo(HitomiSource.ENABLED_PREF_KEY)
        assertThat(SourcePrefs.languageKey(HitomiSource.ID)).isEqualTo(HitomiSource.LANGUAGE_PREF_KEY)
        assertThat(registry.searchPlaceholder(HitomiSource.ID))
            .isEqualTo(registry.get(HitomiSource.ID).searchPlaceholder)
        assertThat(registry.searchPlaceholder(WorkId.DEFAULT_SOURCE))
            .isEqualTo(registry.get(WorkId.DEFAULT_SOURCE).searchPlaceholder)
        assertThat(registry.resolveActive("hitomi")?.id).isEqualTo(HitomiSource.ID)
        assertThat(registry.resolveActive("unknown")).isNull()
        assertThat(registry.sourceForUrl("https://tn.gold-usergeneratedcontent.net/x.webp")?.id)
            .isEqualTo(HitomiSource.ID)
        assertThat(registry.sourceForUrl("https://www.pl3040.com/kr/1.png")?.id)
            .isEqualTo(WorkId.DEFAULT_SOURCE)
        assertThat(registry.sourceForUrl("https://example.invalid/x.png")).isNull()
    }

    @Test
    fun blankSourceIdDoesNotFallBackToEleven() {
        val registry = SourceRegistry.forTests()
        assertThat(registry.getOrNull("")).isNull()
        assertThat(registry.getOrNull("   ")).isNull()
        assertThrows(IllegalStateException::class.java) {
            registry.get("")
        }
        assertThrows(IllegalStateException::class.java) {
            registry.get("   ")
        }
    }

    @Test
    fun emptyRegistryIsAllowed() {
        val registry = SourceRegistry()
        assertThat(registry.all()).isEmpty()
        assertThat(registry.knownIds()).isEmpty()
        assertThat(registry.resolveActive("eleven")).isNull()
    }

    @Test
    fun addRemoveReplaceAllAreCopyOnWrite() {
        val registry = SourceRegistry()
        val eleven = SourceRegistry.forTests().get(WorkId.DEFAULT_SOURCE)
        assertThat(registry.add(eleven)).isTrue()
        assertThat(registry.add(eleven)).isFalse()
        assertThat(registry.knownIds()).contains(WorkId.DEFAULT_SOURCE)
        assertThat(registry.remove(WorkId.DEFAULT_SOURCE)?.id).isEqualTo(WorkId.DEFAULT_SOURCE)
        assertThat(registry.remove(WorkId.DEFAULT_SOURCE)).isNull()
        registry.replaceAll(listOf(eleven))
        assertThat(registry.get(WorkId.DEFAULT_SOURCE).id).isEqualTo(WorkId.DEFAULT_SOURCE)
    }

    @Test
    fun ownsHostMatchesServerProxyAllowlist() {
        val registry = SourceRegistry.forTests()
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
        val registry = SourceRegistry.forTests()
        val hitomi = registry.get(HitomiSource.ID) as HitomiSource
        registry.applyPreferences { null }
        assertThat(hitomi.language).isEqualTo(HitomiSource.DEFAULT_LANGUAGE)
        registry.applyPreferences { id -> if (id == HitomiSource.ID) "english" else null }
        assertThat(hitomi.language).isEqualTo("english")
    }

    @Test
    fun hostApiLevelIsOne() {
        assertThat(HostApi.LEVEL).isEqualTo(1)
        assertThat(SourceRegistry.forTests().get(WorkId.DEFAULT_SOURCE).hostApiLevel).isEqualTo(1)
    }
}
