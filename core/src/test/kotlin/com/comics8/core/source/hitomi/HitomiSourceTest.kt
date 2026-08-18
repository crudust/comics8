package com.comics8.core.source.hitomi

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceHttp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
        assertThat(single.title).isEqualTo("Demo Artist")
        assertThat(single.genre).isEqualTo("doujinshi")
        assertThat(single.updatedAt).isEqualTo("24.08.15")
        assertThat(single.thumbUrl).isEqualTo(
            "https://tn.gold-usergeneratedcontent.net/webpsmalltn/c/ab/" +
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
        assertThat(http.recorded.any { it.url.contains("/artist/demo%20artist-korean.nozomi") }).isTrue()
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
            "https://w2.gold-usergeneratedcontent.net/1690000000/3243/" +
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc.webp",
        )
        assertThat(images[1]).startsWith("https://w1.gold-usergeneratedcontent.net/1690000000/4062/")
        assertThat(images[2]).isEqualTo(
            "https://a1.gold-usergeneratedcontent.net/1690000000/273/" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa111.avif",
        )
        assertThat(source().imageFallbacks(images[0])).containsExactly(
            "https://a2.gold-usergeneratedcontent.net/1690000000/3243/" +
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc.avif",
        )
    }

    @Test
    fun searchBareArtistReturnsSeriesCard() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val items = source().search(SearchQuery("demo_artist"), http)
        assertThat(items).hasSize(1)
        assertThat(items.single().id).isEqualTo("artist:demo_artist")
        assertThat(items.single().thumbUrl).isNotEmpty()
        assertThat(http.recorded.any { it.url.contains("/artist/demo%20artist-") }).isTrue()
    }

    @Test
    fun searchIntersectsFiltersWithRangePages() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val items = source().search(SearchQuery("artist:demo_artist female:sole_female"), http)
        assertThat(items.map { it.entryEpisodeId }).containsExactly("1001")
        val ranged = http.recorded.filter { it.url.endsWith(".nozomi") }
        assertThat(ranged).isNotEmpty()
        assertThat(ranged.all { it.headers["Range"] != null }).isTrue()
        assertThat(ranged.any { it.url.contains("/artist/demo%20artist-") }).isTrue()
        assertThat(ranged.any { it.url.contains("/female/sole%20female-") }).isTrue()
    }

    @Test
    fun searchFailsWhenFilterNozomiIsNotOk() = runBlocking<Unit> {
        val http = FakeHitomiHttp(missingNozomi = setOf("/female/"))
        try {
            source().search(SearchQuery("female:sole_female"), http)
            throw AssertionError("expected HTTP error")
        } catch (e: IllegalStateException) {
            assertThat(e.message).contains("HTTP")
        }
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
    fun galleryinfoRetriesOn502() = runBlocking<Unit> {
        val http = FakeHitomiHttp(failFirst = setOf("1001"), failFirstCode = 502)
        val delays = mutableListOf<Long>()
        val page = HitomiSource(delayMillis = { ms: Long -> delays += ms }).loadListing("LATEST", 1, http)
        assertThat(page.items.any { it.entryEpisodeId == "1001" }).isTrue()
        assertThat(delays).contains(250L)
        assertThat(http.galleryHits["1001"]?.get()).isAtLeast(2)
    }

    @Test
    fun resolveImagesReusesListingGalleryinfo() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val src = source()
        src.loadListing("LATEST", 1, http)
        val hitsAfterListing = http.galleryHits["1001"]?.get() ?: 0
        assertThat(hitsAfterListing).isGreaterThan(0)
        val item = ToonItem(
            id = "artist:demo_artist",
            title = "x",
            thumbUrl = "",
            href = "",
            sourceId = "hitomi",
            entryEpisodeId = "1001",
        )
        val episode = EpisodeItem(
            wrId = "1001",
            title = "ep",
            date = null,
            thumbUrl = null,
            href = HitomiUrls.reader("1001"),
        )
        val images = src.resolveImages(episode, item, http)
        assertThat(images).isNotEmpty()
        assertThat(http.galleryHits["1001"]?.get()).isEqualTo(hitsAfterListing)
    }

    @Test
    fun listingNozomiRetriesOn429() = runBlocking<Unit> {
        val http = FakeHitomiHttp(failFirstNozomi = true)
        val delays = mutableListOf<Long>()
        val page = HitomiSource(delayMillis = { ms: Long -> delays += ms }).loadListing("LATEST", 1, http)
        assertThat(page.items).isNotEmpty()
        assertThat(delays).contains(250L)
        assertThat(http.nozomiHits.get()).isAtLeast(2)
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
        private val failFirstCode: Int = 429,
        private val failFirstNozomi: Boolean = false,
        private val missingNozomi: Set<String> = emptySet(),
    ) : SourceHttp {
        val recorded = CopyOnWriteArrayList<FetchSpec>()
        val galleryHits = ConcurrentHashMap<String, AtomicInteger>()
        val nozomiHits = AtomicInteger(0)
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
                url.endsWith(".nozomi") -> nozomi(spec)
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
                return HttpResult(failFirstCode, emptyMap(), ByteArray(0))
            }
            val body = fixtures[id] ?: return HttpResult(404, emptyMap(), ByteArray(0))
            return ok(body.toByteArray())
        }

        private fun nozomi(spec: FetchSpec): HttpResult {
            val url = spec.url
            if (missingNozomi.any { url.contains(it) }) {
                return HttpResult(404, emptyMap(), ByteArray(0))
            }
            if (failFirstNozomi && nozomiHits.incrementAndGet() == 1) {
                return HttpResult(429, emptyMap(), ByteArray(0))
            }
            val ids = when {
                url.contains("/artist/demo%20artist-") || url.contains("/artist/demo_artist-") ->
                    listOf(1001L, 2002L)
                url.contains("/female/") -> listOf(1001L, 3003L)
                url.contains("/index-") -> listingIds
                else -> listingIds
            }
            val full = encodeIds(ids)
            val range = spec.headers.entries.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.value
            val (body, start) = sliceRange(full, range)
            val headers = linkedMapOf<String, String>()
            val total = when {
                !includeLength -> null
                url.contains("/index-") || url.contains("/popular/") -> 200L
                else -> full.size.toLong()
            }
            if (total != null) {
                val end = (start + body.size - 1).coerceAtLeast(start)
                headers["Content-Range"] = "bytes $start-$end/$total"
                headers["Content-Length"] = body.size.toString()
            }
            return HttpResult(206, headers, body)
        }

        private fun sliceRange(full: ByteArray, range: String?): Pair<ByteArray, Int> {
            if (range.isNullOrBlank() || !range.startsWith("bytes=")) return full to 0
            val spec = range.removePrefix("bytes=")
            val dash = spec.indexOf('-')
            if (dash < 0) return full to 0
            val start = spec.substring(0, dash).toIntOrNull()?.coerceAtLeast(0) ?: 0
            val endRaw = spec.substring(dash + 1)
            val end = if (endRaw.isEmpty()) full.lastIndex else endRaw.toIntOrNull() ?: full.lastIndex
            if (start >= full.size) return ByteArray(0) to start
            val until = (end + 1).coerceIn(start, full.size)
            return full.copyOfRange(start, until) to start
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
