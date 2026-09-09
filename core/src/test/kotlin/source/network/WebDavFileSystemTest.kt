package com.comics8.core.source.network

import com.comics8.core.source.FileRevision
import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import java.nio.ByteBuffer

class WebDavFileSystemTest {
    @Test
    fun listsDirectoryAndReadsOnlyRequestedRange() {
        val data = "abcdef".toByteArray()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "PROPFIND" -> MockResponse()
                    .setResponseCode(207)
                    .setBody(MULTISTATUS)
                request.method == "HEAD" -> MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", data.size)
                    .setHeader("Last-Modified", "Mon, 31 Aug 2026 00:00:00 GMT")
                    .setHeader("ETag", "\"book-v1\"")
                request.getHeader("Range") != null -> {
                    val range = checkNotNull(request.getHeader("Range"))
                    val (start, end) = range.removePrefix("bytes=").split('-').map(String::toInt)
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                        .setBody(okio.Buffer().write(data, start, end - start + 1))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val config = NetworkSourceConfig(
                id = "network-dav-test",
                protocol = NetworkProtocol.WEBDAV,
                name = "DAV",
                url = server.url("/dav/").toString(),
                username = "me",
                password = "pw",
            ).validated()
            val fs = WebDavFileSystem(config)

            assertThat(fs.list("")).containsExactly(
                NetworkNode("book.cbz", "book.cbz", false, FileRevision(6L, 0L)),
            )
            fs.openFile("book.cbz").channel.use { channel ->
                channel.position(2)
                val target = ByteBuffer.allocate(3)
                assertThat(channel.read(target)).isEqualTo(3)
                assertThat(String(target.array())).isEqualTo("cde")
            }

            val requests = generateSequence { server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .toList()
            assertThat(requests.any { it.getHeader("Authorization")?.startsWith("Basic ") == true }).isTrue()
            assertThat(requests.any { it.getHeader("Range") != null }).isTrue()
            assertThat(requests.first { it.getHeader("Range") != null }.getHeader("If-Match"))
                .isEqualTo("\"book-v1\"")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun handlesDigestAuthenticationChallenge() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val auth = request.getHeader("Authorization")
                if (auth == null || !auth.startsWith("Digest ")) {
                    return MockResponse()
                        .setResponseCode(401)
                        .setHeader(
                            "WWW-Authenticate",
                            """Digest realm="test-realm", nonce="mock-nonce-123", qop="auth", algorithm=MD5""",
                        )
                }
                return when (request.method) {
                    "PROPFIND" -> MockResponse()
                        .setResponseCode(207)
                        .setBody(MULTISTATUS)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        try {
            val config = NetworkSourceConfig(
                id = "network-dav-digest-test",
                protocol = NetworkProtocol.WEBDAV,
                name = "DAV-Digest",
                url = server.url("/dav/").toString(),
                username = "me",
                password = "pw",
            ).validated()
            val fs = WebDavFileSystem(config)

            val list = fs.list("")
            assertThat(list).hasSize(1)
            assertThat(list.first().name).isEqualTo("book.cbz")

            val requests = generateSequence { server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .toList()
            assertThat(requests).hasSize(2)
            // 첫 번째 요청: Basic auth 또는 미인증
            // 두 번째 요청: Digest auth
            val digestRequest = requests[1]
            val authHeader = checkNotNull(digestRequest.getHeader("Authorization"))
            assertThat(authHeader).startsWith("Digest ")
            assertThat(authHeader).contains("""username="me"""")
            assertThat(authHeader).contains("""realm="test-realm"""")
            assertThat(authHeader).contains("""nonce="mock-nonce-123"""")
            assertThat(authHeader).contains("qop=auth")
            assertThat(authHeader).contains("nc=00000001")
        } finally {
            server.shutdown()
        }
    }

    companion object {
        private const val MULTISTATUS = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
  <d:response><d:href>/dav/book.cbz</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>6</d:getcontentlength></d:prop></d:propstat></d:response>
</d:multistatus>"""
    }
}
