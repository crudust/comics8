package com.comics8.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.maxBitmapSize
import coil3.size.Size
import com.comics8.core.image.ImageCacheRole
import com.comics8.shared.image.THUMBNAIL_MAX_EDGE_PX
import com.comics8.shared.image.imageCacheRole

/**
 * Platform UI actions needed by shared composables (clipboard, toast notifications, external URLs).
 */
interface PlatformUiBridge {
    fun copyToClipboard(label: String, text: String)

    fun showToast(message: String)

    fun openUrl(url: String) {}
}

val LocalPlatformUiBridge = staticCompositionLocalOf<PlatformUiBridge> {
    object : PlatformUiBridge {
        override fun copyToClipboard(label: String, text: String) = Unit

        override fun showToast(message: String) = Unit
    }
}

val LocalPlatformBackHandler = staticCompositionLocalOf<@Composable (Boolean, () -> Unit) -> Unit> {
    { _, _ -> }
}

val LocalImageFallbacksProvider = staticCompositionLocalOf<(String) -> List<String>> {
    { emptyList() }
}

@Composable
fun PlatformBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
) {
    LocalPlatformBackHandler.current(enabled, onBack)
}

@Composable
fun ComicImage(
    url: String,
    cacheRole: ImageCacheRole,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    refreshEpoch: Long = 0L,
) {
    var isError by remember(url) { mutableStateOf(false) }
    val retryKey = if (isError) refreshEpoch else 0L
    val platformContext = LocalPlatformContext.current
    val imageModel = remember(url, cacheRole, retryKey, platformContext) {
        ImageRequest.Builder(platformContext)
            .crossfade(true)
            .data(url)
            .imageCacheRole(cacheRole, url)
            .apply {
                if (cacheRole != ImageCacheRole.READER) {
                    maxBitmapSize(Size(THUMBNAIL_MAX_EDGE_PX, THUMBNAIL_MAX_EDGE_PX))
                }
                if (retryKey > 0L) {
                    memoryCachePolicy(CachePolicy.WRITE_ONLY)
                    diskCachePolicy(CachePolicy.WRITE_ONLY)
                }
            }
            .build()
    }
    AsyncImage(
        model = imageModel,
        contentDescription = contentDescription,
        contentScale = contentScale,
        onError = { isError = true },
        onSuccess = { isError = false },
        modifier = modifier,
    )
}
