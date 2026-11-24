package com.comics8.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.comics8.core.image.ImageCacheRole

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

@Composable
fun PlatformBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
) {
    LocalPlatformBackHandler.current(enabled, onBack)
}

/**
 * Composable slot for rendering cover/thumbnail images via platform-specific image loaders
 * (Coil 3 on Android, DesktopImageLoader on Desktop).
 */
fun interface ComicImageRenderer {
    @Composable
    fun Render(
        url: String,
        cacheRole: ImageCacheRole,
        contentDescription: String?,
        modifier: Modifier,
        contentScale: ContentScale,
        refreshEpoch: Long,
    )
}

val LocalComicImageRenderer = staticCompositionLocalOf<ComicImageRenderer> {
    ComicImageRenderer { _, _, _, modifier, _, _ ->
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
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
    LocalComicImageRenderer.current.Render(
        url = url,
        cacheRole = cacheRole,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        refreshEpoch = refreshEpoch,
    )
}
