package com.comics8.shared.image

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.comics8.core.source.local.ComicArchive
import com.comics8.core.source.local.ZipImageUri
import okio.Buffer
import okio.FileSystem
import okio.source

class ZipImageFetcher(
    private val url: String,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult {
        val ref = ZipImageUri.parse(url) ?: error("invalid zip image uri")
        val buffer = Buffer()
        ComicArchive.open(ref.zip).use { archive ->
            archive.open(ref.entry).use { input ->
                input.source().use { buffer.writeAll(it) }
            }
        }
        return SourceFetchResult(
            source = ImageSource(buffer, FileSystem.SYSTEM),
            mimeType = mimeType(ref.entry),
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val url = data.toString()
            if (!url.startsWith("${ZipImageUri.SCHEME}:", ignoreCase = true)) return null
            if (ZipImageUri.parse(url) == null) return null
            return ZipImageFetcher(url)
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
