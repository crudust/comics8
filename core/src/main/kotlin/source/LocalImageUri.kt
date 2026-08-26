package com.comics8.core.source

import java.io.File
import java.net.URI

object LocalImageUri {
    fun fromFile(file: File): String = file.toURI().toString()

    fun toFile(url: String): File? {
        if (!url.startsWith("file:", ignoreCase = true)) return null
        return try {
            File(URI(url))
        } catch (_: Exception) {
            val raw = url.substringAfter("file:").removePrefix("//")
            File(raw)
        }
    }
}
