package com.comics8.core.source.network

import com.comics8.core.source.FileRevision
import com.comics8.core.source.local.ZipArchive
import com.comics8.core.source.local.NaturalSort
import com.comics8.core.source.local.ZipImageNames
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.ConcurrentHashMap

object NetworkSourceRuntime {
    private val backends = ConcurrentHashMap<String, NetworkFileSystem>()

    fun register(sourceId: String, backend: NetworkFileSystem) {
        backends.put(sourceId, backend)?.takeIf { it !== backend }?.let { previous ->
            NetworkZipPool.removeForSource(sourceId)
            runCatching { previous.close() }
        }
    }

    fun remove(sourceId: String) {
        backends.remove(sourceId)?.let { runCatching { it.close() } }
        NetworkZipPool.removeForSource(sourceId)
    }

    fun currentRevision(sourceId: String, path: String): FileRevision? =
        backends[sourceId]?.let { backend -> runCatching { backend.stat(path)?.revision }.getOrNull() }

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
        val bytes = readZipBytesWithRetry(ref, backend)
        return java.io.ByteArrayInputStream(bytes)
    }

    private fun readZipBytesWithRetry(ref: NetworkImageUri.Ref, backend: NetworkFileSystem): ByteArray {
        val isFirst = ref.preview == NetworkImageUri.PreviewKind.ZIP_FIRST
        val entryName = ref.zipEntry.orEmpty()
        return try {
            NetworkZipPool.withSession(ref.sourceId, ref.path, backend, ref.revision) { session ->
                if (isFirst) session.firstImageBytes() else session.readEntry(entryName)
            }
        } catch (firstError: Exception) {
            // 실패 시 캐시된 세션을 무효화하고 1회 재시도
            NetworkZipPool.invalidate(ref.sourceId, ref.path)
            NetworkZipPool.withSession(ref.sourceId, ref.path, backend, ref.revision) { session ->
                if (isFirst) session.firstImageBytes() else session.readEntry(entryName)
            }
        }
    }

    fun zipImageEntries(sourceId: String, path: String): NetworkArchiveIndex {
        val backend = backends[sourceId] ?: throw IOException("등록되지 않은 네트워크 저장소입니다")
        return try {
            NetworkZipPool.withSession(sourceId, path, backend) { session ->
                NetworkArchiveIndex(session.listImageEntries(), session.revision)
            }
        } catch (firstError: Exception) {
            NetworkZipPool.invalidate(sourceId, path)
            NetworkZipPool.withSession(sourceId, path, backend) { session ->
                NetworkArchiveIndex(session.listImageEntries(), session.revision)
            }
        }
    }
}

data class NetworkArchiveIndex(
    val entries: List<String>,
    val revision: FileRevision,
)

internal class NetworkZipSession(
    val revision: FileRevision,
    private val channel: SeekableByteChannel,
    private val zip: ZipFile,
) : AutoCloseable {
    private val lock = Any()
    private val lifecycleLock = Any()
    private var users = 0
    private var retired = false
    private var closed = false
    private var cachedImageEntries: List<String>? = null
    @Volatile var lastAccess: Long = System.currentTimeMillis()

    fun acquire(): Boolean = synchronized(lifecycleLock) {
        if (retired) return false
        users++
        true
    }

    fun release() {
        val shouldClose = synchronized(lifecycleLock) {
            users--
            (retired && users == 0 && !closed).also { if (it) closed = true }
        }
        if (shouldClose) closeResources()
    }

    fun retire() {
        val shouldClose = synchronized(lifecycleLock) {
            retired = true
            (users == 0 && !closed).also { if (it) closed = true }
        }
        if (shouldClose) closeResources()
    }

    fun readEntry(entryName: String): ByteArray = synchronized(lock) {
        lastAccess = System.currentTimeMillis()
        val entry = findEntry(entryName) ?: throw IOException("ZIP 항목을 찾을 수 없습니다: $entryName")
        if (entry.size > ZipArchive.MAX_ENTRY_BYTES) throw IOException("ZIP 이미지가 너무 큽니다")
        zip.getInputStream(entry).use { stream ->
            stream.readBytes()
        }
    }

    fun firstImageBytes(): ByteArray = synchronized(lock) {
        lastAccess = System.currentTimeMillis()
        val first = listImageEntries().firstOrNull() ?: throw IOException("ZIP에 이미지가 없습니다")
        readEntry(first)
    }

    fun listImageEntries(): List<String> = synchronized(lock) {
        lastAccess = System.currentTimeMillis()
        cachedImageEntries?.let { return it }
        val entries = zip.entries
        val imageNames = ArrayList<String>()
        while (entries.hasMoreElements()) {
            val candidate = entries.nextElement()
            if (candidate.isDirectory) continue
            val name = ZipArchive.tryNormalizeZipEntry(candidate.name) ?: continue
            if (ZipImageNames.isImageEntry(name)) {
                imageNames += name
            }
        }
        imageNames.sortWith(NaturalSort)
        imageNames.toList().also { cachedImageEntries = it }
    }

    private fun findEntry(requested: String): org.apache.commons.compress.archivers.zip.ZipArchiveEntry? {
        val normalized = ZipArchive.tryNormalizeZipEntry(requested) ?: requested
        zip.getEntry(requested)?.let { return it }
        zip.getEntry(normalized)?.let { return it }
        val entries = zip.entries
        while (entries.hasMoreElements()) {
            val candidate = entries.nextElement()
            if (candidate.isDirectory) continue
            val name = ZipArchive.tryNormalizeZipEntry(candidate.name) ?: candidate.name
            if (name == normalized || name == requested) return candidate
        }
        return null
    }

    override fun close() = retire()

    private fun closeResources() {
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
    private data class Key(val sourceId: String, val path: String, val revision: FileRevision)
    private data class PathKey(val sourceId: String, val path: String)

    private val sessions = LinkedHashMap<Key, NetworkZipSession>(MAX_SESSIONS, 0.75f, true)
    private val creationLocks = Array(16) { Any() }

    fun <T> withSession(
        sourceId: String,
        path: String,
        backend: NetworkFileSystem,
        revisionHint: FileRevision = FileRevision.UNKNOWN,
        block: (NetworkZipSession) -> T,
    ): T {
        while (true) {
            val session = getOrCreate(sourceId, path, backend, revisionHint)
            if (!session.acquire()) continue
            try {
                return block(session)
            } finally {
                session.release()
            }
        }
    }

    private fun getOrCreate(
        sourceId: String,
        path: String,
        backend: NetworkFileSystem,
        revisionHint: FileRevision,
    ): NetworkZipSession {
        val observedRevision = revisionHint.takeIf { it != FileRevision.UNKNOWN }
            ?: runCatching { backend.stat(path)?.revision }.getOrNull()
        if (observedRevision != null) {
            cached(Key(sourceId, path, observedRevision))?.let { return it }
        }

        val pathKey = PathKey(sourceId, path)
        val creationLock = creationLocks[(pathKey.hashCode() and Int.MAX_VALUE) % creationLocks.size]
        return synchronized(creationLock) create@{
            val currentRevision = revisionHint.takeIf { it != FileRevision.UNKNOWN }
                ?: runCatching { backend.stat(path)?.revision }.getOrNull()
            if (currentRevision != null) {
                cached(Key(sourceId, path, currentRevision))?.let { return@create it }
            }

            val opened = backend.openFile(path)
            val channel = opened.channel
            val key = Key(sourceId, path, opened.revision)
            cached(key)?.let { existing ->
                channel.close()
                return@create existing
            }

            val zip = try {
                ZipFile.builder().setSeekableByteChannel(channel).get()
            } catch (e: Exception) {
                channel.close()
                throw e
            }
            val created = NetworkZipSession(opened.revision, channel, zip)
            val retired = ArrayList<NetworkZipSession>()
            val selected = synchronized(lock) {
                sessions[key]?.also { existing ->
                    retired += created
                    existing.lastAccess = System.currentTimeMillis()
                } ?: run {
                    retired += removeExpiredLocked()
                    while (sessions.size >= MAX_SESSIONS) {
                        val oldest = sessions.keys.firstOrNull() ?: break
                        sessions.remove(oldest)?.let(retired::add)
                    }
                    sessions[key] = created
                    created
                }
            }
            retired.forEach(NetworkZipSession::retire)
            selected
        }
    }

    private fun cached(key: Key): NetworkZipSession? {
        val now = System.currentTimeMillis()
        var expired: NetworkZipSession? = null
        val cached = synchronized(lock) {
            val session = sessions[key] ?: return@synchronized null
            if (now - session.lastAccess > TTL_MS) {
                sessions.remove(key)
                expired = session
                null
            } else {
                session.lastAccess = now
                session
            }
        }
        expired?.retire()
        return cached
    }

    fun invalidate(sourceId: String, path: String) {
        val retired = synchronized(lock) {
            val removed = ArrayList<NetworkZipSession>()
            val iterator = sessions.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.sourceId == sourceId && entry.key.path == path) {
                    removed += entry.value
                    iterator.remove()
                }
            }
            removed
        }
        retired.forEach(NetworkZipSession::retire)
    }

    fun removeForSource(sourceId: String) {
        val retired = synchronized(lock) {
            val removed = ArrayList<NetworkZipSession>()
            val iterator = sessions.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.sourceId == sourceId) {
                    removed += entry.value
                    iterator.remove()
                }
            }
            removed
        }
        retired.forEach(NetworkZipSession::retire)
    }

    fun clear() {
        val retired = synchronized(lock) {
            sessions.values.toList().also { sessions.clear() }
        }
        retired.forEach(NetworkZipSession::retire)
    }

    private fun removeExpiredLocked(): List<NetworkZipSession> {
        val now = System.currentTimeMillis()
        val removed = ArrayList<NetworkZipSession>()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastAccess > TTL_MS) {
                removed += entry.value
                iterator.remove()
            }
        }
        return removed
    }
}
