package com.comics8.shared.image

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap
import coil3.BitmapImage
import coil3.Image

actual fun extractCropImageTarget(image: Image): CropImageTarget? {
    val raw = (image as? BitmapImage)?.bitmap ?: return null
    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        raw.config == Bitmap.Config.HARDWARE
    ) {
        raw.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        raw
    } ?: return null
    return CropImageTarget(
        imageBitmap = bitmap.asImageBitmap(),
        width = bitmap.width,
        height = bitmap.height,
        getPixelRgb = { x, y -> bitmap.getPixel(x, y) },
    )
}
