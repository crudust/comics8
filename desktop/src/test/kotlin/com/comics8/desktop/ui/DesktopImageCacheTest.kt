package com.comics8.desktop.ui

import com.comics8.core.source.LocalImageUri
import com.comics8.desktop.ui.util.DesktopImageCache
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DesktopImageCacheTest {
    @Test
    fun loadsLocalFileUrlWithColonInPath() = runBlocking<Unit> {
        val root = createTempDirectory("desk-img").toFile()
        root.deleteOnExit()
        val file = File(root, "hitomi/artist:asanagi/0001.jpg")
        file.parentFile.mkdirs()
        file.writeBytes(ONE_PIXEL_PNG)

        val encoded = LocalImageUri.fromFile(file)
        val raw = "file://" + file.absolutePath
        for (url in listOf(encoded, raw)) {
            val bitmap = DesktopImageCache.loadImage(url)
            assertThat(bitmap).isNotNull()
            assertThat(bitmap!!.width).isAtLeast(1)
            assertThat(bitmap.height).isAtLeast(1)
        }
    }

    companion object {
        private val ONE_PIXEL_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53,
            0xDE.toByte(), 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54,
            0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0x00, 0x00,
            0x00, 0x03, 0x00, 0x01, 0x00, 0x05, 0xFE.toByte(), 0xD4.toByte(),
            0xEF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
