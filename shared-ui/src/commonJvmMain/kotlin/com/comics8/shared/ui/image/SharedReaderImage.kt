package com.comics8.shared.ui.image

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.model.CropRect
import com.comics8.core.model.ImageHalf
import com.comics8.core.model.ImageTile
import com.comics8.core.model.computeContentCropRect
import com.comics8.core.network.ComicImageInterceptor
import com.comics8.shared.image.ReaderCropRectCache
import com.comics8.shared.image.extractCropImageTarget
import com.comics8.shared.image.imageCacheRole
import com.comics8.shared.image.readerImageSizing
import com.comics8.shared.image.readerTile
import com.comics8.shared.image.readerTileIdentity
import com.comics8.shared.ui.LocalImageFallbacksProvider
import com.comics8.shared.ui.theme.LocalStrings

@Composable
fun ReaderImage(
    url: String?,
    contentDescription: String?,
    maxBitmapSize: Size,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    half: ImageHalf = ImageHalf.FULL,
    tile: ImageTile? = null,
    minPlaceholderHeight: Dp = 480.dp,
    onDimensionsLoaded: ((width: Int, height: Int) -> Unit)? = null,
    onRefreshEpisode: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    val platformContext = LocalPlatformContext.current
    val fallbacksProvider = LocalImageFallbacksProvider.current
    var retryKey by remember(url) { mutableStateOf(0) }
    val fallbacks = remember(url) {
        if (url != null && (url.startsWith("http://", true) || url.startsWith("https://", true))) {
            fallbacksProvider(url)
        } else {
            emptyList()
        }
    }
    val effectiveUrl = remember(url, retryKey, fallbacks) {
        if (retryKey >= 2 && fallbacks.isNotEmpty()) {
            fallbacks[(retryKey - 2) % fallbacks.size]
        } else {
            url
        }
    }
    val imageModel = remember(effectiveUrl, half, tile, retryKey, maxBitmapSize, platformContext) {
        ImageRequest.Builder(platformContext)
            .data(effectiveUrl)
            .imageCacheRole(ImageCacheRole.READER, readerTileIdentity(effectiveUrl.toString(), tile))
            .readerTile(tile)
            .readerImageSizing(maxBitmapSize)
            .apply {
                if (retryKey > 0) {
                    memoryCachePolicy(CachePolicy.WRITE_ONLY)
                    diskCachePolicy(CachePolicy.WRITE_ONLY)
                    httpHeaders(
                        NetworkHeaders.Builder()
                            .set(ComicImageInterceptor.HEADER_RETRY_MODE, ComicImageInterceptor.RETRY_MODE_MANUAL)
                            .build(),
                    )
                }
            }
            .build()
    }

    SubcomposeAsyncImage(
        model = imageModel,
        contentDescription = contentDescription,
        contentScale = contentScale,
        alignment = alignment,
        modifier = modifier,
        onSuccess = { successState ->
            val img = successState.result.image
            if (img.width > 0 && img.height > 0) {
                onDimensionsLoaded?.invoke(img.width, img.height)
            }
        },
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .defaultMinSize(minHeight = minPlaceholderHeight),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .defaultMinSize(minHeight = 200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { retryKey++ },
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
                    if (retryKey >= 1 && onRefreshEpisode != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = strings.actionRefresh,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { onRefreshEpisode() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        },
        success = { successState ->
            if (half == ImageHalf.FULL) {
                SubcomposeAsyncImageContent()
            } else {
                val cropTarget = remember(successState.result.image) {
                    extractCropImageTarget(successState.result.image)
                }
                if (cropTarget != null) {
                    val cropKeyUrl = effectiveUrl.orEmpty()
                    val cropRect = remember(cropTarget, half, cropKeyUrl) {
                        ReaderCropRectCache.get(cropKeyUrl, half) ?: run {
                            val w = cropTarget.width
                            val h = cropTarget.height
                            val regionLeft = if (half == ImageHalf.LEFT) 0 else w / 2
                            val regionRight = if (half == ImageHalf.LEFT) w / 2 else w
                            val computed = try {
                                computeContentCropRect(
                                    regionLeft = regionLeft,
                                    regionTop = 0,
                                    regionRight = regionRight,
                                    regionBottom = h,
                                    maxCropFractionX = 0.45f,
                                    maxCropFractionY = 0.20f,
                                    whiteThreshold = 225,
                                    whiteRatio = 0.95f,
                                    getPixelRgb = cropTarget.getPixelRgb,
                                )
                            } catch (_: Exception) {
                                CropRect(regionLeft, 0, regionRight, h)
                            }
                            ReaderCropRectCache.put(cropKeyUrl, half, computed)
                            computed
                        }
                    }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
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
                                    image = cropTarget.imageBitmap,
                                    srcOffset = IntOffset(cropRect.left, cropRect.top),
                                    srcSize = IntSize(cropRect.width, cropRect.height),
                                    dstOffset = IntOffset(dstLeft.toInt(), dstTop.toInt()),
                                    dstSize = IntSize(dstWidth.toInt(), dstHeight.toInt()),
                                )
                            }
                        }
                    }
                } else {
                    SubcomposeAsyncImageContent()
                }
            }
        },
    )
}
