package com.comics8.core.source.js

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ToonClient
import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.NotificationMode
import com.comics8.core.source.ProgressDisplay
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceHttp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class ElevenJsSourceTest {
    @Test
    fun sampleLivesInExamplesAndIsNotShipped() {
        val root = workspaceRoot()
        val example = File(root, "examples/sources/eleven.js")
        assertThat(example.isFile).isTrue()
        val hits = mutableListOf<String>()
        for (dir in listOf(
            File(root, "app/src/main"),
            File(root, "desktop/src/main"),
            File(root, "server"),
            File(root, "core/src/main"),
        )) {
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && it.name == "eleven.js" }
                .forEach { hits += it.relativeTo(root).invariantSeparatorsPath }
        }
        assertThat(hits).isEmpty()
    }

    @Test
    fun metadataMatchesElevenPublicContract() {
        val source = loadEleven()
        assertThat(source.id).isEqualTo("eleven")
        assertThat(source.displayName).isEqualTo("11toon")
        assertThat(source.origin).isEqualTo(ORIGIN)
        assertThat(source.catalogs.map { it.id }).containsExactly(
            "LATEST",
            "POPULAR",
            "COMPLETE",
            "TODAY",
        ).inOrder()
        assertThat(source.catalogs.map { it.paginated }).containsExactly(true, false, false, false).inOrder()
        assertThat(source.emptyListingOk).isFalse()
        assertThat(source.emptyEpisodesOk).isFalse()
        assertThat(source.notificationMode).isEqualTo(NotificationMode.LATEST_INTERSECTION)
        assertThat(source.episodePageSize).isEqualTo(100)
        assertThat(source.progressDisplay).isEqualTo(ProgressDisplay.LAST_READ_ORDER)
        assertThat(source.searchPlaceholder).isEqualTo("제목 검색")
        assertThat(source.defaultPolicy.userAgent).isEqualTo(ToonClient.USER_AGENT)
        assertThat(source.defaultPolicy.referer).isEqualTo(ORIGIN)
        assertThat(source.defaultPolicy.extraHeaders["Accept-Language"])
            .isEqualTo("ko-KR,ko;q=0.9,en;q=0.8")
        assertThat(source.coverUrl("7883")).isEqualTo("https://11toon8.com/data/toon_category/7883.webp")
        assertThat(source.imageReferer("https://www.pl3040.com/kr/1.png")).isEqualTo(ORIGIN)
        assertThat(source.useProxy("https://11toon8.com/x")).isTrue()
    }

    @Test
    fun ownsHostMatchesElevenDomainsAndOriginIp() {
        val source = loadEleven()
        assertThat(source.ownsHost("11toon8.com")).isTrue()
        assertThat(source.ownsHost("www.11toon.com")).isTrue()
        assertThat(source.ownsHost("11toon.com")).isTrue()
        assertThat(source.ownsHost("www.pl4050.com")).isTrue()
        assertThat(source.ownsHost("pl3040.com")).isTrue()
        assertThat(source.ownsHost("103.204.13.68")).isTrue()
        assertThat(source.ownsHost("evil.example.com")).isFalse()
        assertThat(source.ownsHost("pl405.com")).isFalse()
        assertThat(source.ownsHost("11toon8.net")).isFalse()
    }

    @Test
    fun loadLatestListingUsesFixtureAndStampsSourceId() {
        runBlocking {
            val html = readFixture("listing_latest.html")
            val http = RecordingHttp(html)
            val source = loadEleven()
            val page = source.loadListing("LATEST", 1, http)

            assertThat(http.urls).containsExactly(
                ORIGIN + "/bbs/board.php?bo_table=toon_c&type=upd&tablename=" + enc("최신만화") + "&page=1",
            )
            assertThat(page.items).hasSize(2)
            assertThat(page.currentPage).isEqualTo(1)
            assertThat(page.lastPage).isEqualTo(479)

            val first = page.items[0]
            assertThat(first.sourceId).isEqualTo("eleven")
            assertThat(first.id).isEqualTo("7883")
            assertThat(first.title).contains("TSUYOSHI")
            assertThat(first.updatedAt).isEqualTo("08.16")
            assertThat(first.genre).contains("액션")
            assertThat(first.thumbUrl).isEqualTo("https://11toon8.com/data/toon_category/7883.webp")
            assertThat(first.href).contains("is=7883")
            assertThat(page.items[1].sourceId).isEqualTo("eleven")
            assertThat(page.items[1].id).isEqualTo("1")
        }
    }

    @Test
    fun loadPopularListingKeepsRankingWithoutDate() {
        runBlocking {
            val http = RecordingHttp(readFixture("listing_popular.html"))
            val page = loadEleven().loadListing("POPULAR", 1, http)
            assertThat(http.urls.single()).isEqualTo(
                ORIGIN + "/bbs/board.php?bo_table=toon_c&tablename=" + enc("인기만화"),
            )
            val first = page.items.single()
            assertThat(first.id).isEqualTo("1")
            assertThat(first.ranking).isEqualTo("1")
            assertThat(first.updatedAt).isNull()
            assertThat(first.genre).contains("무협")
            assertThat(page.lastPage).isEqualTo(1)
            assertThat(first.sourceId).isEqualTo("eleven")
        }
    }

    @Test
    fun listingUrlsForCompleteAndToday() {
        runBlocking {
            val source = loadEleven()
            val completeHttp = RecordingHttp("<html></html>")
            source.loadListing("COMPLETE", 3, completeHttp)
            assertThat(completeHttp.urls.single()).isEqualTo(
                ORIGIN + "/bbs/board.php?bo_table=toon_c&is_over=1&tablename=" + enc("완결만화"),
            )
            val todayHttp = RecordingHttp("<html></html>")
            source.loadListing("today", 1, todayHttp)
            assertThat(todayHttp.urls.single()).isEqualTo(
                ORIGIN + "/bbs/board.php?bo_table=toon_c&type=today&tablename=" + enc("매일 추천 100"),
            )
        }
    }

    @Test
    fun unknownCatalogAndFavoriteThrow() {
        val source = loadEleven()
        val http = RecordingHttp("<html></html>")
        val unknown = assertThrows(RuntimeException::class.java) {
            runBlocking { source.loadListing("NOPE", 1, http) }
        }
        assertThat(unknown.message).contains("Unknown catalog: NOPE")
        val favorite = assertThrows(RuntimeException::class.java) {
            runBlocking { source.loadListing("FAVORITE", 1, http) }
        }
        assertThat(favorite.message).contains("즐겨찾기는 로컬 목록입니다.")
        assertThat(http.urls).isEmpty()
    }

    @Test
    fun searchUsesAjaxJsonFixture() {
        runBlocking {
            val source = loadEleven()
            val json = readFixture("search_success.json")
            val http = RecordingHttp(json)
            val items = source.search(SearchQuery("열혈강호"), http)
            assertThat(http.urls.single()).isEqualTo(
                ORIGIN + "/bbs/ajax.search.php?search_key=" + enc("열혈강호"),
            )
            assertThat(items).hasSize(2)
            assertThat(items[0].id).isEqualTo("1")
            assertThat(items[0].title).isEqualTo("열혈강호")
            assertThat(items[0].genre).isEqualTo("무협")
            assertThat(items[0].updatedAt).isEqualTo("08.15")
            assertThat(items[0].thumbUrl).isEqualTo("https://11toon8.com/data/toon_category/1.webp")
            assertThat(items[0].href).contains("is=1")
            assertThat(items[0].sourceId).isEqualTo("eleven")
            assertThat(items[1].title).isEqualTo("게이트 제로")
            assertThat(items[1].updatedAt).isEqualTo("08.16")
            assertThat(items[1].sourceId).isEqualTo("eleven")
        }
    }

    @Test
    fun searchFailAndEmptyQueryAreEmpty() {
        runBlocking {
            val source = loadEleven()
            val failHttp = RecordingHttp(readFixture("search_fail.json"))
            assertThat(source.search(SearchQuery("x"), failHttp)).isEmpty()
            val emptyHttp = RecordingHttp("{}")
            assertThat(source.search(SearchQuery("  "), emptyHttp)).isEmpty()
            assertThat(emptyHttp.urls).isEmpty()
        }
    }

    @Test
    fun loadEpisodesUsesFixture() {
        runBlocking {
            val http = RecordingHttp(readFixture("episodes.html"))
            val item = ToonItem(
                id = "7883",
                title = "TSUYOSHI",
                thumbUrl = "",
                href = "",
            )
            val page = loadEleven().loadEpisodes(item, 1, http)
            assertThat(http.urls.single()).isEqualTo(
                ORIGIN + "/bbs/board.php?bo_table=toons&stx=" + enc("TSUYOSHI") + "&is=7883&page=1",
            )
            assertThat(page.lastPage).isEqualTo(2)
            val episode = page.items.single()
            assertThat(episode.wrId).isEqualTo("1827530")
            assertThat(episode.title).contains("231화")
            assertThat(episode.date).isEqualTo("26.08.16")
            assertThat(episode.thumbUrl).isEqualTo("https://11toon7.com/01/1827530.webp")
            assertThat(episode.href).contains("/bbs/board.php")
            assertThat(episode.href).contains("wr_id=1827530")
        }
    }

    @Test
    fun resolveImagesFromJsArray() {
        runBlocking {
            val html = """
                <html>
                <script>
                var img_list = [
                    "//www.pl3040.com/kr//01/7883/1827530/1.jpg",
                    "//www.pl3040.com/kr//01/7883/1827530/2.jpg",
                    "//www.pl3040.com/kr//01/7883/1827530/3.jpg"
                ];
                </script>
                </html>
            """.trimIndent()
            val images = loadEleven().resolveImages(episode(), item(), RecordingHttp(html))
            assertThat(images).containsExactly(
                "https://www.pl3040.com/kr//01/7883/1827530/1.jpg",
                "https://www.pl3040.com/kr//01/7883/1827530/2.jpg",
                "https://www.pl3040.com/kr//01/7883/1827530/3.jpg",
            ).inOrder()
        }
    }

    @Test
    fun resolveImagesFromDomElements() {
        runBlocking {
            val html = """
                <html>
                <body>
                <div id="scroll-list" class="view-content">
                    <img src="/data/1.jpg">
                    <img data-src="/data/2.webp">
                    <img src="/toonfile/toonres/img/192x192.jpg">
                </div>
                </body>
                </html>
            """.trimIndent()
            val images = loadEleven().resolveImages(episode(), item(), RecordingHttp(html))
            assertThat(images).containsExactly(
                "$ORIGIN/data/1.jpg",
                "$ORIGIN/data/2.webp",
            ).inOrder()
        }
    }

    @Test
    fun resolveImagesUsesAccessibleCandidateList() {
        runBlocking {
            val html = """
                <html>
                <script>
                var img_list = ["//www.pl3040.com/kr/1.png", "//www.pl3040.com/kr/2.jpg"];
                var img_list_2 = ["//www.pl4050.com/kr/1.png?v=ei", "//www.pl4050.com/kr/2.jpg?v=ei"];
                </script>
                </html>
            """.trimIndent()
            val primary = "https://www.pl3040.com/kr/1.png"
            val fallback = "https://www.pl4050.com/kr/1.png?v=ei"
            val http = RecordingHttp(html) { url -> url == fallback }
            val images = loadEleven().resolveImages(episode(), item(), http)
            assertThat(http.headUrls).contains(primary)
            assertThat(images).containsExactly(
                "https://www.pl4050.com/kr/1.png?v=ei",
                "https://www.pl4050.com/kr/2.jpg?v=ei",
            ).inOrder()
        }
    }

    @Test
    fun resolveImagesKeepsPrimaryWhenNoCandidateIsAccessible() {
        runBlocking {
            val html = """
                <html>
                <script>
                var img_list = ["//www.pl3040.com/kr/1.png"];
                var img_list_2 = ["//www.pl4050.com/kr/1.png"];
                </script>
                </html>
            """.trimIndent()
            val images = loadEleven().resolveImages(episode(), item(), RecordingHttp(html) { false })
            assertThat(images).containsExactly("https://www.pl3040.com/kr/1.png")
        }
    }

    @Test
    fun resolveImagesThrowsWhenEmpty() {
        val http = RecordingHttp("<html><body>none</body></html>")
        val error = assertThrows(RuntimeException::class.java) {
            runBlocking { loadEleven().resolveImages(episode(), item(), http) }
        }
        assertThat(error.message).contains("만화 이미지를 불러오지 못했습니다.")
    }

    @Test
    fun imageFallbacksRotatePlDomains() {
        val fallbacks = loadEleven().imageFallbacks("https://www.pl3040.com/kr/1.png")
        assertThat(fallbacks).contains("https://www.pl4050.com/kr/1.png")
        assertThat(fallbacks).contains("https://www.pl4050.com/kr/1.png?v=ei")
        assertThat(fallbacks).contains("https://www.pl5060.com/kr/1.png")
        assertThat(fallbacks.none { it.contains("pl3040.com") }).isTrue()
    }

    private fun loadEleven(): JsComicSource {
        val engine = JsEngine()
        val handle = engine.load(readExample("eleven.js"), "eleven.js")
        return JsComicSource(engine, handle)
    }

    private fun episode(): EpisodeItem = EpisodeItem(
        wrId = "1827530",
        title = "TSUYOSHI 231화",
        date = "26.08.16",
        thumbUrl = null,
        href = "$ORIGIN/bbs/board.php?bo_table=toons&wr_id=1827530",
    )

    private fun item(): ToonItem = ToonItem(
        id = "7883",
        title = "TSUYOSHI",
        thumbUrl = "",
        href = "",
        sourceId = "eleven",
    )

    private class RecordingHttp(
        private val body: String,
        private val accessible: (String) -> Boolean = { true },
    ) : SourceHttp {
        val urls = mutableListOf<String>()
        val headUrls = mutableListOf<String>()

        override fun fetch(spec: FetchSpec): HttpResult {
            urls += spec.url
            return HttpResult(200, emptyMap(), body.toByteArray())
        }

        override fun fetchText(spec: FetchSpec): String {
            urls += spec.url
            return body
        }

        override fun isAccessible(url: String, policy: RequestPolicy): Boolean {
            headUrls += url
            return accessible(url)
        }
    }

    companion object {
        private const val ORIGIN = "http://103.204.13.68:8904"

        private fun enc(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        private fun readFixture(name: String): String =
            checkNotNull(ElevenJsSourceTest::class.java.classLoader?.getResourceAsStream(name))
                .bufferedReader()
                .readText()

        private fun readExample(name: String): String {
            val file = File(workspaceRoot(), "examples/sources/$name")
            check(file.isFile) { "missing example source: ${file.absolutePath}" }
            return file.readText()
        }

        private fun workspaceRoot(): File {
            val cwd = File(System.getProperty("user.dir")).canonicalFile
            var dir: File? = cwd
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile &&
                    File(dir, "core/src/main/kotlin/com/comics8/core").isDirectory
                ) {
                    return dir
                }
                dir = dir.parentFile
            }
            error("workspace root not found from $cwd")
        }
    }
}
