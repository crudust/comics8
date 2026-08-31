package com.comics8.monitor.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import com.comics8.core.image.AvifDecoder

class AvifCoilDecoder(
    private val source: ImageSource,
    private val context: Context,
) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val decoded = AvifDecoder.decode(bytes) ?: error("Failed to decode AVIF")
        val bitmap = Bitmap.createBitmap(decoded.argbPixels, decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        return DecodeResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            isSampled = false,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            if (result.mimeType.equals("image/avif", ignoreCase = true)) {
                return AvifCoilDecoder(result.source, options.context)
            }
            val peek = result.source.source().peek()
            val header = ByteArray(64)
            val read = peek.read(header)
            if (read >= 12 && AvifDecoder.isAvif(if (read == header.size) header else header.copyOf(read))) {
                return AvifCoilDecoder(result.source, options.context)
            }
            return null
        }
    }
}
