package com.comics8.core.network

import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.StubComicSource
import com.comics8.core.source.hostSuffixes
import com.google.common.truth.Truth.assertThat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ComicImageInterceptorTest {
    private lateinit var server: MockWebServer

    private val source = StubComicSource(
        id = "demo",
        origin = "https://demo.example",
        defaultPolicy = RequestPolicy(userAgent = "custom-agent", referer = "https://demo.example/"),
        ownedHost = hostSuffixes("127.0.0.1", "localhost", "demo.example"),
        fallbacks = { url ->
            if (url.contains("/missing.webp")) {
                listOf(url.replace("/missing.webp", "/fallback.avif"))
            } else {
                emptyList()
            }
        },
    )
    private val registry = SourceRegistry(listOf(source))

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun injectsRefererAndUserAgentAutomatically() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("image-bytes"))

        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(registry))
            .build()

        val url = server.url("/cover.webp").toString()
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { resp ->
            assertThat(resp.isSuccessful).isTrue()
            assertThat(resp.body?.string()).isEqualTo("image-bytes")
        }

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Referer")).isEqualTo("https://demo.example/")
        assertThat(recorded.getHeader("User-Agent")).isEqualTo(ToonClient.USER_AGENT)
    }

    @Test
    fun automaticallyRetriesFallbackUrlOn404() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("fallback-bytes"))

        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(registry))
            .build()

        val url = server.url("/missing.webp").toString()
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { resp ->
            assertThat(resp.isSuccessful).isTrue()
            assertThat(resp.body?.string()).isEqualTo("fallback-bytes")
        }

        assertThat(server.requestCount).isEqualTo(2)
        val firstReq = server.takeRequest()
        assertThat(firstReq.path).isEqualTo("/missing.webp")
        val secondReq = server.takeRequest()
        assertThat(secondReq.path).isEqualTo("/fallback.avif")
    }

    @Test
    fun limitsFallbackAttemptsToConfiguredMaximum() {
        val multiFallbackSource = StubComicSource(
            id = "demo2",
            origin = "https://demo2.example",
            ownedHost = hostSuffixes("127.0.0.1", "localhost", "demo2.example"),
            fallbacks = { url ->
                listOf(
                    url.replace("/missing.webp", "/fb1.webp"),
                    url.replace("/missing.webp", "/fb2.webp"),
                    url.replace("/missing.webp", "/fb3.webp"),
                    url.replace("/missing.webp", "/fb4.webp"),
                )
            },
        )
        val reg = SourceRegistry(listOf(multiFallbackSource))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).setBody("fb3-bytes"))

        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(reg, maxFallbacks = 2))
            .build()

        val url = server.url("/missing.webp").toString()
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { resp ->
            // Since maxFallbacks = 2, it should only try missing.webp, fb1.webp, fb2.webp (3 requests total) and return 404
            assertThat(resp.isSuccessful).isFalse()
            assertThat(resp.code).isEqualTo(404)
        }

        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun canceledCallBeforeProceedDoesNotExecuteFallbacks() {
        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(registry))
            .build()

        val url = server.url("/missing.webp").toString()
        val call = client.newCall(Request.Builder().url(url).build())
        call.cancel()

        try {
            call.execute().use { }
            fail("Expected IOException on canceled call")
        } catch (e: IOException) {
            assertThat(e.message).contains("Canceled")
        }

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun canceledCallDuringExecutionDoesNotExecuteFallbacks() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("fallback-bytes"))

        val client = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                val resp = chain.proceed(chain.request())
                chain.call().cancel()
                resp
            }
            .addInterceptor(ComicImageInterceptor(registry))
            .build()

        val url = server.url("/missing.webp").toString()
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { }
            fail("Expected IOException due to cancellation")
        } catch (e: IOException) {
            assertThat(e.message).contains("Canceled")
        }

        assertThat(server.requestCount).isEqualTo(1)
        val firstReq = server.takeRequest()
        assertThat(firstReq.path).isEqualTo("/missing.webp")
    }

    @Test
    fun socketTimeoutDoesNotTriggerFallbacks() {
        var callCount = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(registry))
            .addInterceptor { chain ->
                callCount++
                throw SocketTimeoutException("Read timed out")
            }
            .build()

        val url = server.url("/missing.webp").toString()
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { }
            fail("Expected SocketTimeoutException")
        } catch (e: SocketTimeoutException) {
            assertThat(e.message).contains("timed out")
        }

        assertThat(callCount).isEqualTo(1)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun unrecoverableHostErrorTriggersFallback() {
        var callCount = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(registry))
            .addInterceptor { chain ->
                callCount++
                if (chain.request().url.encodedPath.contains("/missing.webp")) {
                    throw UnknownHostException("Unable to resolve host")
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("recovered-bytes".toResponseBody("image/avif".toMediaType()))
                    .build()
            }
            .build()

        val url = server.url("/missing.webp").toString()
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { resp ->
            assertThat(resp.isSuccessful).isTrue()
            assertThat(resp.body?.string()).isEqualTo("recovered-bytes")
        }

        assertThat(callCount).isEqualTo(2)
    }

    @Test
    fun serverError500TriggersFallback() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("fallback-500-bytes"))

        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(registry))
            .build()

        val url = server.url("/missing.webp").toString()
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { resp ->
            assertThat(resp.isSuccessful).isTrue()
            assertThat(resp.body?.string()).isEqualTo("fallback-500-bytes")
        }

        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun forbidden403DoesNotTriggerFallback() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))

        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(registry))
            .build()

        val url = server.url("/missing.webp").toString()
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { resp ->
            assertThat(resp.code).isEqualTo(403)
        }

        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun forbidden403TriggersCrossHostFallback() {
        val crossHostSource = StubComicSource(
            id = "cross403",
            origin = "https://127.0.0.1",
            ownedHost = hostSuffixes("127.0.0.1", "localhost"),
            fallbacks = { url ->
                listOf(url.replace("127.0.0.1", "localhost"))
            },
        )
        val reg = SourceRegistry(listOf(crossHostSource))
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok-from-localhost"))

        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(reg))
            .build()

        val url = "http://127.0.0.1:${server.port}/page1.webp"
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            assertThat(resp.isSuccessful).isTrue()
            assertThat(resp.body?.string()).isEqualTo("ok-from-localhost")
        }

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(server.takeRequest().getHeader("Host")).startsWith("127.0.0.1")
        assertThat(server.takeRequest().getHeader("Host")).startsWith("localhost")
    }

    @Test
    fun htmlErrorPageWith200TriggersFallback() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><body>Blocked</body></html>"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "image/avif")
                .setBody("real-image-bytes"),
        )

        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(registry))
            .build()

        val url = server.url("/missing.webp").toString()
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            assertThat(resp.isSuccessful).isTrue()
            assertThat(resp.body?.string()).isEqualTo("real-image-bytes")
        }

        assertThat(server.requestCount).isEqualTo(2)
        assertThat(server.takeRequest().path).isEqualTo("/missing.webp")
        assertThat(server.takeRequest().path).isEqualTo("/fallback.avif")
    }

    @Test
    fun manualRetryTriggersFallbackOnTimeoutAndSameHost403AndStripsHeader() {
        val multiSource = StubComicSource(
            id = "manual",
            origin = "https://127.0.0.1",
            ownedHost = hostSuffixes("127.0.0.1"),
            fallbacks = { url ->
                listOf(
                    url.replace("/p1.webp", "/fb1.webp"),
                    url.replace("/p1.webp", "/fb2.webp"),
                    url.replace("/p1.webp", "/fb3.webp"),
                    url.replace("/p1.webp", "/fb4.webp"),
                )
            },
        )
        val reg = SourceRegistry(listOf(multiSource))
        val recordedPaths = mutableListOf<String>()
        val recordedRetryHeaders = mutableListOf<String?>()

        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(reg, maxFallbacks = 2))
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                recordedPaths += path
                recordedRetryHeaders += req.header(ComicImageInterceptor.HEADER_RETRY_MODE)
                when (path) {
                    "/p1.webp" -> throw SocketTimeoutException("Read timed out")
                    "/fb1.webp" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body("forbidden".toResponseBody("text/plain".toMediaType()))
                        .build()
                    "/fb2.webp" -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("missing".toResponseBody("text/plain".toMediaType()))
                        .build()
                    else -> Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("manual-recovered".toResponseBody("image/webp".toMediaType()))
                        .build()
                }
            }
            .build()

        val request = Request.Builder()
            .url("http://127.0.0.1:${server.port}/p1.webp")
            .header(ComicImageInterceptor.HEADER_RETRY_MODE, ComicImageInterceptor.RETRY_MODE_MANUAL)
            .build()

        client.newCall(request).execute().use { resp ->
            assertThat(resp.isSuccessful).isTrue()
            assertThat(resp.body?.string()).isEqualTo("manual-recovered")
        }

        assertThat(recordedPaths).containsExactly("/p1.webp", "/fb1.webp", "/fb2.webp", "/fb3.webp").inOrder()
        assertThat(recordedRetryHeaders.all { it == null }).isTrue()
    }

    @Test
    fun stickyHostSkipsDeadOriginAfterHardFailureAndSelfHealsWhenPreferredFails() {
        val mirrorSource = StubComicSource(
            id = "sticky",
            origin = "https://host-a.example",
            ownedHost = hostSuffixes("host-a.example", "host-b.example"),
            fallbacks = { url -> listOf(url.replace("host-a.example", "host-b.example")) },
        )
        val reg = SourceRegistry(listOf(mirrorSource))
        val requestedHosts = mutableListOf<String>()
        var failB = false
        var now = 1_000L

        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(reg, clock = { now }))
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                requestedHosts += host
                val fail = (host == "host-a.example" && !failB) || (host == "host-b.example" && failB)
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (fail) 502 else 200)
                    .message(if (fail) "Bad Gateway" else "OK")
                    .body("img".toResponseBody("image/webp".toMediaType()))
                    .build()
            }
            .build()

        // 1st request: host-a fails 502 -> recovers on host-b -> promotes host-b
        client.newCall(Request.Builder().url("https://host-a.example/1.webp").build()).execute().use {
            assertThat(it.isSuccessful).isTrue()
        }
        assertThat(requestedHosts).containsExactly("host-a.example", "host-b.example").inOrder()

        // 2nd request: skips host-a and goes straight to host-b
        requestedHosts.clear()
        client.newCall(Request.Builder().url("https://host-a.example/2.webp").build()).execute().use {
            assertThat(it.isSuccessful).isTrue()
        }
        assertThat(requestedHosts).containsExactly("host-b.example")

        // 3rd request: host-b goes down -> self-heals back to host-a
        failB = true
        requestedHosts.clear()
        client.newCall(Request.Builder().url("https://host-a.example/3.webp").build()).execute().use {
            assertThat(it.isSuccessful).isTrue()
        }
        assertThat(requestedHosts).containsExactly("host-b.example", "host-a.example").inOrder()
    }

    @Test
    fun stickyHostOn404RequiresTwoRecoveriesBeforePromoting() {
        val mirrorSource = StubComicSource(
            id = "sticky404",
            origin = "https://host-a.example",
            ownedHost = hostSuffixes("host-a.example", "host-b.example"),
            fallbacks = { url -> listOf(url.replace("host-a.example", "host-b.example")) },
        )
        val reg = SourceRegistry(listOf(mirrorSource))
        val requestedHosts = mutableListOf<String>()

        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(reg))
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                requestedHosts += host
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (host == "host-a.example") 404 else 200)
                    .message("status")
                    .body("img".toResponseBody("image/webp".toMediaType()))
                    .build()
            }
            .build()

        // 1st 404 recovery: must NOT promote host-b yet
        client.newCall(Request.Builder().url("https://host-a.example/1.webp").build()).execute().use {
            assertThat(it.isSuccessful).isTrue()
        }
        assertThat(requestedHosts).containsExactly("host-a.example", "host-b.example").inOrder()

        // 2nd request still tries host-a first, then recovers on host-b and promotes host-b
        requestedHosts.clear()
        client.newCall(Request.Builder().url("https://host-a.example/2.webp").build()).execute().use {
            assertThat(it.isSuccessful).isTrue()
        }
        assertThat(requestedHosts).containsExactly("host-a.example", "host-b.example").inOrder()

        // 3rd request skips host-a and goes straight to host-b
        requestedHosts.clear()
        client.newCall(Request.Builder().url("https://host-a.example/3.webp").build()).execute().use {
            assertThat(it.isSuccessful).isTrue()
        }
        assertThat(requestedHosts).containsExactly("host-b.example")
    }

    @Test
    fun degradedFallbackHostIsDemotedBehindHealthyHost() {
        val mirrorSource = StubComicSource(
            id = "demote",
            origin = "https://host-a.example",
            ownedHost = hostSuffixes("host-a.example", "host-b.example", "host-c.example"),
            fallbacks = { url ->
                listOf(
                    url.replace("host-a.example", "host-b.example"),
                    url.replace("host-a.example", "host-c.example"),
                )
            },
        )
        val reg = SourceRegistry(listOf(mirrorSource))
        val requestedHosts = mutableListOf<String>()

        val client = OkHttpClient.Builder()
            .addInterceptor(ComicImageInterceptor(reg))
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                requestedHosts += host
                val code = if (host == "host-c.example") 200 else 500
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("status")
                    .body("img".toResponseBody("image/webp".toMediaType()))
                    .build()
            }
            .build()

        // 1st request: host-a fails 500 -> host-b fails 500 (demoted) -> host-c succeeds (promoted)
        client.newCall(Request.Builder().url("https://host-a.example/1.webp").build()).execute().use {
            assertThat(it.isSuccessful).isTrue()
        }
        assertThat(requestedHosts).containsExactly("host-a.example", "host-b.example", "host-c.example").inOrder()

        // Now ask from a different origin host-d where host-b and host-c are fallbacks:
        // host-c should be tried before demoted host-b.
        val source2 = StubComicSource(
            id = "demote2",
            origin = "https://host-d.example",
            ownedHost = hostSuffixes("host-d.example"),
            fallbacks = { url ->
                listOf(
                    url.replace("host-d.example", "host-b.example"),
                    url.replace("host-d.example", "host-c.example"),
                )
            },
        )
        val reg2 = SourceRegistry(listOf(mirrorSource, source2))
        val interceptor = ComicImageInterceptor(reg2)
        val client2 = OkHttpClient.Builder()
            .addInterceptor(interceptor)
            .addInterceptor { chain ->
                val req = chain.request()
                val host = req.url.host
                requestedHosts += host
                val code = if (host == "host-c.example") 200 else 500
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("status")
                    .body("img".toResponseBody("image/webp".toMediaType()))
                    .build()
            }
            .build()

        // Prime degradation on host-b via host-a
        requestedHosts.clear()
        client2.newCall(Request.Builder().url("https://host-a.example/1.webp").build()).execute().use {}
        // Now request host-d; host-c should come before demoted host-b
        requestedHosts.clear()
        client2.newCall(Request.Builder().url("https://host-d.example/1.webp").build()).execute().use {
            assertThat(it.isSuccessful).isTrue()
        }
        assertThat(requestedHosts).containsExactly("host-d.example", "host-c.example").inOrder()
    }

    @Test
    fun newImageHttpClientConfiguredCorrectly() {
        val testDir = File(System.getProperty("java.io.tmpdir"), "test-c8-cache-" + System.currentTimeMillis())
        val base = ToonClient.defaultClient(cacheDirectory = testDir)
        try {
            val imageClient = ToonClient.newImageHttpClient(base, registry)

            assertThat(imageClient.cache).isNull()
            assertThat(imageClient.connectTimeoutMillis).isEqualTo(15_000)
            assertThat(imageClient.readTimeoutMillis).isEqualTo(15_000)
            assertThat(imageClient.dispatcher.maxRequests).isEqualTo(32)
            assertThat(imageClient.dispatcher.maxRequestsPerHost).isEqualTo(4)
        } finally {
            testDir.deleteRecursively()
        }
    }
}
