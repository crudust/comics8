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

import com.comics8.core.source.local.PreviewImageResolver

class NetworkImageFetcher(
    private val url: String,
    private val options: Options,
    private val thumbs: CoverThumbCache,
) : Fetcher {
    override suspend fun fetch(): SourceResult = runInterruptible(Dispatchers.IO) {
        val previewSpec = PreviewImageResolver.resolve(url)
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
                val tempFile = java.io.File(cacheFile.parentFile, "${cacheFile.name}.tmp.${java.util.UUID.randomUUID()}")
                val bytes = try {
                    NetworkSourceRuntime.open(url).use { input ->
                        input.readBytes()
                    }.also { data ->
                        tempFile.outputStream().use { out ->
                            out.write(data)
                        }
                        if (tempFile.exists() && tempFile.length() > 0L) {
                            tempFile.renameTo(cacheFile)
                        }
                    }
                } catch (e: Exception) {
                    tempFile.delete()
                    throw e
                } finally {
                    tempFile.delete()
                }
                val buffer = Buffer()
                buffer.write(bytes)
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


