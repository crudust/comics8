package com.comics8.core.source.network

import com.comics8.core.source.FileRevision
import okhttp3.Credentials
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
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

class WebDavFileSystem(
    private val config: NetworkSourceConfig,
    private val client: OkHttpClient = OkHttpClient(),
) : NetworkFileSystem {
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
        val revision = readRevision(url)
        val response = client.newCall(
            requestBuilder(url).withRevisionCondition(revision).get().build(),
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
                super.close()
                response.close()
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

    private fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (config.username.isNotBlank()) {
            builder.header("Authorization", Credentials.basic(config.username, config.password))
        }
        return builder
    }

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
                val hrefPath = runCatching { URI(href).path }.getOrNull() ?: href
                if (hrefPath.trimEnd('/') == expected) continue
                val decoded = URLDecoder.decode(hrefPath.trimEnd('/').substringAfterLast('/'), StandardCharsets.UTF_8)
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
    ) : ReadOnlySeekableChannel() {
        private val length = revision.sizeBytes
        private var blockStart = -1L
        private var block = ByteArray(0)

        override fun size(): Long = synchronized(lock) { length }

        override fun read(dst: ByteBuffer): Int = synchronized(lock) {
            check(open) { "channel closed" }
            if (cursor >= length) return -1
            var total = 0
            while (dst.hasRemaining() && cursor < length) {
                val wantedStart = cursor / BLOCK_SIZE * BLOCK_SIZE
                if (blockStart != wantedStart) loadBlock(wantedStart)
                val offset = (cursor - blockStart).toInt()
                if (offset !in block.indices) {
                    if (total == 0) throw IOException("WebDAV 블록 읽기 실패 (위치: $cursor, 블록시작: $blockStart)")
                    break
                }
                val count = minOf(
                    dst.remaining().toLong(),
                    (block.size - offset).toLong(),
                    length - cursor,
                ).toInt()
                if (count <= 0) break
                dst.put(block, offset, count)
                cursor += count
                total += count
            }
            return if (total == 0 && cursor >= length) -1 else total
        }

        private fun loadBlock(start: Long) {
            val end = minOf(length - 1, start + BLOCK_SIZE - 1)
            val builder = requestBuilder(url)
                .header("Range", "bytes=$start-$end")
                .withRevisionCondition(revision)
            val request = builder.get().build()
            client.newCall(request).execute().use { response ->
                if (response.code == 412) {
                    throw IOException("WebDAV 파일이 읽는 도중 변경되었습니다")
                }
                if (response.code != 206) {
                    throw IOException("WebDAV 서버가 바이트 범위 읽기를 지원하지 않습니다 (HTTP ${response.code})")
                }
                block = response.body?.bytes() ?: throw IOException("WebDAV 범위 응답이 비어 있습니다")
                blockStart = start
            }
        }

        override fun close() {
            synchronized(lock) {
                open = false
                block = ByteArray(0)
            }
        }

        companion object {
            private const val BLOCK_SIZE = 512 * 1024L
        }
    }

    companion object {
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>"""
    }
}

private fun Request.Builder.withRevisionCondition(revision: FileRevision): Request.Builder = apply {
    if (revision.entityTag != null) {
        header("If-Match", revision.entityTag)
    } else if (revision.modifiedAtEpochMs > 0L) {
        val value = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            java.time.Instant.ofEpochMilli(revision.modifiedAtEpochMs).atZone(java.time.ZoneOffset.UTC),
        )
        header("If-Unmodified-Since", value)
    }
}
