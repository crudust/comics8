package com.comics8.core.source.local

fun interface ThumbEncoder {
    fun webp(bytes: ByteArray, longEdgePx: Int, quality: Int): ByteArray
}
