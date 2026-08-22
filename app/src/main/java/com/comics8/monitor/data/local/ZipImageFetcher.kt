package com.comics8.monitor.data.local

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.comics8.core.source.local.ZipArchive
import com.comics8.core.source.local.ZipImageUri
import okio.Buffer
import okio.source

class ZipImageFetcher(
    private val url: String,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): SourceResult {
        val ref = ZipImageUri.parse(url) ?: error("invalid zip image uri")
        val buffer = Buffer()
        ZipArchive(ref.zip).use { zip ->
            zip.open(ref.entry).use { input ->
                input.source().use { buffer.writeAll(it) }
            }
        }
        return SourceResult(
            source = ImageSource(source = buffer, context = options.context),
            mimeType = mimeType(ref.entry),
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!ZipImageUri.SCHEME.equals(data.scheme, ignoreCase = true)) return null
            val url = data.toString()
            if (ZipImageUri.parse(url) == null) return null
            return ZipImageFetcher(url, options)
        }
    }

    companion object {
        fun mimeType(entry: String): String? =
            when (entry.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "avif" -> "image/avif"
                else -> null
            }
    }
}
