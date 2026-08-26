package com.comics8.core.source.local

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object LocalTestZips {
    fun write(file: File, entries: List<Pair<String, String>>) {
        file.parentFile.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zos ->
            for ((name, body) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(body.toByteArray())
                zos.closeEntry()
            }
        }
    }

    /** Tiny payload whose CEN/LFH uncompressed size claims [claimedUncompressedSize]. */
    fun writeClaimedSize(file: File, name: String, body: String, claimedUncompressedSize: Long) {
        write(file, listOf(name to body))
        val bytes = file.readBytes()
        val le = le32(claimedUncompressedSize)
        check(bytes.size > 26 && bytes[0] == 0x50.toByte() && bytes[2] == 0x03.toByte()) {
            "not a zip local header"
        }
        le.copyInto(bytes, destinationOffset = 22)
        val cen = indexOf(bytes, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        check(cen >= 0) { "missing central directory" }
        le.copyInto(bytes, destinationOffset = cen + 24)
        val dd = indexOf(bytes, byteArrayOf(0x50, 0x4b, 0x07, 0x08))
        if (dd in 0 until cen) {
            le.copyInto(bytes, destinationOffset = dd + 8)
        }
        file.writeBytes(bytes)
    }

    private fun le32(value: Long): ByteArray {
        val v = value and 0xffffffffL
        return byteArrayOf(
            (v and 0xff).toByte(),
            ((v shr 8) and 0xff).toByte(),
            ((v shr 16) and 0xff).toByte(),
            ((v shr 24) and 0xff).toByte(),
        )
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    fun sourceFile(name: String): File =
        listOf(
            File("src/main/kotlin/source/local/$name"),
            File("core/src/main/kotlin/source/local/$name"),
        ).first { it.isFile }
}
