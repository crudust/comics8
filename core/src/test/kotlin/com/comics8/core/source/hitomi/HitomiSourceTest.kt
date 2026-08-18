package com.comics8.core.source.hitomi

import com.comics8.core.model.ToonItem
import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceHttp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HitomiSourceTest {
    @Test
    fun loadListingMapsArtistCountsAndSkipsAnimeAndFailures() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val page = source().loadListing("LATEST", 1, http)
        assertThat(http.recorded.map { it.url }).contains(
            "https://ltn.gold-usergeneratedcontent.net/index-korean.nozomi",
        )
        assertThat(http.recorded.first { it.url.endsWith("index-korean.nozomi") }.headers["Range"])
            .isEqualTo("bytes=0-99")
        assertThat(http.recorded.first().policy.referer).isEqualTo(HitomiUrls.REFERER)
        assertThat(page.currentPage).isEqualTo(1)
        assertThat(page.lastPage).isEqualTo(2)
        assertThat(page.items.map { it.id }).containsExactly(
            "artist:demo_artist",
            "gallery:2002",
            "gallery:3003",
        ).inOrder()
        val single = page.items[0]
        assertThat(single.sourceId).isEqualTo("hitomi")
        assertThat(single.entryEpisodeId).isEqualTo("1001")
        assertThat(single.artistChoices).isEmpty()
        assertThat(single.genre).isEqualTo("doujinshi")
        assertThat(single.updatedAt).isEqualTo("24.08.15")
        assertThat(single.thumbUrl).isEqualTo(
            "https://tn.hitomi.la/webpsmalltn/c/ab/" +
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc.webp",
        )
        val multi = page.items[1]
        assertThat(multi.artistChoices.map { it.slug }).containsExactly("first_person", "second_person").inOrder()
        val none = page.items[2]
        assertThat(none.artistChoices).isEmpty()
        assertThat(page.items.none { it.entryEpisodeId == "4004" }).isTrue()
        assertThat(page.items.none { it.entryEpisodeId == "9999" }).isTrue()
    }

    @Test
    fun loadEpisodesArtistUsesNozomiPages() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val item = ToonItem(
            id = "artist:demo_artist",
            title = "Demo Artist",
            thumbUrl = "",
            href = HitomiUrls.artistPage("demo_artist"),
            sourceId = "hitomi",
        )
        val page = source().loadEpisodes(item, 1, http)
        assertThat(http.recorded.any { it.url.contains("/artist/demo_artist-korean.nozomi") }).isTrue()
        assertThat(page.items.map { it.wrId }).contains("1001")
        assertThat(page.items.first { it.wrId == "1001" }.href).isEqualTo(HitomiUrls.reader("1001"))
    }

    @Test
    fun loadEpisodesStandaloneGalleryIsSingleEpisode() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val item = ToonItem(
            id = "gallery:3003",
            title = "Standalone Gallery",
            thumbUrl = "",
            href = HitomiUrls.galleryPage("3003"),
            sourceId = "hitomi",
            entryEpisodeId = "3003",
        )
        val page = source().loadEpisodes(item, 1, http)
        assertThat(page.items).hasSize(1)
        assertThat(page.lastPage).isEqualTo(1)
        assertThat(page.items[0].wrId).isEqualTo("3003")
        assertThat(page.items[0].title).isEqualTo("Standalone Gallery")
    }

    @Test
    fun resolveImagesPinsFixtureUrl() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val item = ToonItem(
            id = "artist:demo_artist",
            title = "x",
            thumbUrl = "",
            href = "",
            sourceId = "hitomi",
            entryEpisodeId = "1001",
        )
        val episode = source().loadEpisodes(item, 1, http).items.first { it.wrId == "1001" }
        val images = source().resolveImages(episode, item, http)
        assertThat(images[0]).isEqualTo(
            "https://w2.gold-usergeneratedcontent.net/1690000000/c/ab/" +
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc.webp",
        )
        assertThat(images[1]).startsWith("https://w1.gold-usergeneratedcontent.net/1690000000/f/de/")
        assertThat(source().imageFallbacks(images[0])).containsExactly(
            "https://a2.gold-usergeneratedcontent.net/1690000000/c/ab/" +
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc.avif",
        )
    }

    @Test
    fun searchIntersectsAndRejectsBareWords() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val source = source()
        assertThat(source.search(SearchQuery("big breasts"), http)).isEmpty()
        val items = source.search(SearchQuery("artist:demo_artist"), http)
        assertThat(items.map { it.entryEpisodeId }).contains("1001")
        assertThat(http.recorded.any { it.url.endsWith("/artist/demo_artist-korean.nozomi") }).isTrue()
        assertThat(http.recorded.any { it.url.endsWith("/index-korean.nozomi") }).isTrue()
    }

    @Test
    fun galleryinfoRetriesOn429() = runBlocking<Unit> {
        val http = FakeHitomiHttp(failFirst = setOf("1001"))
        val delays = mutableListOf<Long>()
        val page = HitomiSource(delayMillis = { ms: Long -> delays += ms }).loadListing("LATEST", 1, http)
        assertThat(page.items.any { it.entryEpisodeId == "1001" }).isTrue()
        assertThat(delays).contains(250L)
        assertThat(http.galleryHits["1001"]?.get()).isAtLeast(2)
    }

    @Test
    fun lastPageFallbackWithoutLengthHeader() = runBlocking<Unit> {
        val http = FakeHitomiHttp(includeLength = false, listingIds = listOf(1001L, 2002L))
        val page = source().loadListing("LATEST", 1, http)
        assertThat(page.lastPage).isEqualTo(1)
        assertThat(page.items).isNotEmpty()
    }

    @Test
    fun defaultLanguageIsKoreanAndFlagOff() {
        assertThat(HitomiSource.DEFAULT_LANGUAGE).isEqualTo("korean")
        assertThat(HitomiSource.ENABLED_BY_DEFAULT).isFalse()
        assertThat(source().catalogs.map { it.id }).containsAtLeast("LATEST", "POPULAR", "TODAY")
    }

    private fun source() = HitomiSource(delayMillis = {})

    private class FakeHitomiHttp(
        private val includeLength: Boolean = true,
        private val listingIds: List<Long> = listOf(1001L, 2002L, 3003L, 4004L, 9999L),
        private val failFirst: Set<String> = emptySet(),
    ) : SourceHttp {
        val recorded = mutableListOf<FetchSpec>()
        val galleryHits = mutableMapOf<String, AtomicInteger>()
        private val fixtures = mapOf(
            "1001" to readFixture("hitomi/gallery_one_artist.js"),
            "2002" to readFixture("hitomi/gallery_two_artists.js"),
            "3003" to readFixture("hitomi/gallery_no_artist.js"),
            "4004" to readFixture("hitomi/gallery_anime.js"),
        )

        override fun fetch(spec: FetchSpec): HttpResult {
            recorded += spec
            val url = spec.url
            return when {
                url.endsWith(".nozomi") -> nozomi(url)
                url.contains("/galleries/") && url.endsWith(".js") -> gallery(url.substringAfterLast('/').removeSuffix(".js"))
                url.endsWith("/gg.js") -> ok(readFixture("hitomi/gg.js").toByteArray())
                else -> HttpResult(404, emptyMap(), ByteArray(0))
            }
        }

        override fun fetchText(spec: FetchSpec): String {
            val result = fetch(spec)
            if (result.code !in 200..299) error("HTTP ${result.code}")
            return result.body.toString(Charsets.UTF_8)
        }

        override fun isAccessible(url: String, policy: RequestPolicy): Boolean = true

        private fun gallery(id: String): HttpResult {
            val hits = galleryHits.getOrPut(id) { AtomicInteger(0) }.incrementAndGet()
            if (id in failFirst && hits == 1) {
                return HttpResult(429, emptyMap(), ByteArray(0))
            }
            val body = fixtures[id] ?: return HttpResult(404, emptyMap(), ByteArray(0))
            return ok(body.toByteArray())
        }

        private fun nozomi(url: String): HttpResult {
            val ids = when {
                url.contains("/artist/demo_artist-") -> listOf(1001L, 2002L)
                url.contains("/index-") -> listingIds
                else -> listingIds
            }
            val body = encodeIds(ids)
            val headers = linkedMapOf<String, String>()
            if (includeLength) {
                val total = 200L
                headers["Content-Range"] = "bytes 0-${body.size - 1}/$total"
                headers["Content-Length"] = body.size.toString()
            }
            return HttpResult(206, headers, body)
        }

        private fun ok(body: ByteArray) = HttpResult(200, mapOf("Content-Length" to body.size.toString()), body)

        private fun encodeIds(ids: List<Long>): ByteArray {
            val out = ByteArray(ids.size * 4)
            ids.forEachIndexed { index, id ->
                val o = index * 4
                out[o] = ((id shr 24) and 0xFF).toByte()
                out[o + 1] = ((id shr 16) and 0xFF).toByte()
                out[o + 2] = ((id shr 8) and 0xFF).toByte()
                out[o + 3] = (id and 0xFF).toByte()
            }
            return out
        }
    }

    companion object {
        private fun readFixture(name: String): String =
            checkNotNull(HitomiSourceTest::class.java.classLoader?.getResourceAsStream(name))
                .bufferedReader().readText()
    }
}
