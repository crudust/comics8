package com.comics8.core.source.js

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceConfig
import com.comics8.core.source.SourceHttp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class MangaDexJsSourceTest {
    @Test
    fun sampleLivesInExamples() {
        val root = workspaceRoot()
        val example = File(root, "examples/sources/mangadex.js")
        assertThat(example.isFile).isTrue()
    }

    @Test
    fun metadataMatchesMangaDexContract() {
        val source = loadMangaDex()
        assertThat(source.id).isEqualTo("mangadex")
        assertThat(source.displayName).isEqualTo("MangaDex")
        assertThat(source.origin).isEqualTo(ORIGIN)
        assertThat(source.defaultLanguage).isEqualTo("en")
        assertThat(source.catalogs.map { it.id }).containsExactly(
            "LATEST",
            "LATEST_EN",
            "LATEST_KO",
            "LATEST_JA",
            "POPULAR",
            "TOP_RATED",
            "RECENT",
            "ALL",
        ).inOrder()
        assertThat(source.catalogs.all { it.paginated }).isTrue()
        assertThat(source.episodePageSize).isEqualTo(500)
        assertThat(source.searchPlaceholder).contains("MangaDex")
        assertThat(source.imageReferer("https://cmdxd98sb0x3yprd.mangadex.network/data/123/1.jpg")).isEqualTo("$ORIGIN/")
    }

    @Test
    fun ownsHostMatchesMangaDexDomains() {
        val source = loadMangaDex()
        assertThat(source.ownsHost("mangadex.org")).isTrue()
        assertThat(source.ownsHost("api.mangadex.org")).isTrue()
        assertThat(source.ownsHost("uploads.mangadex.org")).isTrue()
        assertThat(source.ownsHost("cmdxd98sb0x3yprd.mangadex.network")).isTrue()
        assertThat(source.ownsHost("mangadex.network")).isTrue()
        assertThat(source.ownsHost("google.com")).isFalse()
        assertThat(source.ownsHost("other-comic.com")).isFalse()
    }

    @Test
    fun loadListingParsesMangaDexGridItems() {
        runBlocking {
            val json = """
                {
                  "result": "ok",
                  "response": "collection",
                  "data": [
                    {
                      "id": "775979ab-feb9-4f86-a574-3492efb995f4",
                      "type": "manga",
                      "attributes": {
                        "title": { "en": "Lupin III: Neighbor World Princess" },
                        "altTitles": [{ "ja": "ルパン三世　異世界の姫君" }],
                        "status": "ongoing",
                        "tags": [
                          { "attributes": { "name": { "en": "Action" } } },
                          { "attributes": { "name": { "en": "Isekai" } } }
                        ],
                        "latestUploadedChapter": "2026-08-28T12:00:00+00:00"
                      },
                      "relationships": [
                        {
                          "id": "cover-123",
                          "type": "cover_art",
                          "attributes": { "fileName": "cover1.jpg" }
                        },
                        {
                          "id": "auth-1",
                          "type": "author",
                          "attributes": { "name": "Monkey Punch" }
                        }
                      ]
                    }
                  ],
                  "limit": 32,
                  "offset": 0,
                  "total": 150
                }
            """.trimIndent()

            val http = RecordingHttp(json)
            val source = loadMangaDex()
            val page = source.loadListing("LATEST", 1, http)

            assertThat(http.urls.first()).contains("https://api.mangadex.org/manga?")
            assertThat(http.urls.first()).contains("order[latestUploadedChapter]=desc")
            assertThat(http.urls.first()).contains("availableTranslatedLanguage[]=en")
            assertThat(page.items).hasSize(1)

            val item = page.items.first()
            assertThat(item.id).isEqualTo("775979ab-feb9-4f86-a574-3492efb995f4")
            assertThat(item.title).isEqualTo("Lupin III: Neighbor World Princess")
            assertThat(item.thumbUrl).isEqualTo("https://uploads.mangadex.org/covers/775979ab-feb9-4f86-a574-3492efb995f4/cover1.jpg.512.jpg")
            assertThat(item.href).isEqualTo("https://mangadex.org/title/775979ab-feb9-4f86-a574-3492efb995f4")
            assertThat(item.genre).contains("Action, Isekai")
            assertThat(item.genre).contains("Ongoing")
            assertThat(item.updatedAt).isEqualTo("2026.08.28")
            assertThat(item.artistChoices).hasSize(1)
            assertThat(item.artistChoices.first().displayName).isEqualTo("Monkey Punch")
            assertThat(page.currentPage).isEqualTo(1)
            assertThat(page.lastPage).isEqualTo(5)
        }
    }

    @Test
    fun loadListingRespectsLanguageCatalogFilter() {
        runBlocking {
            val json = """
                {
                  "result": "ok",
                  "data": [],
                  "limit": 32,
                  "offset": 0,
                  "total": 0
                }
            """.trimIndent()

            val http = RecordingHttp(json)
            val source = loadMangaDex()
            source.loadListing("LATEST_KO", 1, http)

            assertThat(http.urls.first()).contains("availableTranslatedLanguage[]=ko")
        }
    }

    @Test
    fun searchQueriesTitlesWithMangaDexApi() {
        runBlocking {
            val json = """
                {
                  "result": "ok",
                  "data": [
                    {
                      "id": "b0b721ff-c388-4486-aa0f-c2b0bb321512",
                      "attributes": {
                        "title": { "ja-ro": "Sousou no Frieren" },
                        "altTitles": [{ "en": "Frieren: Beyond Journey's End" }],
                        "status": "ongoing",
                        "tags": []
                      },
                      "relationships": [
                        {
                          "id": "cover-456",
                          "type": "cover_art",
                          "attributes": { "fileName": "frieren.jpg" }
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()

            val http = RecordingHttp(json)
            val source = loadMangaDex()
            val results = source.search(SearchQuery("frieren"), http)

            assertThat(http.urls.first()).contains("https://api.mangadex.org/manga?title=frieren")
            assertThat(results).hasSize(1)
            val result = results.first()
            assertThat(result.id).isEqualTo("b0b721ff-c388-4486-aa0f-c2b0bb321512")
            assertThat(result.title).isEqualTo("Frieren: Beyond Journey's End")
            assertThat(result.thumbUrl).isEqualTo("https://uploads.mangadex.org/covers/b0b721ff-c388-4486-aa0f-c2b0bb321512/frieren.jpg.512.jpg")
        }
    }

    @Test
    fun loadEpisodesParsesChapterFeed() {
        runBlocking {
            val json = """
                {
                  "result": "ok",
                  "total": 1,
                  "limit": 500,
                  "offset": 0,
                  "data": [
                    {
                      "id": "ch-uuid-1",
                      "type": "chapter",
                      "attributes": {
                        "volume": "1",
                        "chapter": "19",
                        "title": "A Taste of Home",
                        "translatedLanguage": "en",
                        "publishAt": "2022-04-02T11:12:40+00:00",
                        "pages": 21
                      },
                      "relationships": [
                        {
                          "id": "grp-1",
                          "type": "scanlation_group",
                          "attributes": { "name": "ScanGroup" }
                        }
                      ]
                    }
                  ]
                }
            """.trimIndent()

            val http = RecordingHttp(json)
            val source = loadMangaDex()
            val toon = ToonItem(
                id = "775979ab-feb9-4f86-a574-3492efb995f4",
                title = "Lupin III",
                thumbUrl = "https://uploads.mangadex.org/cover.jpg",
                href = "https://mangadex.org/title/775979ab-feb9-4f86-a574-3492efb995f4",
            )
            val page = source.loadEpisodes(toon, 1, http)

            assertThat(page.items).hasSize(1)
            val ep = page.items.first()
            assertThat(ep.wrId).isEqualTo("ch-uuid-1")
            assertThat(ep.title).isEqualTo("Vol. 1 Ch. 19 - A Taste of Home (ScanGroup)")
            assertThat(ep.date).isEqualTo("2022-04-02")
            assertThat(ep.thumbUrl).isEqualTo("https://uploads.mangadex.org/cover.jpg")
            assertThat(ep.href).isEqualTo("https://mangadex.org/chapter/ch-uuid-1")
        }
    }

    @Test
    fun resolveImagesQueriesAtHomeAndProvidesFallbacks() {
        runBlocking {
            val json = """
                {
                  "result": "ok",
                  "baseUrl": "https://cmdxd98sb0x3yprd.mangadex.network",
                  "chapter": {
                    "hash": "97413b76180ae623de363dbbf31e2e1f",
                    "data": [
                      "1-abc.jpg",
                      "2-def.jpg"
                    ],
                    "dataSaver": [
                      "1-saver.jpg"
                    ]
                  }
                }
            """.trimIndent()

            val http = RecordingHttp(json)
            val source = loadMangaDex()
            val ep = EpisodeItem(
                wrId = "ch-uuid-1",
                title = "Ch. 1",
                date = "2022-04-02",
                thumbUrl = null,
                href = "https://mangadex.org/chapter/ch-uuid-1",
            )
            val toon = ToonItem(
                id = "manga-uuid",
                title = "Lupin",
                thumbUrl = "",
                href = "https://mangadex.org/title/manga-uuid",
            )

            val images = source.resolveImages(ep, toon, http)
            assertThat(images).containsExactly(
                "https://cmdxd98sb0x3yprd.mangadex.network/data/97413b76180ae623de363dbbf31e2e1f/1-abc.jpg",
                "https://cmdxd98sb0x3yprd.mangadex.network/data/97413b76180ae623de363dbbf31e2e1f/2-def.jpg",
            ).inOrder()

            val fallbacks = source.imageFallbacks(images.first())
            assertThat(fallbacks).containsExactly(
                "https://uploads.mangadex.org/data/97413b76180ae623de363dbbf31e2e1f/1-abc.jpg",
            )
        }
    }

    private fun loadMangaDex(): JsComicSource {
        val engine = JsEngine()
        val handle = engine.load(readExample("mangadex.js"), "mangadex.js")
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
        private const val ORIGIN = "https://mangadex.org"

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
