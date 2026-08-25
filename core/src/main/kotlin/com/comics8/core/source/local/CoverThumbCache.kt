package com.comics8.core.source.local

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock

data class ThumbKey(val path: String, val mtimeEpochMs: Long, val sizeBytes: Long)

class CoverThumbCache(
    private val dir: File,
    private val encoder: ThumbEncoder,
    private val longEdgePx: Int = 320,
    private val quality: Int = 80,
    private val maxSizeBytes: Long = 100L * 1024L * 1024L,
    private val targetSizeBytes: Long = maxSizeBytes * 4L / 5L,
    private val evictionInterval: Int = 32,
) {
    private val locks = Array(16) { ReentrantLock() }
    private val evictionLock = Any()
    private val creationCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val sha256Digest = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }

    init {
        require(maxSizeBytes > 0L) { "maxSizeBytes must be positive" }
        require(targetSizeBytes in 0..maxSizeBytes) { "targetSizeBytes must not exceed maxSizeBytes" }
        require(evictionInterval > 0) { "evictionInterval must be positive" }
    }

    fun clearFailures() {
        val files = dir.listFiles()?.filter(File::isFile) ?: return
        files.filter { it.extension == "fail" }.forEach(File::delete)
    }

    fun clearFailure(key: ThumbKey, requestedLongEdgePx: Int = longEdgePx) {
        val size = requestedLongEdgePx.coerceIn(64, 1024)
        val dest = fileFor(key, size)
        val failed = File(dir, "${dest.name}.fail")
        failed.delete()
    }

    fun fileFor(key: ThumbKey, requestedLongEdgePx: Int = longEdgePx): File =
        File(dir, "${hash(key, requestedLongEdgePx)}.webp")

    fun getOrCreate(key: ThumbKey, decodeFull: () -> ByteArray): File =
        getOrCreate(key, longEdgePx, forceRetry = false, decodeFull)

    fun getOrCreate(
        key: ThumbKey,
        requestedLongEdgePx: Int,
        forceRetry: Boolean = false,
        decodeFull: () -> ByteArray,
    ): File {
        val size = requestedLongEdgePx.coerceIn(64, 1024)
        val dest = fileFor(key, size)
        val lock = locks[(dest.name.hashCode() and Int.MAX_VALUE) % locks.size]
        lock.lockInterruptibly()
        try {
            return getOrCreateLocked(dest, size, forceRetry, decodeFull)
        } finally {
            lock.unlock()
        }
    }

    private fun getOrCreateLocked(
        dest: File,
        size: Int,
        forceRetry: Boolean,
        decodeFull: () -> ByteArray,
    ): File {
        val failed = File(dir, "${dest.name}.fail")
        val part = File(dir, "${dest.name}.part")
        if (!forceRetry && dest.isFile && dest.length() > 0L) {
            dest.setLastModified(System.currentTimeMillis())
            return dest
        }
        if (forceRetry) {
            failed.delete()
        } else if (failed.isFile) {
            val currentMarker = runCatching { failed.readText(Charsets.UTF_8) == FAILURE_MARKER }.getOrDefault(false)
            if (currentMarker && System.currentTimeMillis() - failed.lastModified() < FAILURE_TTL_MS) {
                error("thumbnail generation recently failed")
            }
            failed.delete()
        }
        dir.mkdirs()
        try {
            val encoded = encoder.webp(decodeFull(), size, quality)
            require(encoded.isNotEmpty()) { "webp encode failed" }
            part.writeBytes(encoded)
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            failed.delete()
            if (creationCounter.incrementAndGet() % evictionInterval == 0) {
                synchronized(evictionLock) { evictIfNeeded(dest) }
            }
            return dest
        } catch (e: Exception) {
            part.delete()
            if (!e.isCancellation()) {
                runCatching { failed.writeText(FAILURE_MARKER, Charsets.UTF_8) }
            }
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
        val md = sha256Digest.get()
        md.reset()
        val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }

    companion object {
        private const val FAILURE_TTL_MS = 30L * 1000L
        private const val FAILURE_MARKER = "v2"
    }
}

private fun Throwable.isCancellation(): Boolean {
    if (this is InterruptedException || this is CancellationException || Thread.currentThread().isInterrupted) {
        return true
    }
    var current = cause
    var depth = 0
    while (current != null && current !== this && depth++ < 16) {
        if (current is InterruptedException || current is CancellationException) return true
        current = current.cause
    }
    return false
}
