package com.comics8.core.image

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class AvifDecoderTest {
    @Test
    fun detectsAndDecodesAvifSample() {
        val testFile = File("/tmp/test_001.avif")
        if (!testFile.exists()) return

        val bytes = testFile.readBytes()
        assertThat(AvifDecoder.isAvif(bytes)).isTrue()

        val decoded = AvifDecoder.decode(bytes)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.width).isEqualTo(2036)
        assertThat(decoded.height).isEqualTo(2880)
        assertThat(decoded.argbPixels.size).isEqualTo(2036 * 2880)
    }

    @Test
    fun returnsFalseForNonAvif() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertThat(AvifDecoder.isAvif(pngBytes)).isFalse()
        assertThat(AvifDecoder.decode(pngBytes)).isNull()
    }
}
