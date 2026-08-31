package com.comics8.core.image

import org.glavo.avif.AvifImage

data class DecodedAvif(
    val width: Int,
    val height: Int,
    val argbPixels: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedAvif) return false
        return width == other.width && height == other.height && argbPixels.contentEquals(other.argbPixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + argbPixels.contentHashCode()
        return result
    }
}

object AvifDecoder {
    fun isAvif(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        val isFtyp = bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
            bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte()
        if (!isFtyp) return false
        val limit = minOf(bytes.size, 64)
        val header = String(bytes, 8, limit - 8, Charsets.US_ASCII)
        return "avif" in header || "avis" in header || "mif1" in header
    }

    fun decode(bytes: ByteArray): DecodedAvif? = runCatching {
        val image = AvifImage.read(bytes)
        val frame = image.firstFrame()
        val w = frame.width()
        val h = frame.height()
        val pixels = frame.intPixels()
        require(w > 0 && h > 0 && pixels.size == w * h) { "Invalid frame dimensions or pixel buffer" }
        DecodedAvif(w, h, pixels)
    }.getOrNull()
}
