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
            "jpeg-out".toByteArray()
        }
        val cache = CoverThumbCache(dir, encoder, longEdgePx = 320, quality = 80)
        val key = ThumbKey("/comics/cover.jpg", mtimeEpochMs = 100L, sizeBytes = 12L)
        val created = cache.getOrCreate(key) { "full-image".toByteArray() }

        assertThat(created.name).matches("[0-9a-f]{64}\\.jpg")
        assertThat(created.readBytes().toString(Charsets.UTF_8)).isEqualTo("jpeg-out")
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
        assertThat(dir.listFiles { f -> f.extension == "jpg" }!!.size).isEqualTo(3)
    }

    @Test
    fun sourceDoesNotUseImageIo() {
        val text = LocalTestZips.sourceFile("CoverThumbCache.kt").readText()
        assertThat(text).doesNotContain("ImageIO")
        assertThat(text).doesNotContain("javax.imageio")
        assertThat(text).contains("SHA-256")
    }
}
