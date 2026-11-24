package com.comics8.shared.image

import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.BitmapImage
import coil3.Image

actual fun extractCropImageTarget(image: Image): CropImageTarget? {
    val bitmap = (image as? BitmapImage)?.bitmap ?: return null
    return CropImageTarget(
        imageBitmap = bitmap.asComposeImageBitmap(),
        width = bitmap.width,
        height = bitmap.height,
        getPixelRgb = { x, y -> bitmap.getColor(x, y) },
    )
}
