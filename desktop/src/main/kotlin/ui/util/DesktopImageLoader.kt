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
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.image.ImageMemoryPolicy
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

import okhttp3.Request
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object AwtThumbEncoder : ThumbEncoder {
    override fun webp(bytes: ByteArray, longEdgePx: Int, quality: Int): ByteArray {
        val q = quality.coerceIn(1, 100)
        val src = decodeToSkiaImage(bytes) ?: error("decode failed")
        src.use {
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

internal fun decodeToSkiaImage(bytes: ByteArray): Image? {
    return try {
        Image.makeFromEncoded(bytes)
    } catch (_: Throwable) {
        decodeAvifToSkiaImage(bytes)
    }
}

internal fun decodeAvifToSkiaImage(bytes: ByteArray): Image? {
    val decoded = com.comics8.core.image.AvifDecoder.decode(bytes) ?: return null
    val w = decoded.width
    val h = decoded.height
    val argb = decoded.argbPixels
    val rgba = ByteArray(w * h * 4)
    var srcIdx = 0
    var dstIdx = 0
    while (srcIdx < argb.size) {
        val pixel = argb[srcIdx++]
        rgba[dstIdx++] = ((pixel ushr 16) and 0xFF).toByte()
        rgba[dstIdx++] = ((pixel ushr 8) and 0xFF).toByte()
        rgba[dstIdx++] = (pixel and 0xFF).toByte()
        rgba[dstIdx++] = ((pixel ushr 24) and 0xFF).toByte()
    }
    val info = ImageInfo(
        width = w,
        height = h,
        colorType = ColorType.RGBA_8888,
        alphaType = ColorAlphaType.UNPREMUL,
    )
    return Image.makeRaster(info, rgba, w * 4)
}

object DesktopImageCache {
    private const val MEMORY_MAX_BYTES = 304L * 1024L * 1024L
    private const val DISPLAY_LONG_EDGE = 2048
    private const val DISK_HARD_LIMIT_BYTES = 500L * 1024 * 1024
    private const val DISK_TARGET_BYTES = 400L * 1024 * 1024
    private val pipelineSlots = Semaphore(3)

    private val memoryLock = Any()
    private data class CachedBitmap(val bitmap: ImageBitmap, val sizeBytes: Long)

    private class MemoryBucket(private val maxBytes: Long) {
        private val entries = LinkedHashMap<String, CachedBitmap>(16, 0.75f, true)
        private var sizeBytes = 0L

        fun get(url: String): ImageBitmap? = entries[url]?.bitmap
        fun contains(url: String): Boolean = entries.containsKey(url)

        fun put(url: String, bitmap: ImageBitmap) {
            val bitmapBytes = bitmap.width.toLong() * bitmap.height.toLong() * 4L
            entries.remove(url)?.let { sizeBytes -= it.sizeBytes }
            if (bitmapBytes > maxBytes) return
            entries[url] = CachedBitmap(bitmap, bitmapBytes)
            sizeBytes += bitmapBytes
            val iterator = entries.entries.iterator()
            while (sizeBytes > maxBytes && iterator.hasNext()) {
                sizeBytes -= iterator.next().value.sizeBytes
                iterator.remove()
            }
        }

        fun remove(url: String) {
            entries.remove(url)?.let { sizeBytes -= it.sizeBytes }
        }

        fun clear() {
            entries.clear()
            sizeBytes = 0L
        }
    }
    private val memoryBudgets = ImageMemoryPolicy.partition(MEMORY_MAX_BYTES)
    private val memoryCaches = ImageCacheRole.entries.associateWith { role ->
        MemoryBucket(memoryBudgets[role])
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

    fun get(role: ImageCacheRole, url: String): ImageBitmap? {
        if (url.isBlank()) return null
        synchronized(memoryLock) {
            return memoryCaches.getValue(role).get(url)
        }
    }

    private fun containsMemory(role: ImageCacheRole, url: String): Boolean {
        synchronized(memoryLock) {
            return memoryCaches.getValue(role).contains(url)
        }
    }

    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightLock = Any()
    private data class RequestKey(val role: ImageCacheRole, val url: String)
    private data class InFlightRequest(val id: Long, val deferred: Deferred<ImageBitmap?>)
    private val inFlightRequests = HashMap<RequestKey, InFlightRequest>()
    private val requestIds = AtomicLong(0L)
    private val roleGenerations = LongArray(ImageCacheRole.entries.size)

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
            memoryCaches.values.forEach(MemoryBucket::clear)
        }
    }

    fun clear(role: ImageCacheRole) {
        val pending = synchronized(inFlightLock) {
            roleGenerations[role.ordinal]++
            val matching = inFlightRequests
                .filterKeys { it.role == role }
                .values
                .map { it.deferred }
            inFlightRequests.keys.removeAll { it.role == role }
            synchronized(memoryLock) {
                memoryCaches.getValue(role).clear()
            }
            matching
        }
        pending.forEach { it.cancel() }
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
        val previewSpec = PreviewImageResolver.resolve(url, refreshNetworkRevision = true)
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
                coverThumbs.getOrCreate(previewSpec.key, previewSpec.thumbnailPx, forceRetry = forceRetry) {
                    previewSpec.readSourceBytes()
                }
                    .readBytes()
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
        }
    }

    fun cancelPendingPreviews() {
        synchronized(inFlightLock) {
            val iterator = inFlightRequests.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val url = entry.key.url
                if (PreviewImageResolver.resolve(url) != null || NetworkImageUri.parse(url)?.preview != null) {
                    entry.value.deferred.cancel()
                    iterator.remove()
                }
            }
        }
    }

    suspend fun loadImage(role: ImageCacheRole, url: String, forceRetry: Boolean = false): ImageBitmap? {
        if (url.isBlank()) return null
        if (!forceRetry) {
            get(role, url)?.let { return it }
        } else {
            synchronized(memoryLock) { memoryCaches.getValue(role).remove(url) }
        }

        val deferred = synchronized(inFlightLock) {
            val key = RequestKey(role, url)
            if (!forceRetry) {
                get(role, url)?.let { return it }
            }
            if (forceRetry) {
                inFlightRequests.remove(key)?.deferred?.cancel()
            } else {
                inFlightRequests[key]?.let { return@synchronized it.deferred }
            }

            val generation = roleGenerations[role.ordinal]
            val requestId = requestIds.incrementAndGet()
            val def = loadScope.async(start = CoroutineStart.LAZY) {
                try {
                    if (!isActive) return@async null
                    pipelineSlots.withPermit {
                        if (!isActive) return@async null
                        val bytes = readImageBytes(url, forceRetry = forceRetry)
                        if (bytes != null && bytes.isNotEmpty()) {
                            val bitmap = decodeForDisplay(bytes)
                            if (bitmap != null) {
                                synchronized(inFlightLock) {
                                    if (roleGenerations[role.ordinal] == generation && isActive) {
                                        synchronized(memoryLock) {
                                            memoryCaches.getValue(role).put(url, bitmap)
                                        }
                                    }
                                }
                                bitmap
                            } else {
                                val hash = hashUrl(url)
                                File(diskCacheDir, "$hash.img").delete()
                                null
                            }
                        } else {
                            null
                        }
                    }
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                } finally {
                    synchronized(inFlightLock) {
                        if (inFlightRequests[key]?.id == requestId) {
                            inFlightRequests.remove(key)
                        }
                    }
                }
            }
            inFlightRequests[key] = InFlightRequest(requestId, def)
            def.start()
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
            val src = decodeToSkiaImage(bytes) ?: return null
            src.use {
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

    suspend fun preload(role: ImageCacheRole, urls: List<String>) = withContext(Dispatchers.IO) {
        for (url in urls) {
            if (url.isNotBlank() && !containsMemory(role, url)) {
                loadImage(role, url)
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
    cacheRole: ImageCacheRole,
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
    val cachedBitmap = remember(cacheRole, url) { DesktopImageCache.get(cacheRole, url) }
    var displayedBitmap by remember(cacheRole, url) { mutableStateOf(cachedBitmap) }
    var loading by remember(cacheRole, url) { mutableStateOf(cachedBitmap == null) }

    val retryKey = if (displayedBitmap == null) refreshEpoch else 0L

    LaunchedEffect(cacheRole, url, retryKey) {
        if (url.isBlank()) {
            displayedBitmap = null
            loading = false
            return@LaunchedEffect
        }
        val memoryHit = DesktopImageCache.get(cacheRole, url)
        if (memoryHit != null) {
            displayedBitmap = memoryHit
            loading = false
            onLoaded?.invoke(memoryHit)
        } else {
            loading = true
            val force = retryKey > 0L
            val loaded = DesktopImageCache.loadImage(cacheRole, url, forceRetry = force)
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
                    val loaded = DesktopImageCache.loadImage(cacheRole, url, forceRetry = true)
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
