package com.comics8.shared.image

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.size.Dimension
import com.comics8.core.image.AvifDecoder
import com.comics8.core.model.ImageTile
import com.comics8.core.model.ReaderTiles
import com.comics8.core.source.local.ThumbEncoder
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.roundToInt

object SkiaThumbEncoder : ThumbEncoder {
    override fun webp(bytes: ByteArray, longEdgePx: Int, quality: Int): ByteArray {
        val q = quality.coerceIn(1, 100)
        val src = decodeToSkiaImage(bytes) ?: error("decode failed")
        src.use {
            val w = src.width.coerceAtLeast(1)
            val h = src.height.coerceAtLeast(1)
            val longEdge = maxOf(w, h)
            val scale = if (longEdge <= longEdgePx) 1.0 else longEdgePx.toDouble() / longEdge
            val tw = (w * scale).toInt().coerceAtLeast(1)
            val th = (h * scale).toInt().coerceAtLeast(1)
            return if (tw == w && th == h) {
                src.encodeWebp(q)
            } else {
                Surface.makeRasterN32Premul(tw, th).use { surface ->
                    surface.canvas.clear(0xFFFFFFFF.toInt())
                    surface.canvas.drawImageRect(
                        src,
                        Rect.makeWH(w.toFloat(), h.toFloat()),
                        Rect.makeWH(tw.toFloat(), th.toFloat()),
                        SamplingMode.LINEAR,
                        null,
                        true,
                    )
                    surface.makeImageSnapshot().use { it.encodeWebp(q) }
                }
            }
        }
    }

    private fun Image.encodeWebp(quality: Int): ByteArray {
        val data = encodeToData(EncodedImageFormat.WEBP, quality) ?: error("webp encode failed")
        return data.use { it.bytes }
    }
}

fun decodeToSkiaImage(bytes: ByteArray): Image? =
    try {
        Image.makeFromEncoded(bytes)
    } catch (_: Throwable) {
        decodeAvifToSkiaImage(bytes)
    }

fun decodeAvifToSkiaImage(bytes: ByteArray): Image? {
    val decoded = AvifDecoder.decode(bytes) ?: return null
    val w = decoded.width
    val h = decoded.height
    val rgba = argbToRgba(decoded.argbPixels, w, h, sample = 1)
    val info = ImageInfo(
        width = w,
        height = h,
        colorType = ColorType.RGBA_8888,
        alphaType = ColorAlphaType.UNPREMUL,
    )
    return Image.makeRaster(info, rgba, w * 4)
}

private fun argbToRgba(
    argb: IntArray,
    srcWidth: Int,
    srcHeight: Int,
    sample: Int,
): ByteArray {
    val dstW = (srcWidth / sample).coerceAtLeast(1)
    val dstH = (srcHeight / sample).coerceAtLeast(1)
    val rgba = ByteArray(dstW * dstH * 4)
    var dstIdx = 0
    for (y in 0 until dstH) {
        val srcRowOffset = (y * sample) * srcWidth
        for (x in 0 until dstW) {
            val pixel = argb[srcRowOffset + (x * sample)]
            rgba[dstIdx++] = ((pixel ushr 16) and 0xFF).toByte()
            rgba[dstIdx++] = ((pixel ushr 8) and 0xFF).toByte()
            rgba[dstIdx++] = (pixel and 0xFF).toByte()
            rgba[dstIdx++] = ((pixel ushr 24) and 0xFF).toByte()
        }
    }
    return rgba
}

class SkiaAvifCoilDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val decoded = AvifDecoder.decode(bytes) ?: error("Failed to decode AVIF")

        val maxW = (options.maxBitmapSize.width as? Dimension.Pixels)?.px
        val maxH = (options.maxBitmapSize.height as? Dimension.Pixels)?.px
        val dstWidth = listOfNotNull(
            (options.size.width as? Dimension.Pixels)?.px,
            maxW,
        ).minOrNull() ?: decoded.width
        val dstHeight = listOfNotNull(
            (options.size.height as? Dimension.Pixels)?.px,
            maxH,
        ).minOrNull() ?: decoded.height

        val inSampleSize = DecodeUtils.calculateInSampleSize(
            srcWidth = decoded.width,
            srcHeight = decoded.height,
            dstWidth = dstWidth,
            dstHeight = dstHeight,
            scale = options.scale,
        ).coerceAtLeast(1)

        val sampledW = (decoded.width / inSampleSize).coerceAtLeast(1)
        val sampledH = (decoded.height / inSampleSize).coerceAtLeast(1)
        val rgba = argbToRgba(decoded.argbPixels, decoded.width, decoded.height, inSampleSize)
        val info = ImageInfo(
            width = sampledW,
            height = sampledH,
            colorType = ColorType.RGBA_8888,
            alphaType = ColorAlphaType.UNPREMUL,
        )
        val bitmap = Bitmap().apply {
            allocPixels(info)
            installPixels(info, rgba, sampledW * 4)
        }
        return DecodeResult(
            image = bitmap.asImage(shareable = false),
            isSampled = inSampleSize > 1,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            if (result.mimeType.equals("image/avif", ignoreCase = true)) {
                return SkiaAvifCoilDecoder(result.source, options)
            }
            val peek = result.source.source().peek()
            val header = ByteArray(64)
            val read = peek.read(header)
            if (read >= 12 && AvifDecoder.isAvif(if (read == header.size) header else header.copyOf(read))) {
                return SkiaAvifCoilDecoder(result.source, options)
            }
            return null
        }
    }
}

/**
 * Decodes only the requested vertical slice of a page image on Skia (Desktop) using Coil 3's
 * [ReaderTileKey] request extra.
 */
class SkiaReaderTileDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val tile: ImageTile,
) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val src = decodeToSkiaImage(bytes) ?: error("Failed to decode image slice")
        return src.use {
            val sourceWidth = src.width.coerceAtLeast(1)
            val sourceHeight = src.height.coerceAtLeast(1)
            val rows = ReaderTiles.sourceRowRange(tile.tileIndex, tile.tileCount, sourceHeight)
            if (rows.isEmpty()) error("Empty image slice")

            val rowCount = rows.count()
            val widthScale = maxWidthPx().let { maxW ->
                if (sourceWidth > maxW) maxW.toFloat() / sourceWidth else 1.0f
            }
            val heightScale = if (rowCount > MAX_TILE_HEIGHT_PX) {
                MAX_TILE_HEIGHT_PX.toFloat() / rowCount
            } else {
                1.0f
            }
            val scale = minOf(widthScale, heightScale).coerceAtMost(1.0f)
            val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (rowCount * scale).roundToInt().coerceAtLeast(1)

            val bitmap = Surface.makeRasterN32Premul(targetWidth, targetHeight).use { surface ->
                surface.canvas.clear(0xFFFFFFFF.toInt())
                surface.canvas.drawImageRect(
                    src,
                    Rect.makeLTRB(0f, rows.first.toFloat(), sourceWidth.toFloat(), (rows.last + 1).toFloat()),
                    Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()),
                    SamplingMode.LINEAR,
                    null,
                    true,
                )
                surface.makeImageSnapshot().use { snapshot ->
                    Bitmap.makeFromImage(snapshot)
                }
            }
            DecodeResult(
                image = bitmap.asImage(shareable = false),
                isSampled = scale < 1.0f,
            )
        }
    }

    private fun maxWidthPx(): Int {
        val width = (options.maxBitmapSize.width as? Dimension.Pixels)?.px ?: return Int.MAX_VALUE
        return width.coerceAtLeast(1)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val tile = options.extras[ReaderTileKey] ?: return null
            val mimeType = result.mimeType?.lowercase()
            if (mimeType != null && mimeType !in REGION_DECODABLE_MIME_TYPES) return null
            return SkiaReaderTileDecoder(result.source, options, tile)
        }
    }

    private companion object {
        const val MAX_TILE_HEIGHT_PX = 4096
        val REGION_DECODABLE_MIME_TYPES = setOf("image/jpeg", "image/jpg", "image/png", "image/webp", "image/avif")
    }
}
