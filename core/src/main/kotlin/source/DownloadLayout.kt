package com.comics8.core.source

import java.io.File

object DownloadLayout {
    fun episodeDir(base: File, workId: WorkId, wrId: String): File =
        File(base, "${workId.sourceId}/${workId.toonId}/$wrId")

    fun toonDir(base: File, workId: WorkId): File =
        File(base, "${workId.sourceId}/${workId.toonId}")

    fun resolveEpisodeDir(base: File, workId: WorkId, wrId: String, storedPath: String? = null): File? {
        val dir = episodeDir(base, workId, wrId)
        if (hasImages(dir)) return dir
        if (!storedPath.isNullOrBlank()) {
            val stored = File(storedPath)
            if (hasImages(stored)) return stored
        }
        return null
    }

    internal fun hasImages(dir: File): Boolean = imageCount(dir) > 0

    internal fun imageCount(dir: File): Int {
        if (!dir.isDirectory) return 0
        return dir.listFiles { file ->
            file.isFile && file.length() > 0 && isImageFile(file.name)
        }?.size ?: 0
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return IMAGE_EXTENSIONS.any { lower.endsWith(it) }
    }
}

private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif")

