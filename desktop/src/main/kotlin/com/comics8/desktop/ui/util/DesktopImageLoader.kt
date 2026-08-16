package com.comics8.desktop.ui.util

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.comics8.core.parser.ComicSite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object DesktopImageCache {
    private val memoryCache = ConcurrentHashMap<String, ImageBitmap>()
    private val diskCacheDir = File(System.getProperty("user.home"), ".comics8/cache").apply { mkdirs() }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun get(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        return memoryCache[url]
    }

    private fun hashUrl(url: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun loadImage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        memoryCache[url]?.let { return@withContext it }

        val hash = hashUrl(url)
        val cachedFile = File(diskCacheDir, "$hash.img")

        val bytes = if (cachedFile.exists() && cachedFile.length() > 0) {
            try {
                cachedFile.readBytes()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        } ?: run {
            val urlsToTry = listOf(url) + ComicSite.getFallbackImageUrls(url)
            var fetchedBytes: ByteArray? = null
            for (targetUrl in urlsToTry) {
                try {
                    val request = Request.Builder()
                        .url(targetUrl)
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                        .header("Referer", ComicSite.ORIGIN)
                        .get()
                        .build()
                    val result = httpClient.newCall(request).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.bytes() else null
                    }
                    if (result != null && result.isNotEmpty()) {
                        fetchedBytes = result
                        try {
                            cachedFile.writeBytes(result)
                        } catch (_: Exception) {
                        }
                        break
                    }
                } catch (_: Exception) {
                }
            }
            fetchedBytes
        }

        if (bytes != null && bytes.isNotEmpty()) {
            try {
                val skiaImage = Image.makeFromEncoded(bytes)
                val bitmap = skiaImage.toComposeImageBitmap()
                memoryCache[url] = bitmap
                bitmap
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    suspend fun preload(urls: List<String>) = withContext(Dispatchers.IO) {
        for (url in urls) {
            if (url.isNotBlank() && !memoryCache.containsKey(url)) {
                loadImage(url)
            }
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
            Image(
                bitmap = currentBitmap,
                contentDescription = contentDescription,
                contentScale = contentScale,
                alignment = alignment,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (loading && showPlaceholder) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        } else if (!loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
            )
        }
    }
}
