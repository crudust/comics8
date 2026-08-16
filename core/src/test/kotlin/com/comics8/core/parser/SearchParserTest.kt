package com.comics8.core.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchParserTest {
    @Test
    fun parseSuccess() {
        val items = SearchParser.parse(readFixture("search_success.json"))
        assertThat(items).hasSize(2)
        assertThat(items[0].id).isEqualTo("1")
        assertThat(items[0].title).isEqualTo("열혈강호")
        assertThat(items[0].genre).isEqualTo("무협")
        assertThat(items[0].updatedAt).isEqualTo("08.15")
        assertThat(items[0].thumbUrl).isEqualTo(ComicSite.coverUrl("1"))
        assertThat(items[1].title).isEqualTo("게이트 제로")
        assertThat(items[1].updatedAt).isEqualTo("08.16")
    }

    @Test
    fun parseFailIsEmpty() {
        assertThat(SearchParser.parse(readFixture("search_fail.json"))).isEmpty()
    }

    @Test
    fun formatDate() {
        assertThat(SearchParser.formatDate("2026-08-15 12:40:44")).isEqualTo("08.15")
        assertThat(SearchParser.formatDate("")).isNull()
    }

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().readText()
}
