package com.comics8.core.source.network

import com.comics8.core.source.local.ZipArchive
import com.comics8.core.source.local.NaturalSort
import com.comics8.core.source.local.ZipImageNames
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.ConcurrentHashMap

object NetworkSourceRuntime {
    private val backends = ConcurrentHashMap<String, NetworkFileSystem>()

    fun register(sourceId: String, backend: NetworkFileSystem) {
        backends.put(sourceId, backend)?.let { runCatching { it.close() } }
    }

    fun remove(sourceId: String) {
        backends.remove(sourceId)?.let { runCatching { it.close() } }
        NetworkZipPool.removeForSource(sourceId)
    }

    fun open(url: String): InputStream {
        val ref = NetworkImageUri.parse(url) ?: throw IOException("잘못된 네트워크 이미지 주소입니다")
        val backend = backends[ref.sourceId] ?: throw IOException("등록되지 않은 네트워크 저장소입니다")
        if (ref.preview == NetworkImageUri.PreviewKind.FOLDER_FIRST) {
            val image = backend.list(ref.path)
                .filter { !it.directory && ZipImageNames.isImageEntry(it.name) }
                .minWithOrNull(compareBy(NaturalSort) { it.name })
                ?: throw IOException("폴더에 이미지가 없습니다")
            return backend.open(image.path)
        }
        if (ref.preview != NetworkImageUri.PreviewKind.ZIP_FIRST && ref.zipEntry == null) {
            return backend.open(ref.path)
        }
        val session = NetworkZipPool.getOrCreate(ref.sourceId, ref.path, ref.size, backend)
        val entryName = if (ref.preview == NetworkImageUri.PreviewKind.ZIP_FIRST) {
            session.firstImageEntry()
        } else {
            ref.zipEntry.orEmpty()
        }
        val bytes = session.readEntry(entryName)
        return java.io.ByteArrayInputStream(bytes)
    }

    fun zipImageEntries(sourceId: String, path: String, size: Long = -1L): List<String> {
        val backend = backends[sourceId] ?: throw IOException("등록되지 않은 네트워크 저장소입니다")
        val session = NetworkZipPool.getOrCreate(sourceId, path, size, backend)
        return session.listImageEntries()
    }
}

internal class NetworkZipSession(
    val key: String,
    private val channel: SeekableByteChannel,
    private val zip: ZipFile,
) : AutoCloseable {
    private val lock = Any()
    @Volatile var lastAccess: Long = System.currentTimeMillis()

    fun readEntry(entryName: String): ByteArray = synchronized(lock) {
        lastAccess = System.currentTimeMillis()
        val normalized = ZipArchive.normalizeZipEntry(entryName)
        val entry = zip.getEntry(normalized) ?: throw IOException("ZIP 항목을 찾을 수 없습니다: $normalized")
        if (entry.size > ZipArchive.MAX_ENTRY_BYTES) throw IOException("ZIP 이미지가 너무 큽니다")
        zip.getInputStream(entry).use { stream ->
            stream.readBytes()
        }
    }

    fun firstImageEntry(): String = synchronized(lock) {
        lastAccess = System.currentTimeMillis()
        listImageEntries().firstOrNull() ?: throw IOException("ZIP에 이미지가 없습니다")
    }

    fun listImageEntries(): List<String> = synchronized(lock) {
        lastAccess = System.currentTimeMillis()
        val entries = zip.entries
        val imageNames = ArrayList<String>()
        while (entries.hasMoreElements()) {
            val candidate = entries.nextElement()
            if (candidate.isDirectory) continue
            val name = runCatching { ZipArchive.normalizeZipEntry(candidate.name) }.getOrNull() ?: continue
            if (ZipImageNames.isImageEntry(name)) {
                imageNames += name
            }
        }
        imageNames.sortWith(NaturalSort)
        imageNames
    }

    override fun close() {
        synchronized(lock) {
            runCatching { zip.close() }
            runCatching { channel.close() }
        }
    }
}

internal object NetworkZipPool {
    private const val MAX_SESSIONS = 8
    private const val TTL_MS = 2 * 60 * 1000L
    private val lock = Any()
    private val sessions = LinkedHashMap<String, NetworkZipSession>(MAX_SESSIONS, 0.75f, true)

    fun getOrCreate(sourceId: String, path: String, size: Long, backend: NetworkFileSystem): NetworkZipSession = synchronized(lock) {
        cleanupExpired()
        val key = "$sourceId|$path"
        sessions[key]?.let {
            it.lastAccess = System.currentTimeMillis()
            return it
        }
        while (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.keys.firstOrNull() ?: break
            sessions.remove(oldest)?.close()
        }
        val channel = backend.openChannel(path, size)
        val zip = try {
            ZipFile.builder().setSeekableByteChannel(channel).get()
        } catch (e: Exception) {
            channel.close()
            throw e
        }
        val session = NetworkZipSession(key, channel, zip)
        sessions[key] = session
        return session
    }

    fun removeForSource(sourceId: String) = synchronized(lock) {
        val prefix = "$sourceId|"
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.startsWith(prefix)) {
                entry.value.close()
                iterator.remove()
            }
        }
    }

    fun clear() = synchronized(lock) {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastAccess > TTL_MS) {
                entry.value.close()
                iterator.remove()
            }
        }
    }
}
