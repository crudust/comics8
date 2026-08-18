package com.comics8.core.network

import com.comics8.core.source.FetchSpec
import com.comics8.core.source.RequestPolicy
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
        val client = ToonClient(http, isProxyEnabled = false)
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
        )
        val text = client.fetch("http://103.204.13.68:8904/bbs/board.php")
        assertThat(text).isEqualTo("direct-ok")
    }
}
