package com.comics8.core.source.hitomi

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GalleryInfoParserTest {
    @Test
    fun parseOneArtistAndDate() {
        val info = GalleryInfoParser.parse(readFixture("hitomi/gallery_one_artist.js"))!!
        assertThat(info.id).isEqualTo("1001")
        assertThat(info.title).isEqualTo("Single Artist Gallery")
        assertThat(info.type).isEqualTo("doujinshi")
        assertThat(info.updatedAt).isEqualTo("24.08.15")
        assertThat(info.artists).hasSize(1)
        assertThat(info.artists[0].slug).isEqualTo("demo_artist")
        assertThat(info.artists[0].displayName).isEqualTo("Demo Artist")
        assertThat(info.files).hasSize(2)
    }

    @Test
    fun parseTwoArtistsKeepsBoth() {
        val info = GalleryInfoParser.parse(readFixture("hitomi/gallery_two_artists.js"))!!
        assertThat(info.id).isEqualTo("2002")
        assertThat(info.artists.map { it.slug }).containsExactly("first_person", "second_person").inOrder()
        assertThat(info.updatedAt).isEqualTo("25.01.02")
    }

    @Test
    fun parseNoArtist() {
        val info = GalleryInfoParser.parse(readFixture("hitomi/gallery_no_artist.js"))!!
        assertThat(info.artists).isEmpty()
        assertThat(info.id).isEqualTo("3003")
    }

    @Test
    fun formatDateYyMmDd() {
        assertThat(GalleryInfoParser.formatDate("2024-08-15 12:34:56")).isEqualTo("24.08.15")
        assertThat(GalleryInfoParser.formatDate(null)).isNull()
    }

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().readText()
}
