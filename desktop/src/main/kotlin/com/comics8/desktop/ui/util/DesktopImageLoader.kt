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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.comics8.core.network.ImageFallbacks
import com.comics8.core.network.ImageReferer
import com.comics8.core.source.LocalImageUri
import com.comics8.core.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object DesktopImageCache {
    private const val MEMORY_MAX_ENTRIES = 48
    private const val DISK_HARD_LIMIT_BYTES = 200L * 1024 * 1024
    private const val DISK_TARGET_BYTES = 160L * 1024 * 1024

    private val memoryLock = Any()
    private val memoryCache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MEMORY_MAX_ENTRIES
    }
    private val diskCacheDir = File(System.getProperty("user.home"), ".comics8/cache").apply { mkdirs() }

    @Volatile
    var httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun shareHttpClient(client: OkHttpClient) {
        httpClient = client
    }

    @Volatile
    var registry: SourceRegistry = SourceRegistry()

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

    suspend fun loadImage(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        get(url)?.let { return@withContext it }

        val hash = hashUrl(url)
        val cachedFile = File(diskCacheDir, "$hash.img")

        val localFile = LocalImageUri.toFile(url)
        val bytes = if (localFile != null && localFile.isFile && localFile.length() > 0L) {
            try {
                localFile.readBytes()
            } catch (_: Exception) {
                null
            }
        } else if (cachedFile.exists() && cachedFile.length() > 0) {
            try {
                cachedFile.readBytes()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        } ?: run {
            val urlsToTry = listOf(url) + ImageFallbacks.forUrl(url, registry)
            var fetchedBytes: ByteArray? = null
            for (targetUrl in urlsToTry) {
                if (LocalImageUri.toFile(targetUrl) != null) continue
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
                        fetchedBytes = result
                        try {
                            cachedFile.writeBytes(result)
                            evictDiskIfNeeded(cachedFile)
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
                putMemory(url, bitmap)
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
