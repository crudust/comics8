package com.comics8.core.source.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class CoverThumbCacheTest {
    @Test
    fun hashesPathMtimeSizeAndWritesEncoderBytes() {
        val dir = createTempDirectory("thumbs").toFile()
        dir.deleteOnExit()
        val received = mutableListOf<Triple<ByteArray, Int, Int>>()
        val encoder = ThumbEncoder { bytes, longEdgePx, quality ->
            received += Triple(bytes, longEdgePx, quality)
            "webp-out".toByteArray()
        }
        val cache = CoverThumbCache(dir, encoder, longEdgePx = 320, quality = 80)
        val key = ThumbKey("/comics/cover.jpg", mtimeEpochMs = 100L, sizeBytes = 12L)
        val created = cache.getOrCreate(key) { "full-image".toByteArray() }

        assertThat(created.name).matches("[0-9a-f]{64}\\.webp")
        assertThat(created.readBytes().toString(Charsets.UTF_8)).isEqualTo("webp-out")
        assertThat(received).hasSize(1)
        assertThat(received[0].first.toString(Charsets.UTF_8)).isEqualTo("full-image")
        assertThat(received[0].second).isEqualTo(320)
        assertThat(received[0].third).isEqualTo(80)
        assertThat(cache.fileFor(key)).isEqualTo(created)

        val reused = cache.getOrCreate(key) { error("decode should not run on cache hit") }
        assertThat(reused).isEqualTo(created)
        assertThat(received).hasSize(1)
    }

    @Test
    fun regeneratesWhenMtimeOrSizeChanges() {
        val dir = createTempDirectory("thumbs-miss").toFile()
        dir.deleteOnExit()
        var encodes = 0
        val encoder = ThumbEncoder { bytes, _, _ ->
            encodes += 1
            bytes
        }
        val cache = CoverThumbCache(dir, encoder)
        cache.getOrCreate(ThumbKey("/a.jpg", 1L, 1L)) { byteArrayOf(1) }
        cache.getOrCreate(ThumbKey("/a.jpg", 2L, 1L)) { byteArrayOf(2) }
        cache.getOrCreate(ThumbKey("/a.jpg", 2L, 9L)) { byteArrayOf(3) }
        assertThat(encodes).isEqualTo(3)
        assertThat(dir.listFiles { f -> f.extension == "webp" }!!.size).isEqualTo(3)
    }

    @Test
    fun keepsSeparateSizeVariants() {
        val dir = createTempDirectory("thumbs-sizes").toFile()
        val sizes = mutableListOf<Int>()
        val cache = CoverThumbCache(dir, ThumbEncoder { _, size, _ ->
            sizes += size
            byteArrayOf(size.toByte())
        })
        val key = ThumbKey("/cover.jpg", 1L, 1L)

        val grid = cache.getOrCreate(key, 320) { byteArrayOf(1) }
        val episode = cache.getOrCreate(key, 192) { byteArrayOf(1) }

        assertThat(grid).isNotEqualTo(episode)
        assertThat(sizes).containsExactly(320, 192)
    }

    @Test
    fun cancellationDoesNotPoisonFailureCache() {
        val dir = createTempDirectory("thumbs-cancel").toFile()
        var attempts = 0
        val cache = CoverThumbCache(dir, ThumbEncoder { bytes, _, _ -> bytes })
        val key = ThumbKey("/cancelled.jpg", 1L, 1L)

        val cancelled = runCatching {
            cache.getOrCreate(key) {
                attempts++
                throw InterruptedException("cancelled")
            }
        }.exceptionOrNull()
        val created = cache.getOrCreate(key) {
            attempts++
            byteArrayOf(1)
        }

        assertThat(cancelled).isInstanceOf(InterruptedException::class.java)
        assertThat(created.isFile).isTrue()
        assertThat(attempts).isEqualTo(2)
        assertThat(dir.listFiles().orEmpty().none { it.extension == "fail" }).isTrue()
    }

    @Test
    fun evictsLeastRecentlyUsedThumbsWhenCapacityIsExceeded() {
        val dir = createTempDirectory("thumbs-lru").toFile()
        dir.deleteOnExit()
        val cache = CoverThumbCache(
            dir = dir,
            encoder = ThumbEncoder { bytes, _, _ -> bytes },
            maxSizeBytes = 10L,
            targetSizeBytes = 6L,
            evictionInterval = 1,
        )
        val first = cache.getOrCreate(ThumbKey("/first", 1L, 1L)) { ByteArray(4) { 1 } }
        first.setLastModified(1L)
        val second = cache.getOrCreate(ThumbKey("/second", 1L, 1L)) { ByteArray(4) { 2 } }
        second.setLastModified(2L)
        val third = cache.getOrCreate(ThumbKey("/third", 1L, 1L)) { ByteArray(4) { 3 } }

        assertThat(first.exists()).isFalse()
        assertThat(second.exists()).isFalse()
        assertThat(third.exists()).isTrue()
    }

    @Test
    fun clearFailuresRemovesFailedMarkersAndAllowsRetry() {
        val dir = createTempDirectory("thumbs-clear-fail").toFile()
        dir.deleteOnExit()
        var attempts = 0
        val cache = CoverThumbCache(dir, ThumbEncoder { bytes, _, _ -> bytes })
        val key = ThumbKey("/failing.jpg", 1L, 1L)

        runCatching {
            cache.getOrCreate(key) {
                attempts++
                error("simulated network/IO error")
            }
        }
        assertThat(attempts).isEqualTo(1)

        // Second attempt fails immediately due to .fail marker
        val immediateFail = runCatching {
            cache.getOrCreate(key) {
                attempts++
                byteArrayOf(1)
            }
        }.exceptionOrNull()
        assertThat(immediateFail).hasMessageThat().contains("recently failed")
        assertThat(attempts).isEqualTo(1)

        // Clear failures
        cache.clearFailures()

        // After clearing, retry succeeds
        val created = cache.getOrCreate(key) {
            attempts++
            byteArrayOf(1)
        }
        assertThat(attempts).isEqualTo(2)
        assertThat(created.isFile).isTrue()
    }

    @Test
    fun forceRetryBypassesFailureMarker() {
        val dir = createTempDirectory("thumbs-force-retry").toFile()
        dir.deleteOnExit()
        var attempts = 0
        val cache = CoverThumbCache(dir, ThumbEncoder { bytes, _, _ -> bytes })
        val key = ThumbKey("/force.jpg", 1L, 1L)

        runCatching {
            cache.getOrCreate(key) {
                attempts++
                error("initial error")
            }
        }
        assertThat(attempts).isEqualTo(1)

        // Force retry succeeds
        val created = cache.getOrCreate(key, requestedLongEdgePx = 320, forceRetry = true) {
            attempts++
            byteArrayOf(1)
        }
        assertThat(attempts).isEqualTo(2)
        assertThat(created.isFile).isTrue()
    }

    @Test
    fun sourceDoesNotUseImageIo() {
        val text = LocalTestZips.sourceFile("CoverThumbCache.kt").readText()
        assertThat(text).doesNotContain("ImageIO")
        assertThat(text).doesNotContain("javax.imageio")
        assertThat(text).contains("SHA-256")
    }
}

