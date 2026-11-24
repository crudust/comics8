package com.comics8.shared.image

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.CachePolicy
import coil3.request.Options
import com.comics8.core.source.local.CoverThumbCache
import com.comics8.core.source.local.LocalPreviewUri
import com.comics8.core.source.local.PreviewImageResolver
import com.comics8.core.source.network.NetworkImageUri
import com.comics8.core.source.network.NetworkSourceRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.source
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

class NetworkImageFetcher(
    private val url: String,
    private val options: Options,
    private val thumbs: CoverThumbCache,
    private val readerCacheDir: File,
    private val estimatedReaderCacheBytes: AtomicLong,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult {
        currentCoroutineContext().ensureActive()
        val forceRetry = options.diskCachePolicy == CachePolicy.WRITE_ONLY
        return runInterruptible(Dispatchers.IO) {
            val previewSpec = PreviewImageResolver.resolve(url, refreshNetworkRevision = true)
            val ref = NetworkImageUri.parse(url)
            if (previewSpec != null) {
                PREVIEW_SLOTS.acquire()
                val file = try {
                    thumbs.getOrCreate(previewSpec.key, previewSpec.thumbnailPx, forceRetry = forceRetry) {
                        previewSpec.readSourceBytes()
                    }
                } finally {
                    PREVIEW_SLOTS.release()
                }
                val buffer = Buffer()
                file.source().use { buffer.writeAll(it) }
                SourceFetchResult(
                    source = ImageSource(buffer, FileSystem.SYSTEM),
                    mimeType = "image/webp",
                    dataSource = DataSource.DISK,
                )
            } else {
                requireNotNull(ref) { "invalid network uri" }
                val cacheFile = getReaderCacheFile(readerCacheDir, url, estimatedReaderCacheBytes)
                val cacheLock = READER_LOCKS[(cacheFile.name.hashCode() and Int.MAX_VALUE) % READER_LOCKS.size]
                cacheLock.lockInterruptibly()
                try {
                    if (!forceRetry && cacheFile.isFile && cacheFile.length() > 0L) {
                        cacheFile.setLastModified(System.currentTimeMillis())
                        val buffer = Buffer()
                        cacheFile.source().use { buffer.writeAll(it) }
                        SourceFetchResult(
                            source = ImageSource(buffer, FileSystem.SYSTEM),
                            mimeType = ZipImageFetcher.mimeType(ref.zipEntry ?: ref.path),
                            dataSource = DataSource.DISK,
                        )
                    } else {
                        val bytes = NetworkSourceRuntime.open(url).use { input -> input.readBytes() }
                        if (bytes.isNotEmpty()) {
                            writeReaderCache(cacheFile, bytes, estimatedReaderCacheBytes)
                            evictReaderCache(cacheFile, estimatedReaderCacheBytes)
                        }
                        val buffer = Buffer()
                        buffer.write(bytes)
                        SourceFetchResult(
                            source = ImageSource(buffer, FileSystem.SYSTEM),
                            mimeType = ZipImageFetcher.mimeType(ref.zipEntry ?: ref.path),
                            dataSource = DataSource.NETWORK,
                        )
                    }
                } finally {
                    cacheLock.unlock()
                }
            }
        }
    }

    class Factory(
        private val thumbs: CoverThumbCache,
        private val readerCacheDir: File,
    ) : Fetcher.Factory<Uri> {
        private val estimatedReaderCacheBytes = AtomicLong(-1L)

        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val url = data.toString()
            if (!url.startsWith("${NetworkImageUri.SCHEME}:", ignoreCase = true) &&
                !url.startsWith("${LocalPreviewUri.SCHEME}:", ignoreCase = true)
            ) {
                return null
            }
            if (NetworkImageUri.parse(url) == null && LocalPreviewUri.parse(url) == null) return null
            return NetworkImageFetcher(url, options, thumbs, readerCacheDir, estimatedReaderCacheBytes)
        }
    }

    private companion object {
        const val READER_CACHE_HARD_LIMIT_BYTES = 200L * 1024L * 1024L
        const val READER_CACHE_TARGET_BYTES = 160L * 1024L * 1024L
        val PREVIEW_SLOTS = Semaphore(4, true)
        val READER_LOCKS = Array(16) { ReentrantLock() }
        val READER_EVICTION_LOCK = Any()

        fun getReaderCacheFile(dir: File, url: String, estimatedBytes: AtomicLong): File {
            dir.mkdirs()
            estimatedBytes.compareAndSet(-1L, directorySize(dir))
            val hash = url.encodeUtf8().sha256().hex()
            return File(dir, "$hash.bin")
        }

        fun writeReaderCache(target: File, bytes: ByteArray, estimatedBytes: AtomicLong) {
            val temp = File(target.parentFile, "${target.name}.tmp.${UUID.randomUUID()}")
            val previousSize = target.takeIf(File::isFile)?.length() ?: 0L
            try {
                temp.writeBytes(bytes)
                try {
                    Files.move(
                        temp.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: Exception) {
                    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                estimatedBytes.addAndGet(target.length() - previousSize)
            } finally {
                temp.delete()
            }
        }

        fun evictReaderCache(keep: File, estimatedBytes: AtomicLong) {
            synchronized(READER_EVICTION_LOCK) {
                if (estimatedBytes.get() <= READER_CACHE_HARD_LIMIT_BYTES) return
                val files = keep.parentFile?.listFiles()?.filter { it.isFile && it.extension == "bin" } ?: return
                var total = files.sumOf(File::length)
                if (total > READER_CACHE_HARD_LIMIT_BYTES) {
                    for (file in files.filter { it.absolutePath != keep.absolutePath }.sortedBy(File::lastModified)) {
                        if (total <= READER_CACHE_TARGET_BYTES) break
                        val size = file.length()
                        if (file.delete()) total -= size
                    }
                }
                estimatedBytes.set(total)
            }
        }

        fun directorySize(dir: File): Long =
            dir.listFiles()?.asSequence()?.filter(File::isFile)?.sumOf(File::length) ?: 0L
    }
}
