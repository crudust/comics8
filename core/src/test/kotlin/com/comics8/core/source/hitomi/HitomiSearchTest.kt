package com.comics8.core.source.hitomi

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HitomiSearchTest {
    @Test
    fun parseAcceptsPrefixedTokens() {
        val tokens = HitomiSearch.parse("artist:demo_artist female:sole_female type:doujinshi")
        assertThat(tokens.map { it.ns }).containsExactly("artist", "female", "type").inOrder()
        assertThat(HitomiSearch.nozomiUrl(tokens[0], "korean"))
            .isEqualTo("https://ltn.gold-usergeneratedcontent.net/artist/demo%20artist-korean.nozomi")
        assertThat(HitomiSearch.nozomiUrl(tokens[1], "korean"))
            .isEqualTo("https://ltn.gold-usergeneratedcontent.net/female/sole%20female-korean.nozomi")
        assertThat(HitomiSearch.nozomiUrl(tokens[2], "korean"))
            .isEqualTo("https://ltn.gold-usergeneratedcontent.net/doujinshi-korean.nozomi")
    }

    @Test
    fun languageTokenMapsToIndex() {
        val tokens = HitomiSearch.parse("language:english")
        assertThat(HitomiSearch.nozomiUrl(tokens[0], "korean"))
            .isEqualTo("https://ltn.gold-usergeneratedcontent.net/index-english.nozomi")
    }

    @Test
    fun bareWordsDefaultToArtist() {
        val tokens = HitomiSearch.parse("asanagi")
        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].ns).isEqualTo("artist")
        assertThat(tokens[0].value).isEqualTo("asanagi")
        assertThat(HitomiSearch.parse("cle masahiro").single().value).isEqualTo("cle_masahiro")
        assertThat(HitomiSearch.parse("artist: asanagi").single().value).isEqualTo("asanagi")
        assertThat(HitomiSearch.nozomiUrl(HitomiSearch.parse("fujitaka kinsei").single(), "korean"))
            .isEqualTo("https://ltn.gold-usergeneratedcontent.net/artist/fujitaka%20kinsei-korean.nozomi")
    }

    @Test
    fun skipsExcludesAndKeepsOtherTokens() {
        val tokens = HitomiSearch.parse("artist:foo -female:bar")
        assertThat(tokens.map { it.ns }).containsExactly("artist")
        assertThat(HitomiSearch.parse("-tag:x")).isEmpty()
    }
}
