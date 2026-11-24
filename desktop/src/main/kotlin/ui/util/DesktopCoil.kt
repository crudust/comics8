package com.comics8.desktop.ui.util

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Size
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.image.ImageMemoryPolicy
import com.comics8.core.network.ImageFallbacks
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.local.CoverThumbCache
import com.comics8.shared.image.READER_LONG_EDGE_PX
import com.comics8.shared.image.READER_SHORT_EDGE_PX
import com.comics8.shared.image.ReaderCropRectCache
import com.comics8.shared.image.RolePartitionedMemoryCache
import com.comics8.shared.image.SkiaAvifCoilDecoder
import com.comics8.shared.image.SkiaReaderTileDecoder
import com.comics8.shared.image.SkiaThumbEncoder
import com.comics8.shared.image.THUMBNAIL_MAX_EDGE_PX
import com.comics8.shared.image.buildComicsImageLoader
import com.comics8.shared.image.imageCacheRole
import com.comics8.shared.image.readerImageSizing
import com.comics8.shared.image.readerTileIdentityPrefix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object DesktopCoil {
    private const val MEMORY_MAX_BYTES = 160L * 1024L * 1024L
    private const val DISK_CACHE_MAX_BYTES = 400L * 1024L * 1024L
    private val cacheRoot = File(System.getProperty("user.home"), ".comics8/cache")
    private val singletonRegistered = AtomicBoolean(false)

    val coverThumbs = CoverThumbCache(
        File(System.getProperty("user.home"), ".comics8/thumbs"),
        SkiaThumbEncoder,
        maxSizeBytes = 300L * 1024L * 1024L,
        targetSizeBytes = 240L * 1024L * 1024L,
    )

    val memoryCache = RolePartitionedMemoryCache(ImageMemoryPolicy.partition(MEMORY_MAX_BYTES))

    @Volatile
    private var defaultHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var registry: SourceRegistry? = null

    @Volatile
    var imageLoader: ImageLoader = createImageLoader(
        httpClient = defaultHttpClient,
        registry = SourceRegistry(),
    )
        private set

    fun createImageLoader(
        httpClient: OkHttpClient,
        registry: SourceRegistry,
        readerCacheDir: File = File(cacheRoot, "network_reader"),
        diskCacheDir: File = File(cacheRoot, "coil"),
        diskCacheMaxBytes: Long = DISK_CACHE_MAX_BYTES,
        memoryCache: RolePartitionedMemoryCache = this.memoryCache,
        coverThumbs: CoverThumbCache = this.coverThumbs,
    ): ImageLoader =
        buildComicsImageLoader(
            context = PlatformContext.INSTANCE,
            httpClient = httpClient,
            coverThumbs = coverThumbs,
            readerCacheDir = readerCacheDir,
            diskCacheDir = diskCacheDir,
            diskCacheMaxBytes = diskCacheMaxBytes,
            memoryCache = memoryCache,
            fallbacksProvider = { url -> ImageFallbacks.forUrl(url, registry) },
            extraComponents = {
                add(SkiaReaderTileDecoder.Factory())
                add(SkiaAvifCoilDecoder.Factory())
            },
        )

    fun configure(httpClient: OkHttpClient, registry: SourceRegistry) {
        this.defaultHttpClient = httpClient
        this.registry = registry
        val previous = this.imageLoader
        this.imageLoader = createImageLoader(httpClient = httpClient, registry = registry)
        if (singletonRegistered.compareAndSet(false, true)) {
            SingletonImageLoader.setSafe { this.imageLoader }
        } else {
            previous.shutdown()
        }
    }

    fun clear(role: ImageCacheRole) {
        memoryCache.clear(role)
        if (role == ImageCacheRole.READER) {
            ReaderCropRectCache.clear()
            System.gc()
        }
    }

    fun trimMemory() {
        clear(ImageCacheRole.READER)
    }

    fun evict(role: ImageCacheRole, url: String) {
        if (url.isBlank()) return
        memoryCache.remove(role, url)
        if (role == ImageCacheRole.READER) {
            memoryCache.removeMatching(role, readerTileIdentityPrefix(url))
        }
    }

    fun clearFailures() {
        coverThumbs.clearFailures()
    }

    fun cancelPendingPreviews() = Unit

    suspend fun preload(
        role: ImageCacheRole,
        urls: List<String>,
        maxBitmapSize: Size = Size(READER_SHORT_EDGE_PX, READER_LONG_EDGE_PX),
    ) = withContext(Dispatchers.IO) {
        val loader = imageLoader
        for (url in urls.distinct()) {
            if (url.isBlank()) continue
            val request = ImageRequest.Builder(PlatformContext.INSTANCE)
                .data(url)
                .imageCacheRole(role, url)
                .apply {
                    if (role == ImageCacheRole.READER) {
                        readerImageSizing(maxBitmapSize)
                    } else {
                        maxBitmapSize(Size(THUMBNAIL_MAX_EDGE_PX, THUMBNAIL_MAX_EDGE_PX))
                    }
                }
                .build()
            loader.execute(request)
        }
    }

    fun close() {
        memoryCache.clear()
        ReaderCropRectCache.clear()
        imageLoader.shutdown()
    }
}
