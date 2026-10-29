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

    @Test
    fun handlesPercentCharacterInFilenameWithoutDoubleDecoding() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "PROPFIND" -> MockResponse()
                    .setResponseCode(207)
                    .setBody("""<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:collection/></d:prop></d:propstat></d:response>
  <d:response><d:href>/dav/Kari%20Tomo%20300%25.zip</d:href><d:propstat><d:prop><d:getcontentlength>1234</d:getcontentlength></d:prop></d:propstat></d:response>
  <d:response><d:href>/dav/120%25%20discount.cbz</d:href><d:propstat><d:prop><d:getcontentlength>5678</d:getcontentlength></d:prop></d:propstat></d:response>
</d:multistatus>""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        try {
            val config = NetworkSourceConfig(
                id = "network-dav-percent-test",
                protocol = NetworkProtocol.WEBDAV,
                name = "DAV-Percent",
                url = server.url("/dav/").toString(),
                username = "me",
                password = "pw",
            ).validated()
            val fs = WebDavFileSystem(config)

            val list = fs.list("")
            assertThat(list).hasSize(2)
            assertThat(list.map { it.name }).containsExactly(
                "Kari Tomo 300%.zip",
                "120% discount.cbz",
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun ownsPerInstanceConnectionPoolAndDispatcher() {
        val config = NetworkSourceConfig(
            id = "network-dav-pool-test",
            protocol = NetworkProtocol.WEBDAV,
            name = "DAV-Pool",
            url = "http://127.0.0.1:1/dav/",
            username = "me",
            password = "pw",
        ).validated()

        val first = WebDavFileSystem(config)
        val second = WebDavFileSystem(config)

        // 소스별로 취소/커넥션을 격리할 수 있도록 풀과 디스패처를 공유하지 않는다.
        assertThat(first.connectionPool).isNotSameInstanceAs(second.connectionPool)
        assertThat(first.dispatcher).isNotSameInstanceAs(second.dispatcher)
        // 개인 NAS 세션 한도를 넘지 않도록 보수적으로 조정된 값.
        assertThat(first.dispatcher.maxRequests).isEqualTo(16)
        assertThat(first.dispatcher.maxRequestsPerHost).isEqualTo(8)
    }

    @Test
    fun disconnectEvictsIdleConnectionsAndReconnectsTransparently() {
        val data = "abcdef".toByteArray()
        val server = bookServer(data)
        server.start()
        try {
            val fs = WebDavFileSystem(configFor(server, "network-dav-disconnect-test"))

            assertThat(fs.list("")).containsExactly(
                NetworkNode("book.cbz", "book.cbz", false, FileRevision(6L, 0L)),
            )
            // 읽고 나면 커넥션이 유휴 상태로 풀에 남아 있다.
            assertThat(fs.connectionPool.connectionCount()).isEqualTo(1)

            fs.disconnect()
            // 유휴 커넥션은 즉시 닫혀 NAS 세션이 반납된다.
            assertThat(fs.connectionPool.connectionCount()).isEqualTo(0)
            assertThat(fs.dispatcher.runningCallsCount()).isEqualTo(0)

            // disconnect는 영구 종료가 아니므로 다음 접근은 새 연결로 투명하게 재연결된다.
            assertThat(fs.list("")).containsExactly(
                NetworkNode("book.cbz", "book.cbz", false, FileRevision(6L, 0L)),
            )
            assertThat(fs.connectionPool.connectionCount()).isEqualTo(1)

            fs.openFile("book.cbz").channel.use { channel ->
                channel.position(1)
                val target = ByteBuffer.allocate(2)
                assertThat(channel.read(target)).isEqualTo(2)
                assertThat(String(target.array())).isEqualTo("bc")
            }

            // 멱등: 반복 호출해도 예외 없이 유휴 커넥션만 정리한다.
            fs.disconnect()
            fs.disconnect()
            assertThat(fs.connectionPool.connectionCount()).isEqualTo(0)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun closeEvictsIdleConnections() {
        val server = bookServer("abcdef".toByteArray())
        server.start()
        try {
            val fs = WebDavFileSystem(configFor(server, "network-dav-close-test"))

            fs.list("")
            assertThat(fs.connectionPool.connectionCount()).isEqualTo(1)

            fs.close()
            assertThat(fs.connectionPool.connectionCount()).isEqualTo(0)
        } finally {
            server.shutdown()
        }
    }

    private fun configFor(server: MockWebServer, id: String) = NetworkSourceConfig(
        id = id,
        protocol = NetworkProtocol.WEBDAV,
        name = "DAV",
        url = server.url("/dav/").toString(),
        username = "me",
        password = "pw",
    ).validated()

    /** PROPFIND/HEAD/Range 요청을 모두 처리하는 가짜 WebDAV 서버. */
    private fun bookServer(data: ByteArray): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
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
    }

    companion object {
        private const val MULTISTATUS = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
  <d:response><d:href>/dav/book.cbz</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>6</d:getcontentlength></d:prop></d:propstat></d:response>
</d:multistatus>"""
    }
}
