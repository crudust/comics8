package com.comics8.core.source

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceRegistryTest {
    @Test
    fun forTestsDefaultsToEmpty() {
        val registry = SourceRegistry.forTests()
        assertThat(registry.all()).isEmpty()
        assertThat(registry.knownIds()).isEmpty()
        assertThat(registry.getOrNull(WorkId.DEFAULT_SOURCE)).isNull()
        assertThat(registry.resolveActive("eleven")).isNull()
        assertThat(registry.sourceForUrl("https://www.pl3040.com/kr/1.png")).isNull()
    }

    @Test
    fun blankSourceIdDoesNotFallBackToEleven() {
        val registry = SourceRegistry.forTests(listOf(StubComicSource(id = WorkId.DEFAULT_SOURCE)))
        assertThat(registry.getOrNull("")).isNull()
        assertThat(registry.getOrNull("   ")).isNull()
        assertThrows(IllegalStateException::class.java) {
            registry.get("")
        }
        assertThrows(IllegalStateException::class.java) {
            registry.get("   ")
        }
        assertThat(registry.chipLabel("")).isEqualTo("")
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
        val eleven = StubComicSource(id = WorkId.DEFAULT_SOURCE, displayName = "11toon")
        assertThat(registry.add(eleven)).isTrue()
        assertThat(registry.add(eleven)).isFalse()
        assertThat(registry.knownIds()).contains(WorkId.DEFAULT_SOURCE)
        assertThat(registry.remove(WorkId.DEFAULT_SOURCE)?.id).isEqualTo(WorkId.DEFAULT_SOURCE)
        assertThat(registry.remove(WorkId.DEFAULT_SOURCE)).isNull()
        registry.replaceAll(listOf(eleven))
        assertThat(registry.get(WorkId.DEFAULT_SOURCE).id).isEqualTo(WorkId.DEFAULT_SOURCE)
    }

    @Test
    fun lookupDelegatesToLoadedSources() {
        val eleven = StubComicSource(
            id = WorkId.DEFAULT_SOURCE,
            displayName = "11toon",
            searchPlaceholder = "제목 검색",
            ownedHost = hostSuffixes("pl3040.com", "11toon8.com"),
        )
        val hitomi = StubComicSource(
            id = "hitomi",
            displayName = "Hitomi",
            searchPlaceholder = "작가 검색 (artist: 이름)",
            ownedHost = hostSuffixes("hitomi.la", "gold-usergeneratedcontent.net"),
            proxy = false,
        )
        val registry = SourceRegistry(listOf(eleven, hitomi))
        val installed = registry.knownIds()
        assertThat(WorkId.writable("hitomi", "artist:demo", sourceEnabled = false, installedIds = installed)).isNull()
        assertThat(WorkId.writable("hitomi", "artist:demo", sourceEnabled = true, installedIds = installed))
            .isEqualTo(WorkId("hitomi", "artist:demo"))
        assertThat(registry.chipLabel("hitomi")).isEqualTo("Hitomi")
        assertThat(registry.searchPlaceholder("hitomi")).isEqualTo("작가 검색 (artist: 이름)")
        assertThat(registry.searchPlaceholder(WorkId.DEFAULT_SOURCE)).isEqualTo("제목 검색")
        assertThat(registry.resolveActive("hitomi")?.id).isEqualTo("hitomi")
        assertThat(registry.resolveActive("unknown")).isNull()
        assertThat(registry.sourceForUrl("https://tn.gold-usergeneratedcontent.net/x.webp")?.id)
            .isEqualTo("hitomi")
        assertThat(registry.sourceForUrl("https://www.pl3040.com/kr/1.png")?.id)
            .isEqualTo(WorkId.DEFAULT_SOURCE)
        assertThat(registry.sourceForUrl("https://example.invalid/x.png")).isNull()
        assertThat(registry.ownsHost("aa.gold-usergeneratedcontent.net")).isTrue()
        assertThat(registry.ownsHost("notevilgold-usergeneratedcontent.net")).isFalse()
        assertThat(registry.ownsHost("hitomi.la")).isTrue()
        assertThat(registry.ownsHost("tn.hitomi.la")).isTrue()
        assertThat(registry.ownsHost("11toon8.com")).isTrue()
        assertThat(registry.ownsHost("evil.example.com")).isFalse()
    }

    @Test
    fun applyPreferencesUsesSourceDefaultLanguage() {
        val hitomi = StubComicSource(id = "hitomi", defaultLanguage = "korean")
        val registry = SourceRegistry(listOf(hitomi))
        registry.applyPreferences { null }
        assertThat(hitomi.language).isEqualTo("korean")
        registry.applyPreferences { id -> if (id == "hitomi") "english" else null }
        assertThat(hitomi.language).isEqualTo("english")
    }

    @Test
    fun hostApiLevelIsOne() {
        assertThat(HostApi.LEVEL).isEqualTo(1)
        val source = StubComicSource(id = WorkId.DEFAULT_SOURCE)
        assertThat(source.hostApiLevel).isEqualTo(1)
        assertThat(SourceRegistry(listOf(source)).get(WorkId.DEFAULT_SOURCE).hostApiLevel).isEqualTo(1)
    }
}
