package com.comics8.monitor.data.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.comics8.core.source.local.ThumbEncoder
import java.io.ByteArrayOutputStream

object BitmapThumbEncoder : ThumbEncoder {
    override fun webp(bytes: ByteArray, longEdgePx: Int, quality: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        val decoded = if (srcW > 0 && srcH > 0) {
            val longEdge = maxOf(srcW, srcH)
            var sample = 1
            while (sample < 32 && longEdge / (sample * 2) >= longEdgePx) {
                sample *= 2
            }
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } else {
            null
        } ?: run {
            val avif = com.comics8.core.image.AvifDecoder.decode(bytes) ?: error("decode failed")
            Bitmap.createBitmap(avif.argbPixels, avif.width, avif.height, Bitmap.Config.ARGB_8888)
        }
        var scaled: Bitmap? = null
        try {
            val dw = decoded.width
            val dh = decoded.height
            val currentLong = maxOf(dw, dh)
            val bmp = if (currentLong <= longEdgePx) {
                decoded
            } else {
                val scale = longEdgePx.toFloat() / currentLong
                val tw = (dw * scale).toInt().coerceAtLeast(1)
                val th = (dh * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, tw, th, true).also { created ->
                    if (created != decoded) scaled = created
                }
            }
            val out = ByteArrayOutputStream()
            @Suppress("DEPRECATION")
            val format = if (Build.VERSION.SDK_INT >= 30) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            val ok = bmp.compress(format, quality.coerceIn(1, 100), out)
            if (!ok) error("webp encode failed")
            return out.toByteArray()
        } finally {
            scaled?.recycle()
            decoded.recycle()
        }
    }
}
