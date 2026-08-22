package com.comics8.core.source.local

fun interface ThumbEncoder {
    fun jpeg(bytes: ByteArray, longEdgePx: Int, quality: Int): ByteArray
}
