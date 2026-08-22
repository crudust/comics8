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
import com.comics8.core.source.local.ThumbKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.source

class NetworkImageFetcher(
    private val url: String,
    private val options: Options,
    private val thumbs: CoverThumbCache,
) : Fetcher {
    override suspend fun fetch(): SourceResult = withContext(Dispatchers.IO) {
        val ref = NetworkImageUri.parse(url) ?: error("invalid network image uri")
        val buffer = Buffer()
        val preview = ref.preview != null
        if (preview) {
            val key = ThumbKey("${ref.sourceId}|${ref.path}|${ref.preview}", ref.modifiedAt, ref.size)
            val file = thumbs.getOrCreate(key) {
                NetworkSourceRuntime.open(url).use { it.readBytes() }
            }
            file.source().use { buffer.writeAll(it) }
        } else {
            NetworkSourceRuntime.open(url).use { input ->
                input.source().use { buffer.writeAll(it) }
            }
        }
        SourceResult(
            source = ImageSource(source = buffer, context = options.context),
            mimeType = if (preview) "image/webp" else ZipImageFetcher.mimeType(ref.zipEntry ?: ref.path),
            dataSource = if (preview) DataSource.DISK else DataSource.NETWORK,
        )
    }

    class Factory(private val thumbs: CoverThumbCache) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!NetworkImageUri.SCHEME.equals(data.scheme, ignoreCase = true)) return null
            val url = data.toString()
            if (NetworkImageUri.parse(url) == null) return null
            return NetworkImageFetcher(url, options, thumbs)
        }
    }
}
