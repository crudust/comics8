package com.comics8.core.source.local

import java.io.File
import java.security.MessageDigest

data class ThumbKey(val path: String, val mtimeEpochMs: Long, val sizeBytes: Long)

class CoverThumbCache(
    private val dir: File,
    private val encoder: ThumbEncoder,
    private val longEdgePx: Int = 480,
    private val quality: Int = 80,
    private val maxSizeBytes: Long = 100L * 1024L * 1024L,
    private val targetSizeBytes: Long = maxSizeBytes * 4L / 5L,
) {
    init {
        require(maxSizeBytes > 0L) { "maxSizeBytes must be positive" }
        require(targetSizeBytes in 0..maxSizeBytes) { "targetSizeBytes must not exceed maxSizeBytes" }
    }

    fun fileFor(key: ThumbKey): File = File(dir, "${hash(key)}.webp")

    @Synchronized
    fun getOrCreate(key: ThumbKey, decodeFull: () -> ByteArray): File {
        val dest = fileFor(key)
        if (dest.isFile && dest.length() > 0L) {
            dest.setLastModified(System.currentTimeMillis())
            return dest
        }
        dir.mkdirs()
        val encoded = encoder.webp(decodeFull(), longEdgePx, quality)
        require(encoded.isNotEmpty()) { "webp encode failed" }
        val part = File(dir, "${dest.name}.part")
        part.writeBytes(encoded)
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        evictIfNeeded(dest)
        return dest
    }

    @Synchronized
    fun trim() = evictIfNeeded(null)

    private fun evictIfNeeded(keep: File?) {
        val allFiles = dir.listFiles()?.filter(File::isFile) ?: return
        allFiles.filter { it.extension == "jpg" || it.extension == "part" }.forEach(File::delete)
        val files = allFiles.filter { it.extension == "webp" }
        var total = files.sumOf(File::length)
        if (total <= maxSizeBytes) return
        val keepPath = keep?.absolutePath
        for (file in files.filter { it.absolutePath != keepPath }.sortedBy(File::lastModified)) {
            if (total <= targetSizeBytes) break
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun hash(key: ThumbKey): String {
        val raw = "${key.path}|${key.mtimeEpochMs}|${key.sizeBytes}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }
}
