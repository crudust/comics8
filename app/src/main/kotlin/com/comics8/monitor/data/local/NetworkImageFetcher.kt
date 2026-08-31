package com.comics8.monitor.data.local

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.comics8.core.source.local.CoverThumbCache
import com.comics8.core.source.local.LocalPreviewUri
import com.comics8.core.source.local.PreviewImageResolver
import com.comics8.core.source.network.NetworkImageUri
import com.comics8.core.source.network.NetworkSourceRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
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
) : Fetcher {
    override suspend fun fetch(): SourceResult {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        return runInterruptible(Dispatchers.IO) {
            val previewSpec = PreviewImageResolver.resolve(url, refreshNetworkRevision = true)
            val ref = NetworkImageUri.parse(url)
            if (previewSpec != null) {
                PREVIEW_SLOTS.acquire()
                val file = try {
                    thumbs.getOrCreate(previewSpec.key, previewSpec.thumbnailPx) {
                        previewSpec.readSourceBytes()
                    }
                } finally {
                    PREVIEW_SLOTS.release()
                }
                val buffer = Buffer()
                file.source().use { buffer.writeAll(it) }
                SourceResult(
                    source = ImageSource(source = buffer, context = options.context),
                    mimeType = "image/webp",
                    dataSource = DataSource.DISK,
                )
            } else {
                requireNotNull(ref) { "invalid network uri" }
                val cacheFile = getReaderCacheFile(options.context, url)
                val cacheLock = READER_LOCKS[(cacheFile.name.hashCode() and Int.MAX_VALUE) % READER_LOCKS.size]
                cacheLock.lockInterruptibly()
                try {
                    if (cacheFile.isFile && cacheFile.length() > 0L) {
                        cacheFile.setLastModified(System.currentTimeMillis())
                        val buffer = Buffer()
                        cacheFile.source().use { buffer.writeAll(it) }
                        SourceResult(
                            source = ImageSource(source = buffer, context = options.context),
                            mimeType = ZipImageFetcher.mimeType(ref.zipEntry ?: ref.path),
                            dataSource = DataSource.DISK,
                        )
                    } else {
                        val bytes = NetworkSourceRuntime.open(url).use { input -> input.readBytes() }
                        if (bytes.isNotEmpty()) {
                            writeReaderCache(cacheFile, bytes)
                            evictReaderCache(cacheFile)
                        }
                        val buffer = Buffer()
                        buffer.write(bytes)
                        SourceResult(
                            source = ImageSource(source = buffer, context = options.context),
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

    class Factory(private val thumbs: CoverThumbCache) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!NetworkImageUri.SCHEME.equals(data.scheme, ignoreCase = true) &&
                !LocalPreviewUri.SCHEME.equals(data.scheme, ignoreCase = true)
            ) return null
            val url = data.toString()
            if (NetworkImageUri.parse(url) == null && LocalPreviewUri.parse(url) == null) return null
            return NetworkImageFetcher(url, options, thumbs)
        }
    }

    private companion object {
        const val READER_CACHE_HARD_LIMIT_BYTES = 200L * 1024L * 1024L
        const val READER_CACHE_TARGET_BYTES = 160L * 1024L * 1024L
        val PREVIEW_SLOTS = Semaphore(4, true)
        val READER_LOCKS = Array(16) { ReentrantLock() }
        val READER_EVICTION_LOCK = Any()
        val estimatedReaderCacheBytes = AtomicLong(-1L)

        fun getReaderCacheFile(context: android.content.Context, url: String): File {
            val dir = File(context.cacheDir, "network_reader").apply { mkdirs() }
            estimatedReaderCacheBytes.compareAndSet(-1L, directorySize(dir))
            val hash = url.encodeUtf8().sha256().hex()
            return File(dir, "$hash.bin")
        }

        fun writeReaderCache(target: File, bytes: ByteArray) {
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
                estimatedReaderCacheBytes.addAndGet(target.length() - previousSize)
            } finally {
                temp.delete()
            }
        }

        fun evictReaderCache(keep: File) = synchronized(READER_EVICTION_LOCK) {
            if (estimatedReaderCacheBytes.get() <= READER_CACHE_HARD_LIMIT_BYTES) return
            val files = keep.parentFile?.listFiles()?.filter { it.isFile && it.extension == "bin" } ?: return
            var total = files.sumOf(File::length)
            if (total > READER_CACHE_HARD_LIMIT_BYTES) {
                for (file in files.filter { it.absolutePath != keep.absolutePath }.sortedBy(File::lastModified)) {
                    if (total <= READER_CACHE_TARGET_BYTES) break
                    val size = file.length()
                    if (file.delete()) total -= size
                }
            }
            estimatedReaderCacheBytes.set(total)
        }

        fun directorySize(dir: File): Long =
            dir.listFiles()?.asSequence()?.filter(File::isFile)?.sumOf(File::length) ?: 0L
    }
}
