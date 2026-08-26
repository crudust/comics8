package com.comics8.core.source.js

import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HostApi
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceHttp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class JsComicSourceTest {
    @Test
    fun helloFixtureImplementsComicSourceAndStampsSourceId() {
        runBlocking {
            val source = loadHello()
            assertThat(source.id).isEqualTo("hello")
            assertThat(source.displayName).isEqualTo("Hello")
            assertThat(source.origin).isEqualTo("https://hello.test")
            assertThat(source.hostApiLevel).isEqualTo(HostApi.LEVEL)
            assertThat(source.catalogs.map { it.id }).containsExactly("LATEST")
            assertThat(source.ownsHost("hello.test")).isTrue()
            assertThat(source.ownsHost("other.test")).isFalse()
            assertThat(source.useProxy("https://hello.test/x")).isTrue()

            val html = """
                <html><body>
                <a class="item" href="/series/1" data-id="1" data-thumb="/thumbs/1.jpg">Hello One</a>
                <a class="item" href="/series/2" data-id="2" data-thumb="/thumbs/2.jpg">Hello Two</a>
                </body></html>
            """.trimIndent()
            val http = MapHttp(
                mapOf("https://hello.test/list?page=1" to html.toByteArray()),
            )
            val page = source.loadListing("LATEST", 1, http)
            assertThat(page.items).hasSize(2)
            assertThat(page.items[0].id).isEqualTo("1")
            assertThat(page.items[0].title).isEqualTo("Hello One")
            assertThat(page.items[0].sourceId).isEqualTo("hello")
            assertThat(page.items[0].href).isEqualTo("https://hello.test/series/1")
            assertThat(page.items[0].thumbUrl).isEqualTo("https://hello.test/thumbs/1.jpg")
            assertThat(page.items[1].sourceId).isEqualTo("hello")

            val episodes = source.loadEpisodes(page.items[0], 1, http)
            assertThat(episodes.items).hasSize(1)
            assertThat(episodes.items[0].wrId).isEqualTo("1")
            val images = source.resolveImages(episodes.items[0], page.items[0], http)
            assertThat(images).containsExactly("https://hello.test/img/1.jpg")
        }
    }

    @Test
    fun helloSearchUsesHostJson() {
        runBlocking {
            val source = loadHello()
            val json = """[{"id":"9","title":"Found","thumbUrl":"https://hello.test/t.jpg","href":"https://hello.test/9"}]"""
            val http = MapHttp(mapOf("https://hello.test/search?q=foo%20bar" to json.toByteArray()))
            val items = source.search(SearchQuery("foo bar"), http)
            assertThat(items).hasSize(1)
            assertThat(items[0].id).isEqualTo("9")
            assertThat(items[0].title).isEqualTo("Found")
            assertThat(items[0].sourceId).isEqualTo("hello")
        }
    }

    private fun loadHello(): JsComicSource {
        val engine = JsEngine()
        val handle = engine.load(JsTestResources.read("js/hello.js"), "hello.js")
        return JsComicSource(engine, handle)
    }
}

internal class MapHttp(
    private val bodies: Map<String, ByteArray>,
    private val headersFor: (String) -> Map<String, String> = { emptyMap() },
    private val codeFor: (String) -> Int = { 200 },
) : SourceHttp {
    val specs = mutableListOf<FetchSpec>()

    override fun fetch(spec: FetchSpec): HttpResult {
        specs += spec
        val body = bodies[spec.url] ?: error("unexpected url ${spec.url}")
        return HttpResult(codeFor(spec.url), headersFor(spec.url), body)
    }

    override fun fetchText(spec: FetchSpec): String {
        val result = fetch(spec)
        if (result.code !in 200..299) error("HTTP ${result.code}")
        return result.body.toString(Charsets.UTF_8)
    }

    override fun isAccessible(url: String, policy: RequestPolicy): Boolean = url in bodies
}

internal object JsTestResources {
    fun read(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().readText()
}
