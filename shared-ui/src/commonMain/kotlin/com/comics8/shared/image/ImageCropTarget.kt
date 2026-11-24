package com.comics8.shared.image

import androidx.compose.ui.graphics.ImageBitmap
import coil3.Image

/**
 * Decoded bitmap handle + pixel accessor used by dual-page split content-crop calculation
 * without pulling platform bitmap classes into common composables.
 */
data class CropImageTarget(
    val imageBitmap: ImageBitmap,
    val width: Int,
    val height: Int,
    val getPixelRgb: (x: Int, y: Int) -> Int,
)

expect fun extractCropImageTarget(image: Image): CropImageTarget?
