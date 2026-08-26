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

class PepperCarrotJsSourceTest {
    @Test
    fun sampleLivesInExamples() {
        val root = workspaceRoot()
        val example = File(root, "examples/sources/peppercarrot.js")
        assertThat(example.isFile).isTrue()
    }

    @Test
    fun metadataMatchesPepperCarrotContract() {
        val source = loadPepperCarrot()
        assertThat(source.id).isEqualTo("peppercarrot")
        assertThat(source.displayName).isEqualTo("Pepper & Carrot")
        assertThat(source.origin).isEqualTo(ORIGIN)
        assertThat(source.catalogs.map { it.id }).containsExactly("ALL", "KO", "FR", "JA").inOrder()
        assertThat(source.catalogs.map { it.paginated }).containsExactly(false, false, false, false).inOrder()
        assertThat(source.episodePageSize).isEqualTo(100)
        assertThat(source.searchPlaceholder).isEqualTo("Search episodes (에피소드 검색)...")
        assertThat(source.imageReferer("https://www.peppercarrot.com/0_sources/ep39/hi-res/page.jpg")).isEqualTo("$ORIGIN/")
    }

    @Test
    fun ownsHostMatchesPepperCarrotDomains() {
        val source = loadPepperCarrot()
        assertThat(source.ownsHost("peppercarrot.com")).isTrue()
        assertThat(source.ownsHost("www.peppercarrot.com")).isTrue()
        assertThat(source.ownsHost("davidrevoy.com")).isTrue()
        assertThat(source.ownsHost("google.com")).isFalse()
        assertThat(source.ownsHost("other-comic.com")).isFalse()
    }

    @Test
    fun loadListingParsesPepperCarrotSeries() {
        runBlocking {
            val html = """
                <html>
                <body>
                    <figure class="thumbnail translated col sml-12 med-6 lrg-4">
                        <a href="https://www.peppercarrot.com/en/webcomic/ep39_The-Tavern.html">
                            <img src="https://www.peppercarrot.com/cache/gfx_Pepper-and-Carrot_by-David-Revoy_E39_480x399px_89q_330229.jpg" alt="Episode 39: The Tavern" />
                        </a>
                        <figcaption>
                            <a href="https://www.peppercarrot.com/en/webcomic/ep39_The-Tavern.html">Episode 39: The Tavern</a><br>
                            <span class="caption-smaller">Published on 2025-11-12.</span>
                        </figcaption>
                    </figure>
                </body>
                </html>
            """.trimIndent()

            val http = RecordingHttp(html)
            val source = loadPepperCarrot()
            val page = source.loadListing("KO", 1, http)

            assertThat(http.urls).containsExactly("https://www.peppercarrot.com/ko/webcomics/peppercarrot.html")
            assertThat(page.items).hasSize(1)
            val item = page.items.first()
            assertThat(item.id).isEqualTo("peppercarrot-ko")
            assertThat(item.title).isEqualTo("페퍼와 캐롯 (Pepper & Carrot)")
            assertThat(item.thumbUrl).isEqualTo("https://www.peppercarrot.com/cache/gfx_Pepper-and-Carrot_by-David-Revoy_E39_480x399px_89q_330229.jpg")
            assertThat(item.updatedAt).isEqualTo("2025.11.12")
            assertThat(item.genre).contains("CC BY 4.0")
        }
    }

    @Test
    fun loadEpisodesParsesEpisodeList() {
        runBlocking {
            val html = """
                <html>
                <body>
                    <figure class="thumbnail translated col sml-12 med-6 lrg-4">
                        <a href="https://www.peppercarrot.com/en/webcomic/ep39_The-Tavern.html">
                            <img src="https://www.peppercarrot.com/cache/ep39.jpg" />
                        </a>
                        <figcaption>
                            <a href="https://www.peppercarrot.com/en/webcomic/ep39_The-Tavern.html">Episode 39: The Tavern</a><br>
                            <span class="caption-smaller">Published on 2025-11-12.</span>
                        </figcaption>
                    </figure>
                    <figure class="thumbnail translated col sml-12 med-6 lrg-4">
                        <a href="https://www.peppercarrot.com/en/webcomic/ep38_The-Healer.html">
                            <img src="https://www.peppercarrot.com/cache/ep38.jpg" />
                        </a>
                        <figcaption>
                            <a href="https://www.peppercarrot.com/en/webcomic/ep38_The-Healer.html">Episode 38: The Healer</a><br>
                            <span class="caption-smaller">Published on 2023-04-26.</span>
                        </figcaption>
                    </figure>
                </body>
                </html>
            """.trimIndent()

            val http = RecordingHttp(html)
            val source = loadPepperCarrot()
            val toon = ToonItem(
                id = "peppercarrot-all",
                title = "Pepper & Carrot",
                thumbUrl = "https://www.peppercarrot.com/cover.jpg",
                href = "https://www.peppercarrot.com/en/webcomics/peppercarrot.html",
            )
            val episodePage = source.loadEpisodes(toon, 1, http)

            assertThat(episodePage.items).hasSize(2)
            val ep1 = episodePage.items[0]
            assertThat(ep1.wrId).isEqualTo("ep39_The-Tavern")
            assertThat(ep1.title).isEqualTo("Episode 39: The Tavern")
            assertThat(ep1.date).isEqualTo("2025-11-12")
            assertThat(ep1.href).isEqualTo("https://www.peppercarrot.com/en/webcomic/ep39_The-Tavern.html")

            val ep2 = episodePage.items[1]
            assertThat(ep2.wrId).isEqualTo("ep38_The-Healer")
            assertThat(ep2.title).isEqualTo("Episode 38: The Healer")
            assertThat(ep2.date).isEqualTo("2023-04-26")
        }
    }

    @Test
    fun resolveImagesExtractsHighResPagesAndFallbacks() {
        runBlocking {
            val html = """
                <html>
                <body>
                    <div class="webcomic-page">
                        <img class="comicpage" src="https://www.peppercarrot.com/0_sources/ep39_The-Tavern/low-res/en_Pepper-and-Carrot_by-David-Revoy_E39P00.jpg" alt="Header" />
                    </div>
                    <img src="https://www.peppercarrot.com/0_sources/ep39_The-Tavern/low-res/en_Pepper-and-Carrot_by-David-Revoy_E39P01.jpg" alt="Page 1" />
                    <img src="https://www.peppercarrot.com/0_sources/ep39_The-Tavern/low-res/en_Pepper-and-Carrot_by-David-Revoy_E39P02.jpg" alt="Page 2" />
                </body>
                </html>
            """.trimIndent()

            val http = RecordingHttp(html)
            val source = loadPepperCarrot()
            val toon = ToonItem(
                id = "peppercarrot-en",
                title = "Pepper & Carrot",
                thumbUrl = "",
                href = "https://www.peppercarrot.com/en/webcomics/peppercarrot.html",
            )
            val episode = EpisodeItem(
                wrId = "ep39_The-Tavern",
                title = "Episode 39: The Tavern",
                date = "2025-11-12",
                thumbUrl = null,
                href = "https://www.peppercarrot.com/en/webcomic/ep39_The-Tavern.html",
            )

            val images = source.resolveImages(episode, toon, http)
            assertThat(images).containsExactly(
                "https://www.peppercarrot.com/0_sources/ep39_The-Tavern/hi-res/en_Pepper-and-Carrot_by-David-Revoy_E39P00.jpg",
                "https://www.peppercarrot.com/0_sources/ep39_The-Tavern/hi-res/en_Pepper-and-Carrot_by-David-Revoy_E39P01.jpg",
                "https://www.peppercarrot.com/0_sources/ep39_The-Tavern/hi-res/en_Pepper-and-Carrot_by-David-Revoy_E39P02.jpg",
            ).inOrder()

            val fallbacks = source.imageFallbacks(images.first())
            assertThat(fallbacks).containsExactly(
                "https://www.peppercarrot.com/0_sources/ep39_The-Tavern/low-res/en_Pepper-and-Carrot_by-David-Revoy_E39P00.jpg",
            )
        }
    }

    @Test
    fun searchFindsMatchingEpisodes() {
        runBlocking {
            val html = """
                <html>
                <body>
                    <figure class="thumbnail translated col sml-12 med-6 lrg-4">
                        <a href="https://www.peppercarrot.com/en/webcomic/ep39_The-Tavern.html">
                            <img src="https://www.peppercarrot.com/cache/ep39.jpg" />
                        </a>
                        <figcaption>
                            <a href="https://www.peppercarrot.com/en/webcomic/ep39_The-Tavern.html">Episode 39: The Tavern</a><br>
                            <span class="caption-smaller">Published on 2025-11-12.</span>
                        </figcaption>
                    </figure>
                    <figure class="thumbnail translated col sml-12 med-6 lrg-4">
                        <a href="https://www.peppercarrot.com/en/webcomic/ep01_Potion-of-Flight.html">
                            <img src="https://www.peppercarrot.com/cache/ep01.jpg" />
                        </a>
                        <figcaption>
                            <a href="https://www.peppercarrot.com/en/webcomic/ep01_Potion-of-Flight.html">Episode 1: The Potion of Flight</a><br>
                            <span class="caption-smaller">Published on 2014-05-10.</span>
                        </figcaption>
                    </figure>
                </body>
                </html>
            """.trimIndent()

            val http = RecordingHttp(html)
            val source = loadPepperCarrot()
            val results = source.search(SearchQuery(text = "potion"), http)

            assertThat(results).hasSize(1)
            val result = results.first()
            assertThat(result.title).isEqualTo("Episode 1: The Potion of Flight")
            assertThat(result.entryEpisodeId).isEqualTo("ep01_Potion-of-Flight")
        }
    }

    private fun loadPepperCarrot(): JsComicSource {
        val engine = JsEngine()
        val handle = engine.load(readExample("peppercarrot.js"), "peppercarrot.js")
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
        private const val ORIGIN = "https://www.peppercarrot.com"

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
                    File(dir, "core/src/main/kotlin").isDirectory
                ) {
                    return dir
                }
                dir = dir.parentFile
            }
            error("workspace root not found from $cwd")
        }
    }
}
