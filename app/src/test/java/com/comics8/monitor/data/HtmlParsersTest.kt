package com.comics8.monitor.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HtmlParsersTest {
    @Test
    fun parseLatestListing() {
        val html = readFixture("listing_latest.html")
        val page = HtmlParsers.parseListing(html, ComicSite.ORIGIN)

        assertThat(page.items).hasSize(2)
        assertThat(page.currentPage).isEqualTo(1)
        assertThat(page.lastPage).isEqualTo(479)

        val first = page.items[0]
        assertThat(first.id).isEqualTo("7883")
        assertThat(first.title).contains("TSUYOSHI")
        assertThat(first.updatedAt).isEqualTo("08.16")
        assertThat(first.genre).contains("액션")
        assertThat(first.thumbUrl).isEqualTo("https://11toon8.com/data/toon_category/7883.webp")
        assertThat(first.href).contains("is=7883")
    }

    @Test
    fun parsePopularListingKeepsRankingWithoutDate() {
        val html = readFixture("listing_popular.html")
        val page = HtmlParsers.parseListing(html, ComicSite.ORIGIN)
        val first = page.items.single()
        assertThat(first.id).isEqualTo("1")
        assertThat(first.ranking).isEqualTo("1")
        assertThat(first.updatedAt).isNull()
        assertThat(first.genre).contains("무협")
        assertThat(page.lastPage).isEqualTo(1)
    }

    @Test
    fun parseEpisodes() {
        val html = readFixture("episodes.html")
        val page = HtmlParsers.parseEpisodes(html, ComicSite.ORIGIN)
        assertThat(page.lastPage).isEqualTo(2)
        val episode = page.items.single()
        assertThat(episode.wrId).isEqualTo("1827530")
        assertThat(episode.title).contains("231화")
        assertThat(episode.date).isEqualTo("26.08.16")
        assertThat(episode.thumbUrl).isEqualTo("https://11toon7.com/01/1827530.webp")
        assertThat(episode.href).contains("/bbs/board.php")
        assertThat(episode.href).contains("wr_id=1827530")
    }

    @Test
    fun extractBackgroundUrl() {
        val url = HtmlParsers.extractBackgroundUrl(
            "background-image: url('//11toon8.com/data/toon_category/1.webp');",
        )
        assertThat(url).isEqualTo("//11toon8.com/data/toon_category/1.webp")
    }

    private fun readFixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().readText()
}
