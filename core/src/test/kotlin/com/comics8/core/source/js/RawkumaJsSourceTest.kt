package com.comics8.core.source.js

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
import java.io.File

class RawkumaJsSourceTest {
    @Test
    fun sampleLivesInExamplesAndIsNotShipped() {
        val root = workspaceRoot()
        val example = File(root, "examples/sources/rawkuma.js")
        assertThat(example.isFile).isTrue()
    }

    @Test
    fun metadataMatchesRawkumaContract() {
        val source = loadRawkuma()
        assertThat(source.id).isEqualTo("rawkuma")
        assertThat(source.displayName).isEqualTo("Rawkuma")
        assertThat(source.origin).isEqualTo(ORIGIN)
        assertThat(source.catalogs.map { it.id }).containsExactly("LATEST", "ALL").inOrder()
        assertThat(source.catalogs.map { it.paginated }).containsExactly(false, true).inOrder()
        assertThat(source.episodePageSize).isEqualTo(500)
        assertThat(source.searchPlaceholder).isEqualTo("Search manga...")
        assertThat(source.imageReferer("https://kuma.kyut.dev/1.jpg")).isEqualTo("$ORIGIN/")
        assertThat(source.imageReferer("https://rcdn.kyut.dev/2.jpg")).isEqualTo("$ORIGIN/")
    }

    @Test
    fun ownsHostMatchesRawkumaAndKyutDomains() {
        val source = loadRawkuma()
        assertThat(source.ownsHost("rawkuma.net")).isTrue()
        assertThat(source.ownsHost("www.rawkuma.net")).isTrue()
        assertThat(source.ownsHost("kuma.kyut.dev")).isTrue()
        assertThat(source.ownsHost("rcdn.kyut.dev")).isTrue()
        assertThat(source.ownsHost("kyut.dev")).isTrue()
        assertThat(source.ownsHost("google.com")).isFalse()
        assertThat(source.ownsHost("other-manga.com")).isFalse()
    }

    @Test
    fun loadLatestListingParsesMangaCards() {
        runBlocking {
            val html = """
                <html>
                <body>
                <div class="overflow-hidden relative flex flex-col min-w-0">
                    <a href="https://rawkuma.net/manga/yani-neko/">
                        <img src="https://rawkuma.net/wp-content/uploads/yani.jpg" />
                    </a>
                    <h1>Yani Neko</h1>
                    <div class="font-normal text-xs">Ongoing</div>
                    <time datetime="2026-08-25T10:00:00Z">1 hour ago</time>
                </div>
                <div class="overflow-hidden relative flex flex-col min-w-0">
                    <a href="https://rawkuma.net/manga/chainsaw-man/">
                        <img src="https://rawkuma.net/wp-content/uploads/csm.jpg" />
                    </a>
                    <h1>Chainsaw Man</h1>
                    <div class="font-normal text-xs">Ongoing</div>
                </div>
                </body>
                </html>
            """.trimIndent()
            val http = RecordingHttp(html)
            val page = loadRawkuma().loadListing("LATEST", 1, http)

            assertThat(http.urls).containsExactly("$ORIGIN/")
            assertThat(page.items).hasSize(2)
            assertThat(page.currentPage).isEqualTo(1)

            val first = page.items[0]
            assertThat(first.sourceId).isEqualTo("rawkuma")
            assertThat(first.id).isEqualTo("yani-neko")
            assertThat(first.title).isEqualTo("Yani Neko")
            assertThat(first.thumbUrl).isEqualTo("https://rawkuma.net/wp-content/uploads/yani.jpg")
            assertThat(first.href).isEqualTo("https://rawkuma.net/manga/yani-neko/")
            assertThat(first.genre).contains("Ongoing")
            assertThat(first.updatedAt).isEqualTo("1 hour ago")

            val second = page.items[1]
            assertThat(second.id).isEqualTo("chainsaw-man")
            assertThat(second.title).isEqualTo("Chainsaw Man")
        }
    }

    @Test
    fun loadAllListingFetchesArchivePage() {
        runBlocking {
            val html = """
                <html>
                <body>
                <div class="group-data-[direction=horizontal]:hidden">
                    <a href="https://rawkuma.net/manga/billion-racer/">
                        <img src="https://rawkuma.net/wp-content/uploads/br.jpg" />
                    </a>
                    <h1>Billion Racer</h1>
                    <div class="font-normal text-xs">Completed</div>
                </div>
                <a href="/manga/page/2/">2</a>
                <a href="/manga/page/10/">10</a>
                </body>
                </html>
            """.trimIndent()
            val http = RecordingHttp(html)
            val page = loadRawkuma().loadListing("ALL", 2, http)

            assertThat(http.urls).containsExactly("$ORIGIN/manga/page/2/")
            assertThat(page.items).hasSize(1)
            val first = page.items[0]
            assertThat(first.id).isEqualTo("billion-racer")
            assertThat(first.title).isEqualTo("Billion Racer")
            assertThat(page.currentPage).isEqualTo(2)
            assertThat(page.lastPage).isEqualTo(10)
        }
    }

    @Test
    fun searchWithWpRestApiReturnsResults() {
        runBlocking {
            val restJson = """
                [
                    {
                        "id": 58377,
                        "slug": "yani-neko",
                        "link": "https://rawkuma.net/manga/yani-neko/",
                        "title": { "rendered": "Yani Neko" },
                        "content": { "rendered": "<p>A chain-smoking cat girl</p>" },
                        "_embedded": {
                            "wp:featuredmedia": [
                                { "source_url": "https://rawkuma.net/wp-content/uploads/yani.jpg" }
                            ]
                        }
                    }
                ]
            """.trimIndent()

            val http = RecordingHttp(restJson)
            val items = loadRawkuma().search(SearchQuery("yani"), http)

            assertThat(http.urls).containsExactly("$ORIGIN/wp-json/wp/v2/manga?search=yani&_embed=1")
            assertThat(items).hasSize(1)
            assertThat(items[0].id).isEqualTo("yani-neko")
            assertThat(items[0].title).isEqualTo("Yani Neko")
            assertThat(items[0].thumbUrl).isEqualTo("https://rawkuma.net/wp-content/uploads/yani.jpg")
            assertThat(items[0].href).isEqualTo("https://rawkuma.net/manga/yani-neko/")
            assertThat(items[0].genre).contains("A chain-smoking cat girl")
        }
    }

    @Test
    fun loadEpisodesParsesChapterRows() {
        runBlocking {
            val html = """
                <html>
                <body>
                <div id="chapter-list">
                    <div data-chapter-number="401">
                        <a href="https://rawkuma.net/manga/yani-neko/chapter-401.378504/">
                            <div class="font-medium"><span>Chapter 401</span></div>
                            <time datetime="2026-08-24T11:07:40Z">23 hours ago</time>
                            <img src="https://rawkuma.net/thumb401.jpg" />
                        </a>
                    </div>
                    <div data-chapter-number="400">
                        <a href="https://rawkuma.net/manga/yani-neko/chapter-400.378502/">
                            <div class="font-medium"><span>Chapter 400</span></div>
                            <time datetime="2026-08-24T11:07:40Z">23 hours ago</time>
                        </a>
                    </div>
                </div>
                </body>
                </html>
            """.trimIndent()
            val http = RecordingHttp(html)
            val item = ToonItem(
                id = "yani-neko",
                title = "Yani Neko",
                thumbUrl = "https://rawkuma.net/thumb.jpg",
                href = "https://rawkuma.net/manga/yani-neko/",
            )
            val episodePage = loadRawkuma().loadEpisodes(item, 1, http)

            assertThat(http.urls).containsExactly("https://rawkuma.net/manga/yani-neko/")
            assertThat(episodePage.items).hasSize(2)

            val ep1 = episodePage.items[0]
            assertThat(ep1.wrId).isEqualTo("401.378504")
            assertThat(ep1.title).isEqualTo("Chapter 401")
            assertThat(ep1.date).isEqualTo("2026-08-24")
            assertThat(ep1.thumbUrl).isEqualTo("https://rawkuma.net/thumb401.jpg")
            assertThat(ep1.href).isEqualTo("https://rawkuma.net/manga/yani-neko/chapter-401.378504/")

            val ep2 = episodePage.items[1]
            assertThat(ep2.wrId).isEqualTo("400.378502")
            assertThat(ep2.thumbUrl).isEqualTo("https://rawkuma.net/thumb.jpg")
        }
    }

    @Test
    fun resolveImagesExtractsImageUrls() {
        runBlocking {
            val html = """
                <html>
                <body>
                <section data-image-data="1">
                    <img src="https://kuma.kyut.dev/wp-content/scr/y/yani-neko/401/1.jpg" />
                    <img src="https://kuma.kyut.dev/wp-content/scr/y/yani-neko/401/2.jpg" />
                    <img src="https://kuma.kyut.dev/wp-content/scr/y/yani-neko/401/3.jpg" />
                </section>
                </body>
                </html>
            """.trimIndent()
            val http = RecordingHttp(html)
            val ep = EpisodeItem(
                wrId = "401.378504",
                title = "Chapter 401",
                date = "2026-08-24",
                thumbUrl = null,
                href = "https://rawkuma.net/manga/yani-neko/chapter-401.378504/",
            )
            val item = ToonItem(
                id = "yani-neko",
                title = "Yani Neko",
                thumbUrl = "",
                href = "https://rawkuma.net/manga/yani-neko/",
            )
            val images = loadRawkuma().resolveImages(ep, item, http)
            assertThat(images).containsExactly(
                "https://kuma.kyut.dev/wp-content/scr/y/yani-neko/401/1.jpg",
                "https://kuma.kyut.dev/wp-content/scr/y/yani-neko/401/2.jpg",
                "https://kuma.kyut.dev/wp-content/scr/y/yani-neko/401/3.jpg",
            ).inOrder()
        }
    }

    private fun loadRawkuma(): JsComicSource {
        val engine = JsEngine()
        val handle = engine.load(readExample("rawkuma.js"), "rawkuma.js")
        return JsComicSource(engine, handle)
    }

    private class RecordingHttp(
        private val body: String,
        private val accessible: (String) -> Boolean = { true },
    ) : SourceHttp {
        val urls = mutableListOf<String>()

        override fun fetch(spec: FetchSpec): HttpResult {
            urls += spec.url
            return HttpResult(200, emptyMap(), body.toByteArray())
        }

        override fun fetchText(spec: FetchSpec): String {
            urls += spec.url
            return body
        }

        override fun isAccessible(url: String, policy: RequestPolicy): Boolean = accessible(url)
    }

    companion object {
        private const val ORIGIN = "https://rawkuma.net"

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
