package com.comics8.core.source

import java.io.File

object DownloadLayout {
    fun episodeDir(base: File, workId: WorkId, wrId: String): File =
        File(base, "${workId.sourceId}/${workId.toonId}/$wrId")

    fun toonDir(base: File, workId: WorkId): File =
        File(base, "${workId.sourceId}/${workId.toonId}")

    fun legacyEpisodeDir(base: File, toonId: String, wrId: String): File =
        File(base, "$toonId/$wrId")

    fun legacyToonDir(base: File, toonId: String): File =
        File(base, toonId)

    fun resolveEpisodeDir(base: File, workId: WorkId, wrId: String, storedPath: String? = null): File? {
        val neu = episodeDir(base, workId, wrId)
        if (neu.isDirectory) return neu
        val legacy = legacyEpisodeDir(base, workId.toonId, wrId)
        if (legacy.isDirectory) return legacy
        if (!storedPath.isNullOrBlank()) {
            val stored = File(storedPath)
            if (stored.isDirectory) return stored
        }
        return null
    }
}
