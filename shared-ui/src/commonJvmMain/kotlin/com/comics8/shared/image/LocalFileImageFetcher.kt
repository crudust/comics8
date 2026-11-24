package com.comics8.shared.image

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.comics8.core.source.LocalImageUri
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Fetches `file:` URIs including raw unencoded paths that contain colons (e.g. `artist:name/001.jpg`)
 * via [LocalImageUri.toFile], which standard URI file fetchers can misparse.
 */
class LocalFileImageFetcher(
    private val file: File,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult =
        SourceFetchResult(
            source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
            mimeType = ZipImageFetcher.mimeType(file.name),
            dataSource = DataSource.DISK,
        )

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val raw = data.toString()
            if (!raw.startsWith("file:", ignoreCase = true)) return null
            val file = LocalImageUri.toFile(raw) ?: return null
            if (!file.isFile) return null
            return LocalFileImageFetcher(file)
        }
    }
}
