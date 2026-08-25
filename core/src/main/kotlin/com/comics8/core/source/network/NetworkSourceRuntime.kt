package com.comics8.core.source.network

import com.comics8.core.source.local.ZipArchive
import com.comics8.core.source.local.NaturalSort
import com.comics8.core.source.local.ZipImageNames
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

object NetworkSourceRuntime {
    private val backends = ConcurrentHashMap<String, NetworkFileSystem>()

    fun register(sourceId: String, backend: NetworkFileSystem) {
        backends.put(sourceId, backend)?.let { runCatching { it.close() } }
    }

    fun remove(sourceId: String) {
        backends.remove(sourceId)?.let { runCatching { it.close() } }
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
        val entryName = ref.zipEntry.orEmpty()
        val channel = backend.openChannel(ref.path, ref.size)
        val zip = try {
            ZipFile.builder().setSeekableByteChannel(channel).get()
        } catch (e: Exception) {
            channel.close()
            throw e
        }
        val normalized = if (ref.preview == NetworkImageUri.PreviewKind.ZIP_FIRST) {
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
            imageNames.firstOrNull() ?: run {
                zip.close()
                throw IOException("ZIP에 이미지가 없습니다")
            }
        } else {
            ZipArchive.normalizeZipEntry(entryName)
        }
        val entry = zip.getEntry(normalized) ?: run {
            zip.close()
            throw IOException("ZIP 항목을 찾을 수 없습니다: $normalized")
        }
        if (entry.size > ZipArchive.MAX_ENTRY_BYTES) {
            zip.close()
            throw IOException("ZIP 이미지가 너무 큽니다")
        }
        val stream = zip.getInputStream(entry)
        return object : FilterInputStream(stream) {
            private var seen = 0L
            override fun read(): Int {
                val value = super.read()
                if (value >= 0) account(1)
                return value
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count > 0) account(count.toLong())
                return count
            }
            private fun account(count: Long) {
                seen += count
                if (seen > ZipArchive.MAX_ENTRY_BYTES) throw IOException("ZIP 이미지가 너무 큽니다")
            }
            override fun close() {
                runCatching { super.close() }
                zip.close()
            }
        }
    }
}
