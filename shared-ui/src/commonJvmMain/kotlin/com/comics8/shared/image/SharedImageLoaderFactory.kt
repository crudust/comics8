package com.comics8.shared.image

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.comics8.core.source.local.CoverThumbCache
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * Builds the shared Coil 3 [ImageLoader] used by both Android and Desktop.
 */
fun buildComicsImageLoader(
    context: PlatformContext,
    httpClient: OkHttpClient,
    coverThumbs: CoverThumbCache,
    readerCacheDir: File,
    diskCacheDir: File,
    diskCacheMaxBytes: Long,
    memoryCache: RolePartitionedMemoryCache,
    fallbacksProvider: (String) -> List<String>,
    extraComponents: ComponentRegistry.Builder.() -> Unit = {},
): ImageLoader {
    val diskCache = DiskCache.Builder()
        .directory(diskCacheDir.toOkioPath())
        .maxSizeBytes(diskCacheMaxBytes)
        .build()
    return ImageLoader.Builder(context)
        .components {
            add(FallbackImageInterceptor(fallbacksProvider) { diskCache })
            add(LocalFileImageFetcher.Factory())
            add(ZipImageFetcher.Factory())
            add(NetworkImageFetcher.Factory(coverThumbs, readerCacheDir))
            add(OkHttpNetworkFetcherFactory(httpClient))
            extraComponents()
        }
        .memoryCache { memoryCache }
        .diskCache { diskCache }
        .build()
}
