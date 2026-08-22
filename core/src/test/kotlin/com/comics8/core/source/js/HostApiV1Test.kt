package com.comics8.core.source.js

import com.comics8.core.network.ToonClient
import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceLocator
import com.comics8.core.source.SourceRegistry
import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Test
import org.mozilla.javascript.Context
import java.util.concurrent.atomic.AtomicInteger

class HostApiV1Test {
    @Test
    fun rangeGoesOnFetchSpecHeadersNotPolicyExtraHeaders() {
        val http = CapturingHttp(
            HttpResult(
                code = 200,
                headers = mapOf("Content-Length" to "4"),
                body = byteArrayOf(1, 2, 3, 4),
            ),
        )
        val json = evalJson(
            """
            (function() {
              var r = host.fetch({
                url: "https://example.test/gallery.nozomi",
                headers: { Range: "bytes=0-3" }
              });
              return { code: r.code, size: host.byteLength(r.body) };
            })()
            """.trimIndent(),
            http,
        )
        assertThat(json.getInt("code")).isEqualTo(200)
        assertThat(http.last!!.headers["Range"]).isEqualTo("bytes=0-3")
        assertThat(http.last!!.policy.extraHeaders.keys.none { it.equals("Range", ignoreCase = true) }).isTrue()
        assertThat(http.last!!.policy.extraHeaders["Accept-Language"]).isEqualTo("ko-KR,ko;q=0.9,en;q=0.8")
    }

    @Test
    fun rangeOnFull200SlicesBodyAndKeepsTotalLength() {
        val body = ByteArray(4096) { index -> (index % 256).toByte() }
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Length", body.size.toString())
                    .body(body.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()
        val client = ToonClient(http, isProxyEnabled = false, sources = SourceLocator { SourceRegistry() })
        val json = evalJson(
            """
            (function() {
              var r = host.fetch({
                url: "https://example.test/gallery.nozomi",
                headers: { Range: "bytes=0-99" }
              });
              return {
                code: r.code,
                size: host.byteLength(r.body),
                total: r.totalLength(),
                contentRange: r.header("Content-Range"),
                contentLength: r.header("Content-Length"),
                u32: host.u32be(r.body, 0),
                sliceLen: host.byteLength(host.slice(r.body, 0, 4))
              };
            })()
            """.trimIndent(),
            client,
        )
        assertThat(json.getInt("code")).isEqualTo(200)
        assertThat(json.getInt("size")).isEqualTo(100)
        assertThat(json.getLong("total")).isEqualTo(4096)
        assertThat(json.getString("contentRange")).isEqualTo("bytes 0-99/4096")
        assertThat(json.getString("contentLength")).isEqualTo("100")
        assertThat(json.getLong("u32")).isEqualTo(0x00010203L)
        assertThat(json.getInt("sliceLen")).isEqualTo(4)
    }

    @Test
    fun totalLengthPrefersContentRange() {
        val http = CapturingHttp(
            HttpResult(
                code = 206,
                headers = mapOf(
                    "Content-Range" to "bytes 0-99/4096",
                    "Content-Length" to "100",
                ),
                body = ByteArray(100) { it.toByte() },
            ),
        )
        val json = evalJson(
            """
            (function() {
              var r = host.fetch({ url: "https://example.test/n.nozomi" });
              return { total: r.totalLength(), header: r.header("content-range") };
            })()
            """.trimIndent(),
            http,
        )
        assertThat(json.getLong("total")).isEqualTo(4096)
        assertThat(json.getString("header")).isEqualTo("bytes 0-99/4096")
    }

    @Test
    fun fetchAllUsesConcurrencySix() {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val http = object : SourceHttp {
            override fun fetch(spec: FetchSpec): HttpResult {
                val n = inFlight.incrementAndGet()
                maxInFlight.updateAndGet { current -> maxOf(current, n) }
                try {
                    Thread.sleep(40)
                } finally {
                    inFlight.decrementAndGet()
                }
                return HttpResult(200, emptyMap(), spec.url.toByteArray())
            }

            override fun fetchText(spec: FetchSpec): String = fetch(spec).body.toString(Charsets.UTF_8)

            override fun isAccessible(url: String, policy: RequestPolicy): Boolean = true
        }
        val json = evalJson(
            """
            (function() {
              var specs = [];
              for (var i = 0; i < 7; i++) specs.push({ url: "https://example.test/" + i });
              var results = host.fetchAll(specs);
              return { count: results.length, first: host.utf8(results[0].body) };
            })()
            """.trimIndent(),
            http,
        )
        assertThat(json.getInt("count")).isEqualTo(7)
        assertThat(json.getString("first")).isEqualTo("https://example.test/0")
        assertThat(maxInFlight.get()).isEqualTo(6)
    }

    @Test
    fun evalSiteJsGalleryInfoAndGg() {
        val gallery = JsTestResources.read("hitomi/gallery_one_artist.js")
        val ggJs = JsTestResources.read("hitomi/gg.js")
        val engine = JsEngine()
        val handle = engine.load(JsTestResources.read("js/hello.js"), "hello.js")
        val galleryJson = evalJsonOn(
            engine,
            handle,
            """
            (function() {
              var info = host.evalSiteJs("galleryinfo", ${jsString(gallery)});
              return { id: String(info.id), title: info.title, artist: info.artists[0].artist };
            })()
            """.trimIndent(),
        )
        assertThat(galleryJson.getString("id")).isEqualTo("1001")
        assertThat(galleryJson.getString("title")).isEqualTo("Single Artist Gallery")
        assertThat(galleryJson.getString("artist")).isEqualTo("Demo Artist")

        val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcabc"
        val ggJson = evalJsonOn(
            engine,
            handle,
            """
            (function() {
              var gg = host.evalSiteJs("gg", ${jsString(ggJs)});
              return { b: gg.b, m: gg.m(0xCAB), s: gg.s("$hash") };
            })()
            """.trimIndent(),
        )
        assertThat(ggJson.getString("b")).isEqualTo("1690000000/")
        assertThat(ggJson.getInt("m")).isEqualTo(1)
        assertThat(ggJson.getString("s")).isEqualTo("3243")
    }

    @Test
    fun fetchAllDoesNotDeadlockWhenJsSourceIsRegistered() {
        val engine = JsEngine(callTimeoutMs = 3_000)
        val handle = engine.load(JsTestResources.read("js/hello.js"), "hello.js")
        val source = JsComicSource(engine, handle)
        val registry = SourceRegistry(listOf(source))
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("ok".toByteArray().toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        val client = ToonClient(
            client = http,
            proxyBaseUrl = "https://proxy.test/proxy",
            isProxyEnabled = true,
            sources = SourceLocator { registry },
        )
        val json = evalJsonOn(
            engine,
            handle,
            """
            (function() {
              var results = host.fetchAll([
                { url: "https://hello.test/a" },
                { url: "https://hello.test/b" }
              ]);
              return { count: results.length, a: results[0].code, b: results[1].code };
            })()
            """.trimIndent(),
            client,
        )
        assertThat(json.getInt("count")).isEqualTo(2)
        assertThat(json.getInt("a")).isEqualTo(200)
        assertThat(json.getInt("b")).isEqualTo(200)
    }

    @Test
    fun evalSiteJsScopeHidesHostAndJava() {
        val engine = JsEngine()
        val handle = engine.load(JsTestResources.read("js/hello.js"), "hello.js")
        val json = evalJsonOn(
            engine,
            handle,
            """
            (function() {
              var hostType = host.evalSiteJs("gg", "var gg = { b: typeof host, m: function(){return 0}, s: function(){return ''} }").b;
              var javaType = host.evalSiteJs("gg", "var gg = { b: typeof java + ',' + typeof Packages, m: function(){return 0}, s: function(){return ''} }").b;
              return { hostType: hostType, javaType: javaType };
            })()
            """.trimIndent(),
        )
        assertThat(json.getString("hostType")).isEqualTo("undefined")
        assertThat(json.getString("javaType")).isEqualTo("undefined,undefined")
        val error = runCatching {
            engine.eval(
                handle,
                """host.evalSiteJs("gg", "java.lang.Runtime.getRuntime(); var gg = { b: 'x', m: function(){return 0}, s: function(){return ''} }");""",
            )
        }.exceptionOrNull()
        assertThat(error).isNotNull()
    }

    @Test
    fun evalSiteJsRejectsUnknownKind() {
        val engine = JsEngine()
        val handle = engine.load(JsTestResources.read("js/hello.js"), "hello.js")
        val error = runCatching {
            engine.eval(handle, """host.evalSiteJs("other", "var x = 1");""")
        }.exceptionOrNull()
        assertThat(error).isNotNull()
        assertThat(error!!.message).contains("galleryinfo or gg")
    }

    private fun evalJson(expression: String, http: SourceHttp): JSONObject {
        val engine = JsEngine()
        val handle = engine.load(JsTestResources.read("js/hello.js"), "hello.js")
        return evalJsonOn(engine, handle, expression, http)
    }

    private fun evalJsonOn(
        engine: JsEngine,
        handle: JsSourceHandle,
        expression: String,
        http: SourceHttp? = null,
    ): JSONObject {
        val raw = engine.eval(handle, "JSON.stringify($expression)", http)
        return JSONObject(Context.toString(raw))
    }

    private fun jsString(value: String): String = JSONObject.quote(value)
}

private class CapturingHttp(private val result: HttpResult) : SourceHttp {
    var last: FetchSpec? = null

    override fun fetch(spec: FetchSpec): HttpResult {
        last = spec
        return result
    }

    override fun fetchText(spec: FetchSpec): String {
        last = spec
        if (result.code !in 200..299) error("HTTP ${result.code}")
        return result.body.toString(Charsets.UTF_8)
    }

    override fun isAccessible(url: String, policy: RequestPolicy): Boolean = true
}
