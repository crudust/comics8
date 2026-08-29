package com.comics8.desktop.data

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class DesktopUpdateManagerTest {
    @Test
    fun unzipExtractsFilesInsideTarget() {
        val root = createTempDirectory("desktop-update").toFile()
        val archive = File(root, "update.zip")
        writeZip(archive, "Comics8/app/data.txt", "ok")
        val target = File(root, "target").apply { mkdirs() }

        DesktopUpdateManager.unzip(archive, target)

        assertThat(File(target, "Comics8/app/data.txt").readText()).isEqualTo("ok")
    }

    @Test
    fun unzipRejectsParentTraversal() {
        val root = createTempDirectory("desktop-update-slip").toFile()
        val archive = File(root, "update.zip")
        writeZip(archive, "../outside.txt", "unsafe")
        val target = File(root, "target").apply { mkdirs() }

        assertThrows(IllegalArgumentException::class.java) {
            DesktopUpdateManager.unzip(archive, target)
        }

        assertThat(File(root, "outside.txt").exists()).isFalse()
    }

    @Test
    fun unzipRejectsWindowsStyleParentTraversal() {
        val root = createTempDirectory("desktop-update-slip-win").toFile()
        val archive = File(root, "update.zip")
        writeZip(archive, "..\\outside.txt", "unsafe")
        val target = File(root, "target").apply { mkdirs() }

        assertThrows(IllegalArgumentException::class.java) {
            DesktopUpdateManager.unzip(archive, target)
        }

        assertThat(File(root, "outside.txt").exists()).isFalse()
    }

    private fun writeZip(archive: File, entryName: String, contents: String) {
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry(entryName))
            output.write(contents.toByteArray())
            output.closeEntry()
        }
    }
}
