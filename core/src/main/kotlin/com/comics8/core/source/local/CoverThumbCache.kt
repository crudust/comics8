package com.comics8.core.source.local

import java.io.File
import java.security.MessageDigest

data class ThumbKey(val path: String, val mtimeEpochMs: Long, val sizeBytes: Long)

class CoverThumbCache(
    private val dir: File,
    private val encoder: ThumbEncoder,
    private val longEdgePx: Int = 320,
    private val quality: Int = 80,
) {
    fun fileFor(key: ThumbKey): File = File(dir, "${hash(key)}.jpg")

    fun getOrCreate(key: ThumbKey, decodeFull: () -> ByteArray): File {
        val dest = fileFor(key)
        if (dest.isFile && dest.length() > 0L) return dest
        dir.mkdirs()
        dest.writeBytes(encoder.jpeg(decodeFull(), longEdgePx, quality))
        return dest
    }

    private fun hash(key: ThumbKey): String {
        val raw = "${key.path}|${key.mtimeEpochMs}|${key.sizeBytes}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }
}
