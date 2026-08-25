package com.comics8.core.source.js

import com.comics8.core.model.ArtistRef
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.ProgressDisplay
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceConfig
import com.comics8.core.source.SourceHttp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class HitomiJsSourceTest {
    @Test
    fun metaMatchesHitomiContract() {
        val source = loadHitomi()
        assertThat(source.id).isEqualTo("hitomi")
        assertThat(source.displayName).isEqualTo("Hitomi")
        assertThat(source.origin).isEqualTo("https://hitomi.la")
        assertThat(source.emptyListingOk).isTrue()
        assertThat(source.emptyEpisodesOk).isTrue()
        assertThat(source.progressDisplay).isEqualTo(ProgressDisplay.READ_COUNT)
        assertThat(source.episodePageSize).isEqualTo(25)
        assertThat(source.defaultLanguage).isEqualTo("korean")
        assertThat(source.searchPlaceholder).isEqualTo("Search artist:name...")
        assertThat(source.catalogs.map { it.id }).containsExactly(
            "LATEST", "POPULAR", "TODAY", "MONTH", "YEAR",
            "DOUJINSHI", "MANGA", "ARTISTCG", "GAMECG", "IMAGESET",
        ).inOrder()
        assertThat(source.catalogs.all { it.paginated }).isTrue()
        assertThat(source.useProxy("https://ltn.gold-usergeneratedcontent.net/gg.js")).isFalse()
        assertThat(source.imageReferer("https://w1.gold-usergeneratedcontent.net/x.webp"))
            .isEqualTo("https://hitomi.la/")
        assertThat(source.ownsHost("hitomi.la")).isTrue()
        assertThat(source.ownsHost("tn.gold-usergeneratedcontent.net")).isTrue()
        assertThat(source.ownsHost("example.com")).isFalse()
        assertThat(source.defaultPolicy.referer).isEqualTo("https://hitomi.la/")
    }

    @Test
    fun hitomiJsIsNotBundledInApkOrServer() {
        val root = workspaceRoot()
        assertThat(File(root, "examples/sources/hitomi.js").isFile).isTrue()
        assertThat(File(root, "app/src/main/assets/sources/hitomi.js").exists()).isFalse()
        assertThat(File(root, "core/src/main/resources/sources/hitomi.js").exists()).isFalse()
        assertThat(File(root, "desktop/src/main/resources/sources/hitomi.js").exists()).isFalse()
        val serverHits = File(root, "server").walkTopDown()
            .filter { it.isFile && it.name == "hitomi.js" }
            .toList()
        assertThat(serverHits).isEmpty()
        val apkResHits = File(root, "app/src/main").walkTopDown()
            .filter { it.isFile && it.name == "hitomi.js" }
            .toList()
        assertThat(apkResHits).isEmpty()
    }

    @Test
    fun loadListingMapsArtistCountsAndSkipsAnimeAndFailures() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val source = loadHitomi()
        val page = source.loadListing("LATEST", 1, http)
        assertThat(http.recorded.map { it.url }).contains(
            "https://ltn.gold-usergeneratedcontent.net/index-korean.nozomi",
        )
        assertThat(http.recorded.first { it.url.endsWith("index-korean.nozomi") }.headers["Range"])
            .isEqualTo("bytes=0-99")
        assertThat(http.recorded.first().policy.referer).isEqualTo("https://hitomi.la/")
        assertThat(http.recorded.first().policy.extraHeaders.keys.none { it.equals("Range", ignoreCase = true) }).isTrue()
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
        assertThat(http.recorded.count { it.url.contains("/galleries/") && it.url.endsWith(".js") })
            .isAtLeast(5)
    }

    @Test
    fun loadEpisodesArtistUsesNozomiPages() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val item = ToonItem(
            id = "artist:demo_artist",
            title = "Demo Artist",
            thumbUrl = "",
            href = "https://hitomi.la/artist/demo%20artist-all.html",
            sourceId = "hitomi",
        )
        val page = loadHitomi().loadEpisodes(item, 1, http)
        assertThat(http.recorded.any { it.url.contains("/artist/demo%20artist-korean.nozomi") }).isTrue()
        assertThat(page.items.map { it.wrId }).contains("1001")
        assertThat(page.items.first { it.wrId == "1001" }.href)
            .isEqualTo("https://hitomi.la/reader/1001.html")
    }

    @Test
    fun loadEpisodesStandaloneGalleryIsSingleEpisode() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val item = ToonItem(
            id = "gallery:3003",
            title = "Standalone Gallery",
            thumbUrl = "",
            href = "https://hitomi.la/galleries/3003.html",
            sourceId = "hitomi",
            entryEpisodeId = "3003",
        )
        val page = loadHitomi().loadEpisodes(item, 1, http)
        assertThat(page.items).hasSize(1)
        assertThat(page.lastPage).isEqualTo(1)
        assertThat(page.items[0].wrId).isEqualTo("3003")
        assertThat(page.items[0].title).isEqualTo("Standalone Gallery")
    }

    @Test
    fun loadEpisodesAnimeGalleryIsEmpty() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val item = ToonItem(
            id = "gallery:4004",
            title = "Animated Drop",
            thumbUrl = "",
            href = "https://hitomi.la/galleries/4004.html",
            sourceId = "hitomi",
            entryEpisodeId = "4004",
        )
        val page = loadHitomi().loadEpisodes(item, 1, http)
        assertThat(page.items).isEmpty()
        assertThat(page.lastPage).isEqualTo(1)
    }

    @Test
    fun resolveImagesPinsFixtureUrl() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val source = loadHitomi()
        val item = ToonItem(
            id = "artist:demo_artist",
            title = "x",
            thumbUrl = "",
            href = "",
            sourceId = "hitomi",
            entryEpisodeId = "1001",
        )
        val episode = source.loadEpisodes(item, 1, http).items.first { it.wrId == "1001" }
        val images = source.resolveImages(episode, item, http)
        assertThat(images[0]).isEqualTo(
            "https://w2.gold-usergeneratedcontent.net/1690000000/3243/" +
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc.webp",
        )
        assertThat(images[1]).startsWith("https://w1.gold-usergeneratedcontent.net/1690000000/4062/")
        assertThat(images[2]).isEqualTo(
            "https://a1.gold-usergeneratedcontent.net/1690000000/273/" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa111.avif",
        )
        assertThat(source.imageFallbacks(images[0])).containsExactly(
            "https://a2.gold-usergeneratedcontent.net/1690000000/3243/" +
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc.avif",
        )
        assertThat(http.recorded.any { it.url.endsWith("/gg.js") }).isTrue()
    }

    @Test
    fun searchBareArtistReturnsSeriesCard() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val items = loadHitomi().search(SearchQuery("demo_artist"), http)
        assertThat(items).hasSize(1)
        assertThat(items.single().id).isEqualTo("artist:demo_artist")
        assertThat(items.single().thumbUrl).isNotEmpty()
        assertThat(http.recorded.any { it.url.contains("/artist/demo%20artist-") }).isTrue()
    }

    @Test
    fun searchIntersectsFiltersWithRangePages() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val items = loadHitomi().search(SearchQuery("artist:demo_artist female:sole_female"), http)
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
        val error = runCatching {
            loadHitomi().search(SearchQuery("female:sole_female"), http)
        }.exceptionOrNull()
        assertThat(error).isNotNull()
        assertThat(error!!.message).contains("HTTP")
    }

    @Test
    fun galleryinfoRetriesOn429() = runBlocking<Unit> {
        val http = FakeHitomiHttp(failFirst = setOf("1001"))
        val page = loadHitomi().loadListing("LATEST", 1, http)
        assertThat(page.items.any { it.entryEpisodeId == "1001" }).isTrue()
        assertThat(http.galleryHits["1001"]?.get()).isAtLeast(2)
    }

    @Test
    fun listingNozomiRetriesOn429() = runBlocking<Unit> {
        val http = FakeHitomiHttp(failFirstNozomi = true)
        val page = loadHitomi().loadListing("LATEST", 1, http)
        assertThat(page.items).isNotEmpty()
        assertThat(http.nozomiHits.get()).isAtLeast(2)
    }

    @Test
    fun resolveImagesReusesListingGalleryinfo() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val src = loadHitomi()
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
            href = "https://hitomi.la/reader/1001.html",
        )
        val images = src.resolveImages(episode, item, http)
        assertThat(images).isNotEmpty()
        assertThat(http.galleryHits["1001"]?.get()).isEqualTo(hitsAfterListing)
    }

    @Test
    fun lastPageFallbackWithoutLengthHeader() = runBlocking<Unit> {
        val http = FakeHitomiHttp(includeLength = false, listingIds = listOf(1001L, 2002L))
        val page = loadHitomi().loadListing("LATEST", 1, http)
        assertThat(page.lastPage).isEqualTo(1)
        assertThat(page.items).isNotEmpty()
    }

    @Test
    fun loadEpisodesDeduplicatesGalleriesWithSameIdFromMultipleNozomiIds() = runBlocking<Unit> {
        val http = FakeHitomiHttp(
            extraFixtures = mapOf(
                "1002" to readFixture("hitomi/gallery_one_artist.js"),
            ),
            customNozomi = mapOf(
                "/artist/dup%20artist-" to listOf(1001L, 1002L, 2002L),
            ),
        )
        val item = ToonItem(
            id = "artist:dup_artist",
            title = "Dup Artist",
            thumbUrl = "",
            href = "https://hitomi.la/artist/dup%20artist-all.html",
            sourceId = "hitomi",
        )
        val page = loadHitomi().loadEpisodes(item, 1, http)
        assertThat(page.items.map { it.wrId }).containsExactly("1001", "2002").inOrder()
    }

    @Test
    fun suggestUsesTagIndexAndParsesRows() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val items = loadHitomi().suggest(SearchQuery("asa"), http)
        assertThat(http.recorded.any { it.url == "https://tagindex.hitomi.la/artist/a/s/a.json" }).isTrue()
        assertThat(items.map { "${it.ns}:${it.tag}" }).containsExactly("artist:cle masahiro", "artist:asanagi").inOrder()
        assertThat(items[1].count).isEqualTo(868)
    }

    @Test
    fun resolveParentBuildsArtistSeries() {
        val source = loadHitomi()
        val item = ToonItem(
            id = "gallery:2002",
            title = "Collab Gallery",
            thumbUrl = "https://tn.example/t.webp",
            href = "https://hitomi.la/galleries/2002.html",
            genre = "manga",
            updatedAt = "25.01.02",
            sourceId = "hitomi",
            entryEpisodeId = "2002",
        )
        val parent = source.resolveParent(item, ArtistRef("first_person", "First Person"), "2002")
        assertThat(parent).isNotNull()
        assertThat(parent!!.id).isEqualTo("artist:first_person")
        assertThat(parent.title).isEqualTo("First Person")
        assertThat(parent.href).isEqualTo("https://hitomi.la/artist/first%20person-all.html")
        assertThat(parent.entryEpisodeId).isEqualTo("2002")
        assertThat(parent.sourceId).isEqualTo("hitomi")
        assertThat(source.supportsChapterNotifications(parent)).isTrue()
        assertThat(source.supportsChapterNotifications(item)).isFalse()
        val candidates = source.notificationCandidates(listOf(item, parent))
        assertThat(candidates.map { it.id }).containsExactly("artist:first_person")
    }

    @Test
    fun applyConfigSwitchesListingLanguage() = runBlocking<Unit> {
        val http = FakeHitomiHttp()
        val source = loadHitomi()
        source.applyConfig(SourceConfig(language = "english"))
        source.loadListing("LATEST", 1, http)
        assertThat(http.recorded.any { it.url.endsWith("index-english.nozomi") }).isTrue()
    }

    @Test
    fun searchTwentyFiveCardsCompletesUnderThreeSeconds() = runBlocking<Unit> {
        val ids = (1L..25L).toList()
        val fixtures = ids.associate { id ->
            id.toString() to syntheticGallery(id)
        }
        val http = FakeHitomiHttp(
            listingIds = ids,
            extraFixtures = fixtures,
            customNozomi = mapOf("/index-" to ids),
        )
        val source = loadHitomi()
        val times = mutableListOf<Long>()
        repeat(5) {
            http.recorded.clear()
            val started = System.nanoTime()
            val page = source.loadListing("LATEST", 1, http)
            times += (System.nanoTime() - started) / 1_000_000L
            assertThat(page.items).hasSize(25)
        }
        times.sort()
        val p95Index = ((times.size - 1) * 0.95).toInt().coerceIn(times.indices)
        assertThat(times[p95Index]).isLessThan(3_000L)
    }

    private fun loadHitomi(): JsComicSource {
        val engine = JsEngine()
        val handle = engine.load(hitomiScript(), "hitomi.js")
        return JsComicSource(engine, handle)
    }

    private class FakeHitomiHttp(
        private val includeLength: Boolean = true,
        private val listingIds: List<Long> = listOf(1001L, 2002L, 3003L, 4004L, 9999L),
        private val failFirst: Set<String> = emptySet(),
        private val failFirstCode: Int = 429,
        private val failFirstNozomi: Boolean = false,
        private val missingNozomi: Set<String> = emptySet(),
        private val extraFixtures: Map<String, String> = emptyMap(),
        private val customNozomi: Map<String, List<Long>> = emptyMap(),
    ) : SourceHttp {
        val recorded = CopyOnWriteArrayList<FetchSpec>()
        val galleryHits = ConcurrentHashMap<String, AtomicInteger>()
        val nozomiHits = AtomicInteger(0)
        private val fixtures = mapOf(
            "1001" to readFixture("hitomi/gallery_one_artist.js"),
            "2002" to readFixture("hitomi/gallery_two_artists.js"),
            "3003" to readFixture("hitomi/gallery_no_artist.js"),
            "4004" to readFixture("hitomi/gallery_anime.js"),
        ) + extraFixtures

        override fun fetch(spec: FetchSpec): HttpResult {
            recorded += spec
            val url = spec.url
            return when {
                url.endsWith(".nozomi") -> nozomi(spec)
                url.contains("/galleries/") && url.endsWith(".js") ->
                    gallery(url.substringAfterLast('/').removeSuffix(".js"))
                url.endsWith("/gg.js") -> ok(readFixture("hitomi/gg.js").toByteArray())
                url.startsWith("https://tagindex.hitomi.la/") ->
                    ok("""[["cle masahiro",1023,"artist"],["asanagi",868,"artist"]]""".toByteArray())
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
            val custom = customNozomi.entries.firstOrNull { url.contains(it.key) }?.value
            val ids = when {
                custom != null -> custom
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
            checkNotNull(HitomiJsSourceTest::class.java.classLoader?.getResourceAsStream(name))
                .bufferedReader().readText()

        private fun hitomiScript(): String {
            val file = File(workspaceRoot(), "examples/sources/hitomi.js")
            check(file.isFile) { "missing ${file.absolutePath}" }
            return file.readText()
        }

        private fun workspaceRoot(): File {
            var dir = File(System.getProperty("user.dir")).canonicalFile
            repeat(6) {
                if (File(dir, "settings.gradle.kts").isFile && File(dir, "core").isDirectory) return dir
                dir = dir.parentFile ?: return@repeat
            }
            error("workspace root not found from ${System.getProperty("user.dir")}")
        }

        private fun syntheticGallery(id: Long): String {
            val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc"
            return """
                var galleryinfo = {
                  "id": $id,
                  "title": "Gallery $id",
                  "type": "doujinshi",
                  "date": "2024-08-15 12:34:56",
                  "artists": [{"artist": "Artist $id", "url": "https://hitomi.la/artist/artist_$id-all.html"}],
                  "files": [{"hash": "$hash", "haswebp": 1, "hasavif": 1}]
                };
            """.trimIndent()
        }
    }
}
