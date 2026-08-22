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

    fun sourceFile(name: String): File =
        listOf(
            File("src/main/kotlin/com/comics8/core/source/local/$name"),
            File("core/src/main/kotlin/com/comics8/core/source/local/$name"),
        ).first { it.isFile }
}
