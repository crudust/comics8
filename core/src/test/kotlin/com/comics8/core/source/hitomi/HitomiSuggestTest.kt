package com.comics8.core.source.hitomi

import com.comics8.core.source.SearchSuggestion
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HitomiSuggestTest {
    @Test
    fun urlUsesArtistIndexForBareWords() {
        assertThat(HitomiSuggest.url("asa"))
            .isEqualTo("https://tagindex.hitomi.la/artist/a/s/a.json")
        assertThat(HitomiSuggest.url("series:tou"))
            .isEqualTo("https://tagindex.hitomi.la/series/t/o/u.json")
        assertThat(HitomiSuggest.url("")).isNull()
    }

    @Test
    fun parseOfficialJsonRows() {
        val items = HitomiSuggest.parse(
            """[["cle masahiro",1023,"artist"],["asanagi",868,"artist"]]""",
        )
        assertThat(items.map { it.queryToken }).containsExactly("artist:cle_masahiro", "artist:asanagi").inOrder()
        assertThat(items[1].count).isEqualTo(868)
    }

    @Test
    fun applyReplacesLastTerm() {
        val pick = SearchSuggestion("artist", "asanagi", 868)
        assertThat(HitomiSuggest.apply("asa", pick)).isEqualTo("artist:asanagi ")
        assertThat(HitomiSuggest.apply("female:sole_female as", pick)).isEqualTo("female:sole_female artist:asanagi ")
    }
}
