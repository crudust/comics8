package com.comics8.desktop.ui.util

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comics8.core.model.CropRect
import com.comics8.core.model.ImageHalf
import com.comics8.core.model.computeContentCropRect
import com.comics8.core.network.ImageFallbacks
import com.comics8.core.network.ImageReferer
import com.comics8.core.source.LocalImageUri
import com.comics8.core.source.SourceRegistry
import com.comics8.desktop.ui.theme.LocalStrings
import com.comics8.core.source.local.PreviewImageResolver
import com.comics8.core.source.local.ThumbEncoder
import com.comics8.core.source.local.CoverThumbCache
import com.comics8.core.source.local.LocalPreviewUri
import com.comics8.core.source.local.ThumbKey
import com.comics8.core.source.local.ZipArchive
import com.comics8.core.source.local.ZipImageUri
import com.comics8.core.source.network.NetworkImageUri
import com.comics8.core.source.network.NetworkSourceRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

import okhttp3.Request
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

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
    private const val MEMORY_MAX_ENTRIES = 192
    private const val DISPLAY_LONG_EDGE = 2048
    private const val DISK_HARD_LIMIT_BYTES = 500L * 1024 * 1024
    private const val DISK_TARGET_BYTES = 400L * 1024 * 1024
    private val previewSlots = Semaphore(4, true)
    private val remoteSlots = Semaphore(12, true)

    private val memoryLock = Any()
    private val memoryCache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MEMORY_MAX_ENTRIES
    }
    private val diskCacheDir = File(System.getProperty("user.home"), ".comics8/cache").apply { mkdirs() }
    private val estimatedDiskBytes = AtomicLong(
        diskCacheDir.listFiles()?.asSequence()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L,
    )
    val coverThumbs = CoverThumbCache(
        File(System.getProperty("user.home"), ".comics8/thumbs"),
        AwtThumbEncoder,
        maxSizeBytes = 300L * 1024L * 1024L,
        targetSizeBytes = 240L * 1024L * 1024L,
    )

    @Volatile
    var httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
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

    private val dimensionCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

    fun getDimensions(url: String): Pair<Int, Int>? {
        if (url.isBlank()) return null
        dimensionCache[url]?.let { return it }
        get(url)?.let {
            val dim = Pair(it.width, it.height)
            dimensionCache[url] = dim
            return dim
        }
        return null
    }

    fun probeDimensions(url: String): Pair<Int, Int>? {
        if (url.isBlank()) return null
        getDimensions(url)?.let { return it }
        return try {
            val hash = hashUrl(url)
            val cachedFile = File(diskCacheDir, "$hash.img")
            val localFile = LocalImageUri.toFile(url)
            val zipRef = ZipImageUri.parse(url)

            val bytes = if (localFile != null && localFile.isFile && localFile.length() > 0L) {
                localFile.readBytes()
            } else if (cachedFile.isFile && cachedFile.length() > 0L) {
                cachedFile.readBytes()
            } else if (zipRef != null) {
                ZipArchive(zipRef.zip).use { archive ->
                    archive.open(zipRef.entry).use { it.readBytes() }
                }
            } else {
                null
            }

            if (bytes != null && bytes.isNotEmpty()) {
                Image.makeFromEncoded(bytes).use { img ->
                    val dim = Pair(img.width, img.height)
                    dimensionCache[url] = dim
                    dim
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun putMemory(url: String, bitmap: ImageBitmap) {
        synchronized(memoryLock) {
            memoryCache[url] = bitmap
            dimensionCache[url] = Pair(bitmap.width, bitmap.height)
        }
    }

    private fun containsMemory(url: String): Boolean {
        synchronized(memoryLock) {
            return memoryCache.containsKey(url)
        }
    }

    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightLock = Any()
    private val inFlightRequests = HashMap<String, Deferred<ImageBitmap?>>()

    private fun hashUrl(url: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun writeAtomically(targetFile: File, bytes: ByteArray) {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp.${java.util.UUID.randomUUID()}")
        val previousSize = targetFile.takeIf { it.isFile }?.length() ?: 0L
        try {
            tempFile.writeBytes(bytes)
            if (tempFile.exists() && tempFile.length() > 0L) {
                try {
                    Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: Exception) {
                    Files.move(tempFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                estimatedDiskBytes.addAndGet(targetFile.length() - previousSize)
            }
        } catch (_: Exception) {
            tempFile.delete()
        }
    }

    fun clearFailures() {
        coverThumbs.clearFailures()
    }

    fun close() {
        loadScope.cancel()
        synchronized(inFlightLock) { inFlightRequests.clear() }
        synchronized(memoryLock) {
            memoryCache.clear()
            dimensionCache.clear()
        }
    }

    internal fun readImageBytes(url: String, forceRetry: Boolean = false): ByteArray? {
        if (url.isBlank()) return null
        val hash = hashUrl(url)
        val cachedFile = File(diskCacheDir, "$hash.img")
        if (forceRetry && cachedFile.isFile && cachedFile.length() == 0L) {
            cachedFile.delete()
        }
        val localFile = LocalImageUri.toFile(url)
        val zipRef = ZipImageUri.parse(url)
        val previewSpec = PreviewImageResolver.resolve(url)
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
        } else if (previewSpec != null) {
            try {
                previewSlots.acquire()
                try {
                    coverThumbs.getOrCreate(previewSpec.key, previewSpec.thumbnailPx, forceRetry = forceRetry) {
                        previewSpec.readSourceBytes()
                    }.readBytes()
                } finally {
                    previewSlots.release()
                }
            } catch (_: Exception) {
                null
            }
        } else if (networkRef != null) {
            if (!forceRetry && cachedFile.isFile && cachedFile.length() > 0L) {
                cachedFile.setLastModified(System.currentTimeMillis())
                val bytes = runCatching { cachedFile.readBytes() }.getOrNull()
                if (bytes != null && bytes.isNotEmpty()) {
                    bytes
                } else {
                    cachedFile.delete()
                    fetchNetworkBytes(url, cachedFile)
                }
            } else {
                fetchNetworkBytes(url, cachedFile)
            }
        } else if (!forceRetry && cachedFile.exists() && cachedFile.length() > 0) {
            try {
                cachedFile.readBytes()
            } catch (_: Exception) {
                null
            }
        } else {
            fetchRemoteBytes(url, cachedFile)
        }
    }

    private fun fetchNetworkBytes(url: String, cachedFile: File): ByteArray? {
        return try {
            NetworkSourceRuntime.open(url).use { it.readBytes() }.also { bytes ->
                writeAtomically(cachedFile, bytes)
                evictDiskIfNeeded(cachedFile)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchRemoteBytes(url: String, cachedFile: File): ByteArray? {
        if (LocalImageUri.toFile(url) != null) return null
        if (ZipImageUri.parse(url) != null) return null
        if (NetworkImageUri.parse(url) != null) return null

        remoteSlots.acquire()
        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            val result = httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
            if (result != null && result.isNotEmpty()) {
                try {
                    writeAtomically(cachedFile, result)
                    evictDiskIfNeeded(cachedFile)
                } catch (_: Exception) {
                }
                return result
            }
            return null
        } catch (_: Exception) {
            return null
        } finally {
            remoteSlots.release()
        }
    }

    fun cancelPendingPreviews() {
        synchronized(inFlightLock) {
            val iterator = inFlightRequests.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val url = entry.key
                if (PreviewImageResolver.resolve(url) != null || NetworkImageUri.parse(url)?.preview != null) {
                    entry.value.cancel()
                    iterator.remove()
                }
            }
        }
    }

    suspend fun loadImage(url: String, forceRetry: Boolean = false): ImageBitmap? {
        if (url.isBlank()) return null
        if (!forceRetry) {
            get(url)?.let { return it }
        } else {
            synchronized(memoryLock) { memoryCache.remove(url) }
        }

        val deferred = synchronized(inFlightLock) {
            if (!forceRetry) {
                get(url)?.let { return it }
            }
            if (forceRetry) {
                inFlightRequests.remove(url)
            } else {
                inFlightRequests[url]?.let { return@synchronized it }
            }

            val def = loadScope.async {
                try {
                    if (!isActive) return@async null
                    val bytes = readImageBytes(url, forceRetry = forceRetry)
                    if (bytes != null && bytes.isNotEmpty()) {
                        val bitmap = decodeForDisplay(bytes)
                        if (bitmap != null) {
                            putMemory(url, bitmap)
                            bitmap
                        } else {
                            val hash = hashUrl(url)
                            File(diskCacheDir, "$hash.img").delete()
                            null
                        }
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                } finally {
                    synchronized(inFlightLock) {
                        inFlightRequests.remove(url)
                    }
                }
            }
            inFlightRequests[url] = def
            def
        }

        return try {
            deferred.await()
        } catch (_: kotlinx.coroutines.CancellationException) {
            null
        }
    }


    private fun decodeForDisplay(bytes: ByteArray): ImageBitmap? {
        return try {
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
        } catch (_: Exception) {
            null
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
        if (estimatedDiskBytes.get() <= DISK_HARD_LIMIT_BYTES) return
        try {
            val files = diskCacheDir.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= DISK_HARD_LIMIT_BYTES) {
                estimatedDiskBytes.set(total)
                return
            }
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
            estimatedDiskBytes.set(total)
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
    refreshEpoch: Long = 0L,
    onLoaded: ((ImageBitmap) -> Unit)? = null,
) {
    // Check synchronous memory cache first
    val cachedBitmap = remember(url) { DesktopImageCache.get(url) }
    var displayedBitmap by remember(url) { mutableStateOf(cachedBitmap) }
    var loading by remember(url) { mutableStateOf(cachedBitmap == null) }

    val retryKey = if (displayedBitmap == null) refreshEpoch else 0L

    LaunchedEffect(url, retryKey) {
        if (url.isBlank()) {
            displayedBitmap = null
            loading = false
            return@LaunchedEffect
        }
        val memoryHit = DesktopImageCache.get(url)
        if (memoryHit != null) {
            displayedBitmap = memoryHit
            loading = false
            onLoaded?.invoke(memoryHit)
        } else {
            loading = true
            val force = retryKey > 0L
            val loaded = DesktopImageCache.loadImage(url, forceRetry = force)
            displayedBitmap = loaded
            loading = false
            if (loaded != null) {
                onLoaded?.invoke(loaded)
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
            val strings = LocalStrings.current
            var localRetry by remember(url) { mutableStateOf(0) }
            LaunchedEffect(localRetry) {
                if (localRetry > 0) {
                    loading = true
                    val loaded = DesktopImageCache.loadImage(url, forceRetry = true)
                    displayedBitmap = loaded
                    loading = false
                    if (loaded != null) {
                        onLoaded?.invoke(loaded)
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
                    .clickable { localRetry++ },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = strings.errorImageLoadFailed,
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = strings.errorCannotLoadImage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "(${strings.actionRetry})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
