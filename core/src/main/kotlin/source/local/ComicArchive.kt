package com.comics8.core.source.local

import java.io.Closeable
import java.io.File
import java.io.InputStream

interface ComicArchive : Closeable {
    fun imageEntries(): List<String>
    fun firstImageEntry(): String?
    fun open(entryName: String): InputStream

    companion object {
        fun open(file: File): ComicArchive =
            if (ZipImageNames.isRarName(file.name)) RarArchive(file) else ZipArchive(file)
    }
}
