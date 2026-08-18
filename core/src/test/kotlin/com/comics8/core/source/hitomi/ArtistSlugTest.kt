package com.comics8.core.source.hitomi

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArtistSlugTest {
    @Test
    fun normalizeLowercasesAndUnderscores() {
        assertThat(ArtistSlug.normalize("Demo Artist")).isEqualTo("demo_artist")
    }

    @Test
    fun fromArtistUrlStripsLangSuffix() {
        assertThat(ArtistSlug.fromArtistUrl("https://hitomi.la/artist/first_person-all.html"))
            .isEqualTo("first_person")
        assertThat(ArtistSlug.fromArtistUrl("https://hitomi.la/artist/second_person-korean.html"))
            .isEqualTo("second_person")
    }

    @Test
    fun fromArtistUrlNormalizesEncodedSpaces() {
        assertThat(ArtistSlug.fromArtistUrl("https://hitomi.la/artist/Demo%20Artist-all.html"))
            .isEqualTo("demo_artist")
        assertThat(ArtistSlug.fromArtistUrl("https://hitomi.la/artist/Demo+Artist-all.html"))
            .isEqualTo("demo_artist")
    }
}
