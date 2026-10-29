package com.comics8.core.source.network

import com.comics8.core.source.FileRevision
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class WebDavFileSystem(
    private val config: NetworkSourceConfig,
    client: OkHttpClient = BASE_CLIENT,
) : NetworkFileSystem {
    /**
     * 소스별 취소/커넥션 격리를 위해 풀과 디스패처는 인스턴스마다 따로 소유한다.
     * 개인 NAS(Nextcloud, Synology 등)의 동시 세션 한도를 넘지 않도록 보수적으로 잡는다.
     */
    internal val connectionPool = ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)
    internal val dispatcher = Dispatcher().apply {
        maxRequests = MAX_REQUESTS
        maxRequestsPerHost = MAX_REQUESTS_PER_HOST
    }

    private val authHandler = WebDavAuthHandler(config)
    private val client: OkHttpClient = client.newBuilder()
        .connectionPool(connectionPool)
        .dispatcher(dispatcher)
        .addInterceptor(authHandler)
        .authenticator(authHandler)
        .build()
    override fun list(path: String): List<NetworkNode> {
        val requestUrl = urlFor(path, directory = true)
        val request = requestBuilder(requestUrl)
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code !in setOf(200, 207)) throw httpError(response, "WebDAV 폴더를 읽을 수 없습니다")
            val bytes = response.body?.bytes() ?: throw IOException("WebDAV 응답이 비어 있습니다")
            return parseListing(bytes, path, URI(requestUrl).path)
        }
    }

    override fun open(path: String): InputStream {
        val url = urlFor(path)
        val response = client.newCall(
            requestBuilder(url).get().build(),
        ).execute()
        if (!response.isSuccessful) {
            val error = httpError(response, "WebDAV 파일을 열 수 없습니다")
            response.close()
            throw error
        }
        val stream = response.body?.byteStream() ?: run {
            response.close()
            throw IOException("WebDAV 응답이 비어 있습니다")
        }
        return object : FilterInputStream(stream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    response.close()
                }
            }
        }
    }

    override fun stat(path: String): NetworkNode? {
        val normalized = NetworkSourceConfig.normalizePath(path)
        val candidates = if (normalized.isEmpty()) {
            listOf(urlFor(path, directory = true))
        } else {
            listOf(urlFor(path), urlFor(path, directory = true)).distinct()
        }
        for (candidate in candidates) {
            val request = requestBuilder(candidate).head().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val revision = response.toRevision() ?: return@use
                return NetworkNode(
                    path = normalized,
                    name = path.substringAfterLast('/').ifBlank { config.name },
                    directory = response.request.url.encodedPath.endsWith('/'),
                    revision = revision,
                )
            }
        }
        return null
    }

    override fun openFile(path: String): OpenedNetworkFile {
        val url = urlFor(path)
        val revision = readRevision(url)
        require(revision.sizeBytes >= 0L) { "WebDAV 파일 크기를 확인할 수 없습니다" }
        return OpenedNetworkFile(
            WebDavChannel(client, ::requestBuilder, url, revision),
            revision,
        )
    }

    override fun test() {
        list("")
        val firstFile = list("").firstOrNull { !it.directory } ?: return
        openFile(firstFile.path).channel.use { channel ->
            if (channel.size() > 0L) channel.read(ByteBuffer.allocate(1))
        }
    }

    /**
     * 이전 화면에서 남은 백그라운드 Range/PROPFIND 요청을 취소하고 유휴 소켓을 닫아 NAS 세션을 반납한다.
     * OkHttp 클라이언트 자체는 파괴되지 않으므로 다음 [list], [stat], [openFile] 호출에서 투명하게 재연결한다.
     */
    override fun disconnect() {
        dispatcher.cancelAll()
        connectionPool.evictAll()
    }

    override fun close() = disconnect()

    private fun readRevision(url: String): FileRevision {
        val request = requestBuilder(url).head().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response, "WebDAV 파일 정보를 읽을 수 없습니다")
            return response.toRevision() ?: throw IOException("WebDAV 파일 버전을 확인할 수 없습니다")
        }
    }

    private fun Response.toRevision(): FileRevision? {
        val size = header("Content-Length")?.toLongOrNull() ?: -1L
        val modifiedAt = header("Last-Modified")
            ?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
            ?: 0L
        val entityTag = header("ETag")?.trim()?.takeIf(String::isNotEmpty)
        if (size < 0L && modifiedAt <= 0L && entityTag == null) return null
        return FileRevision(size, modifiedAt, entityTag)
    }

    private fun requestBuilder(url: String): Request.Builder = Request.Builder().url(url)

    private fun urlFor(path: String, directory: Boolean = false): String {
        val base = config.url.trimEnd('/')
        val encoded = NetworkSourceConfig.normalizePath(path).split('/').filter { it.isNotEmpty() }
            .joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
        val value = if (encoded.isEmpty()) "$base/" else "$base/$encoded"
        return if (directory && !value.endsWith('/')) "$value/" else value
    }

    private fun parseListing(bytes: ByteArray, parent: String, requestPath: String): List<NetworkNode> {
        if (String(bytes, StandardCharsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true)) {
            throw IOException("DOCTYPE이 포함된 WebDAV 응답은 허용되지 않습니다")
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        }
        val doc = factory.newDocumentBuilder().parse(bytes.inputStream())
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val expected = requestPath.trimEnd('/')
        return buildList {
            for (i in 0 until responses.length) {
                val element = responses.item(i) as? Element ?: continue
                val href = element.firstText("href") ?: continue
                val uri = runCatching { URI(href) }.getOrNull()
                val hrefPath = uri?.path ?: href
                if (hrefPath.trimEnd('/') == expected) continue
                val decoded = uri?.path?.trimEnd('/')?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                    ?: runCatching {
                        val rawSegment = (uri?.rawPath ?: href).trimEnd('/').substringAfterLast('/')
                        URLDecoder.decode(rawSegment, StandardCharsets.UTF_8)
                    }.getOrNull()
                    ?: (uri?.rawPath ?: href).trimEnd('/').substringAfterLast('/')
                if (decoded.isBlank()) continue
                val directory = element.getElementsByTagNameNS("DAV:", "collection").length > 0
                val size = element.firstText("getcontentlength")?.toLongOrNull() ?: 0L
                val modifiedAt = element.firstText("getlastmodified")
                    ?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
                    ?: 0L
                val entityTag = element.firstText("getetag")?.takeIf(String::isNotBlank)
                add(
                    NetworkNode(
                        joinNetworkPath(parent, decoded),
                        decoded,
                        directory,
                        FileRevision(size, modifiedAt, entityTag),
                    ),
                )
            }
        }.distinctBy { it.path }
    }

    private fun Element.firstText(localName: String): String? {
        val nodes = getElementsByTagNameNS("DAV:", localName)
        return if (nodes.length > 0) nodes.item(0).textContent?.trim() else null
    }

    private fun httpError(response: Response, prefix: String): IOException =
        IOException("$prefix (HTTP ${response.code})")

    private class WebDavChannel(
        private val client: OkHttpClient,
        private val requestBuilder: (String) -> Request.Builder,
        private val url: String,
        private val revision: FileRevision,
    ) : BufferedNetworkChannel(revision.sizeBytes) {

        override fun fetchRange(offset: Long, lengthToRead: Int): ByteArray {
            val end = offset + lengthToRead - 1
            val builder = requestBuilder(url)
                .header("Range", "bytes=$offset-$end")
                .withRevisionCondition(revision)
            val request = builder.get().build()
            client.newCall(request).execute().use { response ->
                if (response.code == 412) {
                    throw IOException("WebDAV 파일이 읽는 도중 변경되었습니다")
                }
                if (response.code != 206) {
                    throw IOException("WebDAV 서버가 바이트 범위 읽기를 지원하지 않습니다 (HTTP ${response.code})")
                }
                return response.body?.bytes() ?: throw IOException("WebDAV 범위 응답이 비어 있습니다")
            }
        }
    }

    companion object {
        private const val MAX_REQUESTS = 16
        private const val MAX_REQUESTS_PER_HOST = 8
        private const val MAX_IDLE_CONNECTIONS = 8
        private const val KEEP_ALIVE_MINUTES = 1L

        /** 기본 타임아웃 템플릿. 여기 딸린 풀/디스패처는 인스턴스가 만든 것으로 교체되어 쓰이지 않는다. */
        private val BASE_CLIENT: OkHttpClient = OkHttpClient.Builder().build()

        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>"""
    }
}

private fun Request.Builder.withRevisionCondition(revision: FileRevision): Request.Builder = apply {
    val tag = revision.entityTag
    if (tag != null && !tag.startsWith("W/", ignoreCase = true)) {
        header("If-Match", tag)
    } else if (revision.modifiedAtEpochMs > 0L) {
        val value = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(revision.modifiedAtEpochMs).atZone(java.time.ZoneOffset.UTC),
        )
        header("If-Unmodified-Since", value)
    }
}
