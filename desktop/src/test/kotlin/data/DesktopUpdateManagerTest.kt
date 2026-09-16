package com.comics8.desktop.data

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
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

    @Test
    fun unzipSetsExecutablePermissionsForKnownBinaries() {
        val root = createTempDirectory("desktop-update-perms").toFile()
        val archive = File(root, "update.zip")
        val target = File(root, "target").apply { mkdirs() }

        writeZip(archive, "jspawnhelper", "#!/bin/sh\nexit 0\n")

        DesktopUpdateManager.unzip(archive, target)

        val extracted = File(target, "jspawnhelper")
        assertThat(extracted.exists()).isTrue()
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            val perms = Files.getPosixFilePermissions(extracted.toPath())
            assertThat(perms).contains(PosixFilePermission.OWNER_EXECUTE)
        }
    }

    private fun writeZip(archive: File, entryName: String, contents: String) {
        ZipOutputStream(archive.outputStream()).use { output ->
            output.putNextEntry(ZipEntry(entryName))
            output.write(contents.toByteArray())
            output.closeEntry()
        }
    }
}
