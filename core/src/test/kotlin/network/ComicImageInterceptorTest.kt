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
