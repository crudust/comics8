package com.comics8.desktop.ui.util

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.comics8.core.model.CropRect
import com.comics8.core.model.ImageHalf
import com.comics8.core.model.computeContentCropRect
import com.comics8.core.network.ImageFallbacks
import com.comics8.core.network.ImageReferer
import com.comics8.core.source.LocalImageUri
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.local.ThumbEncoder
import com.comics8.core.source.local.CoverThumbCache
import com.comics8.core.source.local.LocalPreviewUri
import com.comics8.core.source.local.ThumbKey
import com.comics8.core.source.local.ZipArchive
import com.comics8.core.source.local.ZipImageUri
import com.comics8.core.source.network.NetworkImageUri
import com.comics8.core.source.network.NetworkSourceRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore

object AwtThumbEncoder : ThumbEncoder {
    override fun webp(bytes: ByteArray, longEdgePx: Int, quality: Int): ByteArray {
        val q = quality.coerceIn(1, 100)
        Image.makeFromEncoded(bytes).use { src ->
            val w = src.width.coerceAtLeast(1)
            val h = src.height.coerceAtLeast(1)
            val longEdge = maxOf(w, h)
            val scale = if (longEdge <= longEdgePx) 1.0 else longEdgePx.toDouble() / longEdge
            val tw = (w * scale).toInt().coerceAtLeast(1)
            val th = (h * scale).toInt().coerceAtLeast(1)
            val webp = if (tw == w && th == h) {
                src.encodeWebp(q)
            } else {
                Surface.makeRasterN32Premul(tw, th).use { surface ->
                    surface.canvas.clear(0xFFFFFFFF.toInt())
                    surface.canvas.drawImageRect(
                        src,
                        Rect.makeWH(w.toFloat(), h.toFloat()),
                        Rect.makeWH(tw.toFloat(), th.toFloat()),
                        SamplingMode.LINEAR,
                        null,
                        true,
                    )
                    surface.makeImageSnapshot().use { it.encodeWebp(q) }
                }
            }
            return webp
        }
    }

    private fun Image.encodeWebp(quality: Int): ByteArray {
        val data = encodeToData(EncodedImageFormat.WEBP, quality) ?: error("webp encode failed")
        return data.use { it.bytes }
    }
}

object DesktopImageCache {
    private const val MEMORY_MAX_ENTRIES = 12
    private const val DISPLAY_LONG_EDGE = 2048
    private const val DISK_HARD_LIMIT_BYTES = 200L * 1024 * 1024
    private const val DISK_TARGET_BYTES = 160L * 1024 * 1024
    private val previewSlots = Semaphore(2, true)

    private val memoryLock = Any()
    private val memoryCache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MEMORY_MAX_ENTRIES
    }
    private val diskCacheDir = File(System.getProperty("user.home"), ".comics8/cache").apply { mkdirs() }
    val coverThumbs = CoverThumbCache(
        File(System.getProperty("user.home"), ".comics8/thumbs"),
        AwtThumbEncoder,
        maxSizeBytes = 300L * 1024L * 1024L,
        targetSizeBytes = 240L * 1024L * 1024L,
    )

    @Volatile
    var httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun shareHttpClient(client: OkHttpClient) {
        httpClient = client
    }

    @Volatile
    lateinit var registry: SourceRegistry

    fun get(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        synchronized(memoryLock) {
            return memoryCache[url]
        }
    }

    private fun putMemory(url: String, bitmap: ImageBitmap) {
        synchronized(memoryLock) {
            memoryCache[url] = bitmap
        }
    }

    private fun containsMemory(url: String): Boolean {
        synchronized(memoryLock) {
            return memoryCache.containsKey(url)
        }
    }

    private fun hashUrl(url: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    internal fun readImageBytes(url: String): ByteArray? {
        if (url.isBlank()) return null
        val hash = hashUrl(url)
        val cachedFile = File(diskCacheDir, "$hash.img")
        val localFile = LocalImageUri.toFile(url)
        val zipRef = ZipImageUri.parse(url)
        val localPreviewRef = LocalPreviewUri.parse(url)
        val networkRef = NetworkImageUri.parse(url)
        return if (localFile != null && localFile.isFile && localFile.length() > 0L) {
            try {
                localFile.readBytes()
            } catch (_: Exception) {
                null
            }
        } else if (zipRef != null) {
            try {
                ZipArchive(zipRef.zip).use { archive ->
                    archive.open(zipRef.entry).use { it.readBytes() }
                }
            } catch (_: Exception) {
                null
            }
        } else if (localPreviewRef != null || networkRef?.preview != null) {
            try {
                val key = if (localPreviewRef != null) {
                    ThumbKey("local|${localPreviewRef.path}|${localPreviewRef.kind}", localPreviewRef.modifiedAt, localPreviewRef.size)
                } else {
                    ThumbKey(
                        "${networkRef!!.sourceId}|${networkRef.path}|${networkRef.preview}",
                        networkRef.modifiedAt,
                        networkRef.size,
                    )
                }
                val thumbnailPx = localPreviewRef?.thumbnailPx
                    ?: networkRef!!.thumbnailPx.takeIf { it > 0 }
                    ?: 320
                previewSlots.acquire()
                try {
                    coverThumbs.getOrCreate(key, thumbnailPx) {
                        if (localPreviewRef != null) LocalPreviewUri.open(localPreviewRef).use { it.readBytes() }
                        else NetworkSourceRuntime.open(url).use { it.readBytes() }
                    }.readBytes()
                } finally {
                    previewSlots.release()
                }
            } catch (_: Exception) {
                null
            }
        } else if (networkRef != null) {
            if (cachedFile.isFile && cachedFile.length() > 0L) {
                cachedFile.setLastModified(System.currentTimeMillis())
                runCatching { cachedFile.readBytes() }.getOrNull()
            } else {
                try {
                    NetworkSourceRuntime.open(url).use { it.readBytes() }.also { bytes ->
                        cachedFile.writeBytes(bytes)
                        evictDiskIfNeeded(cachedFile)
                    }
                } catch (_: Exception) {
                    null
                }
            }
        } else if (cachedFile.exists() && cachedFile.length() > 0) {
            try {
                cachedFile.readBytes()
            } catch (_: Exception) {
                null
            }
        } else {
            fetchRemoteBytes(url, cachedFile)
        }
    }

    private fun fetchRemoteBytes(url: String, cachedFile: File): ByteArray? {
        val urlsToTry = listOf(url) + ImageFallbacks.forUrl(url, registry)
        for (targetUrl in urlsToTry) {
            if (LocalImageUri.toFile(targetUrl) != null) continue
            if (ZipImageUri.parse(targetUrl) != null) continue
            if (NetworkImageUri.parse(targetUrl) != null) continue
            try {
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .header("Referer", ImageReferer.forUrl(targetUrl, registry))
                    .get()
                    .build()
                val result = httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
                if (result != null && result.isNotEmpty()) {
                    try {
                        cachedFile.writeBytes(result)
                        evictDiskIfNeeded(cachedFile)
                    } catch (_: Exception) {
                    }
                    return result
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    suspend fun loadImage(url: String): ImageBitmap? = runInterruptible(Dispatchers.IO) {
        if (url.isBlank()) return@runInterruptible null
        get(url)?.let { return@runInterruptible it }
        val bytes = readImageBytes(url)
        if (bytes != null && bytes.isNotEmpty()) {
            try {
                val bitmap = decodeForDisplay(bytes) ?: return@runInterruptible null
                putMemory(url, bitmap)
                bitmap
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        } else {
            null
        }
    }

    private fun decodeForDisplay(bytes: ByteArray): ImageBitmap? {
        Image.makeFromEncoded(bytes).use { src ->
            val w = src.width.coerceAtLeast(1)
            val h = src.height.coerceAtLeast(1)
            val longEdge = maxOf(w, h)
            if (longEdge <= DISPLAY_LONG_EDGE) {
                return src.toComposeImageBitmap()
            }
            val scale = DISPLAY_LONG_EDGE.toFloat() / longEdge
            val tw = (w * scale).toInt().coerceAtLeast(1)
            val th = (h * scale).toInt().coerceAtLeast(1)
            return Surface.makeRasterN32Premul(tw, th).use { surface ->
                surface.canvas.clear(0xFFFFFFFF.toInt())
                surface.canvas.drawImageRect(
                    src,
                    Rect.makeWH(w.toFloat(), h.toFloat()),
                    Rect.makeWH(tw.toFloat(), th.toFloat()),
                    SamplingMode.LINEAR,
                    null,
                    true,
                )
                surface.makeImageSnapshot().use { it.toComposeImageBitmap() }
            }
        }
    }

    suspend fun preload(urls: List<String>) = withContext(Dispatchers.IO) {
        for (url in urls) {
            if (url.isNotBlank() && !containsMemory(url)) {
                loadImage(url)
            }
        }
    }

    private fun evictDiskIfNeeded(keep: File) {
        try {
            val files = diskCacheDir.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= DISK_HARD_LIMIT_BYTES) return
            val keepPath = keep.absolutePath
            val oldestFirst = files
                .filter { it.absolutePath != keepPath }
                .sortedBy { it.lastModified() }
            for (file in oldestFirst) {
                if (total <= DISK_TARGET_BYTES) break
                val size = file.length()
                if (file.delete()) {
                    total -= size
                }
            }
        } catch (_: Exception) {
        }
    }
}

@Composable
fun DesktopAsyncImage(
    url: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    half: ImageHalf = ImageHalf.FULL,
    showPlaceholder: Boolean = true,
    onLoaded: ((ImageBitmap) -> Unit)? = null,
) {
    // Check synchronous memory cache first
    val cachedBitmap = remember(url) { DesktopImageCache.get(url) }
    var displayedBitmap by remember { mutableStateOf(cachedBitmap) }
    var loading by remember { mutableStateOf(cachedBitmap == null) }

    LaunchedEffect(url) {
        val memoryHit = DesktopImageCache.get(url)
        if (memoryHit != null) {
            displayedBitmap = memoryHit
            loading = false
            onLoaded?.invoke(memoryHit)
        } else {
            loading = true
            val loaded = DesktopImageCache.loadImage(url)
            if (loaded != null) {
                displayedBitmap = loaded
                loading = false
                onLoaded?.invoke(loaded)
            } else {
                loading = false
            }
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = alignment,
    ) {
        val currentBitmap = displayedBitmap
        if (currentBitmap != null) {
            if (half == ImageHalf.FULL) {
                Image(
                    bitmap = currentBitmap,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    alignment = alignment,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val cropRect = remember(currentBitmap, half) {
                    try {
                        val awtImage = currentBitmap.toAwtImage()
                        val w = awtImage.width
                        val h = awtImage.height
                        val regionLeft = if (half == ImageHalf.LEFT) 0 else w / 2
                        val regionRight = if (half == ImageHalf.LEFT) w / 2 else w
                        computeContentCropRect(
                            regionLeft = regionLeft,
                            regionTop = 0,
                            regionRight = regionRight,
                            regionBottom = h,
                            maxCropFractionX = 0.45f,
                            maxCropFractionY = 0.20f,
                            whiteThreshold = 225,
                            whiteRatio = 0.95f,
                            getPixelRgb = { x, y -> awtImage.getRGB(x, y) },
                        )
                    } catch (_: Exception) {
                        val w = currentBitmap.width
                        val h = currentBitmap.height
                        val regionLeft = if (half == ImageHalf.LEFT) 0 else w / 2
                        val regionRight = if (half == ImageHalf.LEFT) w / 2 else w
                        CropRect(regionLeft, 0, regionRight, h)
                    }
                }

                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val srcW = cropRect.width.toFloat()
                    val srcH = cropRect.height.toFloat()
                    if (srcW > 0 && srcH > 0) {
                        val pageAspectRatio = srcW / srcH
                        val containerWidth = size.width
                        val containerHeight = size.height
                        val containerAspectRatio = containerWidth / containerHeight

                        val dstWidth: Float
                        val dstHeight: Float
                        if (containerAspectRatio > pageAspectRatio) {
                            dstHeight = containerHeight
                            dstWidth = dstHeight * pageAspectRatio
                        } else {
                            dstWidth = containerWidth
                            dstHeight = dstWidth / pageAspectRatio
                        }

                        val dstLeft = (containerWidth - dstWidth) / 2f
                        val dstTop = (containerHeight - dstHeight) / 2f

                        drawImage(
                            image = currentBitmap,
                            srcOffset = androidx.compose.ui.unit.IntOffset(cropRect.left, cropRect.top),
                            srcSize = androidx.compose.ui.unit.IntSize(cropRect.width, cropRect.height),
                            dstOffset = androidx.compose.ui.unit.IntOffset(dstLeft.toInt(), dstTop.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(dstWidth.toInt(), dstHeight.toInt()),
                        )
                    }
                }
            }
        } else if (loading && showPlaceholder) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else if (!loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.layout.Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    modifier = Modifier.padding(16.dp),
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.BrokenImage,
                        contentDescription = "이미지 로딩 실패",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(36.dp),
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.Text(
                        text = "이미지를 불러올 수 없습니다",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}
