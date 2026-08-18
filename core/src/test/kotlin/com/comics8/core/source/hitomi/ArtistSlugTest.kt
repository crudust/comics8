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
}
