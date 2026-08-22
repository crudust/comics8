package com.comics8.core.source.local

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock

data class ThumbKey(val path: String, val mtimeEpochMs: Long, val sizeBytes: Long)

class CoverThumbCache(
    private val dir: File,
    private val encoder: ThumbEncoder,
    private val longEdgePx: Int = 320,
    private val quality: Int = 80,
    private val maxSizeBytes: Long = 100L * 1024L * 1024L,
    private val targetSizeBytes: Long = maxSizeBytes * 4L / 5L,
) {
    private val locks = Array(16) { ReentrantLock() }
    private val evictionLock = Any()

    init {
        require(maxSizeBytes > 0L) { "maxSizeBytes must be positive" }
        require(targetSizeBytes in 0..maxSizeBytes) { "targetSizeBytes must not exceed maxSizeBytes" }
    }

    fun fileFor(key: ThumbKey, requestedLongEdgePx: Int = longEdgePx): File =
        File(dir, "${hash(key, requestedLongEdgePx)}.webp")

    fun getOrCreate(key: ThumbKey, decodeFull: () -> ByteArray): File =
        getOrCreate(key, longEdgePx, decodeFull)

    fun getOrCreate(key: ThumbKey, requestedLongEdgePx: Int, decodeFull: () -> ByteArray): File {
        val size = requestedLongEdgePx.coerceIn(64, 1024)
        val dest = fileFor(key, size)
        val lock = locks[(dest.name.hashCode() and Int.MAX_VALUE) % locks.size]
        lock.lockInterruptibly()
        try {
            return getOrCreateLocked(dest, size, decodeFull)
        } finally {
            lock.unlock()
        }
    }

    private fun getOrCreateLocked(dest: File, size: Int, decodeFull: () -> ByteArray): File {
        val failed = File(dir, "${dest.name}.fail")
        if (dest.isFile && dest.length() > 0L) {
            dest.setLastModified(System.currentTimeMillis())
            return dest
        }
        if (failed.isFile && System.currentTimeMillis() - failed.lastModified() < FAILURE_TTL_MS) {
            error("thumbnail generation recently failed")
        }
        dir.mkdirs()
        try {
            val encoded = encoder.webp(decodeFull(), size, quality)
            require(encoded.isNotEmpty()) { "webp encode failed" }
            val part = File(dir, "${dest.name}.part")
            part.writeBytes(encoded)
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            failed.delete()
            synchronized(evictionLock) { evictIfNeeded(dest) }
            return dest
        } catch (e: Exception) {
            runCatching { failed.writeText("failed", Charsets.UTF_8) }
            throw e
        }
    }

    fun trim() = synchronized(evictionLock) { evictIfNeeded(null) }

    private fun evictIfNeeded(keep: File?) {
        val allFiles = dir.listFiles()?.filter(File::isFile) ?: return
        allFiles.filter {
            it.extension == "jpg" || it.extension == "part" ||
                it.extension == "fail" && System.currentTimeMillis() - it.lastModified() >= FAILURE_TTL_MS
        }.forEach(File::delete)
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

    private fun hash(key: ThumbKey, requestedLongEdgePx: Int): String {
        val raw = "${key.path}|${key.mtimeEpochMs}|${key.sizeBytes}|$requestedLongEdgePx"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }

    companion object {
        private const val FAILURE_TTL_MS = 10L * 60L * 1000L
    }
}
