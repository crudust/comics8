package com.comics8.core.source.hitomi

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HitomiSearchTest {
    @Test
    fun parseAcceptsPrefixedTokens() {
        val tokens = HitomiSearch.parse("artist:demo_artist female:sole_female type:doujinshi")
        assertThat(tokens).isNotNull()
        assertThat(tokens!!.map { it.ns }).containsExactly("artist", "female", "type").inOrder()
        assertThat(HitomiSearch.nozomiUrl(tokens[0], "korean"))
            .isEqualTo("https://ltn.gold-usergeneratedcontent.net/artist/demo_artist-korean.nozomi")
        assertThat(HitomiSearch.nozomiUrl(tokens[1], "korean"))
            .isEqualTo("https://ltn.gold-usergeneratedcontent.net/female/sole_female-korean.nozomi")
        assertThat(HitomiSearch.nozomiUrl(tokens[2], "korean"))
            .isEqualTo("https://ltn.gold-usergeneratedcontent.net/doujinshi-korean.nozomi")
    }

    @Test
    fun languageTokenMapsToIndex() {
        val tokens = HitomiSearch.parse("language:english")!!
        assertThat(HitomiSearch.nozomiUrl(tokens[0], "korean"))
            .isEqualTo("https://ltn.gold-usergeneratedcontent.net/index-english.nozomi")
    }

    @Test
    fun rejectBareWordsAndExcludes() {
        assertThat(HitomiSearch.parse("big breasts")).isNull()
        assertThat(HitomiSearch.parse("artist:foo -female:bar")).isNull()
        assertThat(HitomiSearch.parse("-tag:x")).isNull()
    }
}
