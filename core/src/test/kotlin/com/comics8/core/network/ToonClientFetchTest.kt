package com.comics8.core.network

import com.comics8.core.source.FetchSpec
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceLocator
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.StubComicSource
import com.comics8.core.source.hostSuffixes
import com.google.common.truth.Truth.assertThat
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class ToonClientFetchTest {
    @Test
    fun rangeOnFull200SlicesBodyAndKeepsTotal() {
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
        val client = ToonClient(http, isProxyEnabled = false, sources = testLocator())
        val result = client.fetch(
            FetchSpec(
                url = "https://example.test/gallery.nozomi",
                policy = RequestPolicy(userAgent = ToonClient.USER_AGENT),
                headers = mapOf("Range" to "bytes=0-99"),
            ),
        )
        assertThat(result.code).isEqualTo(200)
        assertThat(result.body.size).isEqualTo(100)
        assertThat(result.body.toList()).isEqualTo(body.copyOfRange(0, 100).toList())
        assertThat(result.totalLength()).isEqualTo(4096)
        assertThat(result.header("Content-Range")).isEqualTo("bytes 0-99/4096")
        assertThat(result.header("Content-Length")).isEqualTo("100")
    }

    @Test
    fun proxyHttpErrorFallsBackToDirect() {
        val http = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                val (code, text) = if (url.contains("/proxy?url=")) {
                    502 to "bad gateway"
                } else {
                    200 to "direct-ok"
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("x")
                    .body(text.toByteArray().toResponseBody("text/plain".toMediaType()))
                    .build()
            })
            .build()
        val client = ToonClient(
            client = http,
            proxyBaseUrl = "https://example.test/proxy",
            isProxyEnabled = true,
            sources = testLocator(),
        )
        val text = client.fetch("http://103.204.13.68:8904/bbs/board.php")
        assertThat(text).isEqualTo("direct-ok")
    }

    @Test
    fun afterThreeProxyFailuresSkipsProxy() {
        val urls = mutableListOf<String>()
        val client = failingProxyClient(urls)
        repeat(3) { client.fetch(PROXY_TARGET) }
        assertThat(urls.count { it.contains("/proxy") }).isEqualTo(3)
        urls.clear()
        client.fetch(PROXY_TARGET)
        assertThat(urls.none { it.contains("/proxy") }).isTrue()
        assertThat(urls).isNotEmpty()
    }

    @Test
    fun proxyTriedAgainAfterCooldown() {
        val urls = mutableListOf<String>()
        var now = 1_000_000L
        val client = failingProxyClient(urls)
        client.clock = { now }
        repeat(3) { client.fetch(PROXY_TARGET) }
        now += ToonClient.PROXY_COOLDOWN_MS
        urls.clear()
        client.fetch(PROXY_TARGET)
        assertThat(urls.any { it.contains("/proxy") }).isTrue()
    }

    @Test
    fun proxySuccessResetsFailureCount() {
        val urls = mutableListOf<String>()
        var proxyCode = 502
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                urls += url
                val (code, text) = if (url.contains("/proxy?url=")) {
                    proxyCode to if (proxyCode in 200..299) "proxy-ok" else "bad gateway"
                } else {
                    200 to "direct-ok"
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("x")
                    .body(text.toByteArray().toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        val client = ToonClient(
            client = http,
            proxyBaseUrl = "https://example.test/proxy",
            isProxyEnabled = true,
            sources = testLocator(),
        )
        repeat(2) { client.fetch(PROXY_TARGET) }
        proxyCode = 200
        client.fetch(PROXY_TARGET)
        proxyCode = 502
        client.fetch(PROXY_TARGET)
        urls.clear()
        client.fetch(PROXY_TARGET)
        assertThat(urls.count { it.contains("/proxy") }).isEqualTo(1)
    }

    private fun failingProxyClient(urls: MutableList<String>): ToonClient {
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                urls += url
                val (code, text) = if (url.contains("/proxy?url=")) {
                    502 to "bad gateway"
                } else {
                    200 to "direct-ok"
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("x")
                    .body(text.toByteArray().toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        return ToonClient(
            client = http,
            proxyBaseUrl = "https://example.test/proxy",
            isProxyEnabled = true,
            sources = testLocator(),
        )
    }

    companion object {
        private const val PROXY_TARGET = "http://103.204.13.68:8904/bbs/board.php"

        private fun testLocator(): SourceLocator = SourceLocator {
            SourceRegistry(
                listOf(
                    StubComicSource(
                        id = "proxied",
                        ownedHost = hostSuffixes("103.204.13.68"),
                        proxy = true,
                    ),
                ),
            )
        }
    }
}
