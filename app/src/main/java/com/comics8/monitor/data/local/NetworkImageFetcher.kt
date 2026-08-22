package com.comics8.monitor.data.local

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.comics8.core.source.network.NetworkImageUri
import com.comics8.core.source.network.NetworkSourceRuntime
import com.comics8.core.source.local.CoverThumbCache
import com.comics8.core.source.local.LocalPreviewUri
import com.comics8.core.source.local.ThumbKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okio.Buffer
import okio.source
import java.util.concurrent.Semaphore

import okio.ByteString.Companion.encodeUtf8

class NetworkImageFetcher(
    private val url: String,
    private val options: Options,
    private val thumbs: CoverThumbCache,
) : Fetcher {
    override suspend fun fetch(): SourceResult = runInterruptible(Dispatchers.IO) {
        val localRef = LocalPreviewUri.parse(url)
        val ref = NetworkImageUri.parse(url)
        require(localRef != null || ref != null) { "invalid preview uri" }
        val preview = localRef != null || ref?.preview != null
        if (preview) {
            val thumbnailPx = localRef?.thumbnailPx ?: ref!!.thumbnailPx.takeIf { it > 0 } ?: 320
            val key = if (localRef != null) {
                ThumbKey("local|${localRef.path}|${localRef.kind}", localRef.modifiedAt, localRef.size)
            } else {
                ThumbKey("${ref!!.sourceId}|${ref.path}|${ref.preview}", ref.modifiedAt, ref.size)
            }
            PREVIEW_SLOTS.acquire()
            val file = try {
                thumbs.getOrCreate(key, thumbnailPx) {
                    if (localRef != null) LocalPreviewUri.open(localRef).use { it.readBytes() }
                    else NetworkSourceRuntime.open(url).use { it.readBytes() }
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
            val cacheFile = getReaderCacheFile(options.context, url)
            if (cacheFile.isFile && cacheFile.length() > 0L) {
                cacheFile.setLastModified(System.currentTimeMillis())
                val buffer = Buffer()
                cacheFile.source().use { buffer.writeAll(it) }
                SourceResult(
                    source = ImageSource(source = buffer, context = options.context),
                    mimeType = ZipImageFetcher.mimeType(ref!!.zipEntry ?: ref.path),
                    dataSource = DataSource.DISK,
                )
            } else {
                val tempFile = java.io.File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                try {
                    NetworkSourceRuntime.open(url).use { input ->
                        tempFile.outputStream().use { out ->
                            input.copyTo(out)
                        }
                    }
                    if (tempFile.exists() && tempFile.length() > 0L) {
                        tempFile.renameTo(cacheFile)
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    throw e
                }
                val buffer = Buffer()
                cacheFile.source().use { buffer.writeAll(it) }
                SourceResult(
                    source = ImageSource(source = buffer, context = options.context),
                    mimeType = ZipImageFetcher.mimeType(ref!!.zipEntry ?: ref.path),
                    dataSource = DataSource.NETWORK,
                )
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
        val PREVIEW_SLOTS = Semaphore(8, true)

        fun getReaderCacheFile(context: android.content.Context, url: String): java.io.File {
            val dir = java.io.File(context.cacheDir, "network_reader").apply { mkdirs() }
            val hash = url.encodeUtf8().sha256().hex()
            return java.io.File(dir, "$hash.bin")
        }
    }
}


