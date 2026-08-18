package com.comics8.core.source

import com.comics8.core.source.hitomi.HitomiUrls
import java.io.File

object DownloadLayout {
    private val sourceDirNames: Set<String> = setOf(WorkId.DEFAULT_SOURCE, HitomiUrls.SOURCE_ID)

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
        if (hasImages(neu)) return neu
        val legacy = legacyEpisodeDir(base, workId.toonId, wrId)
        if (hasImages(legacy)) return legacy
        if (!storedPath.isNullOrBlank()) {
            val stored = File(storedPath)
            if (hasImages(stored)) return stored
        }
        return null
    }

    /**
     * Moves `{toonId}/{wrId}` under `{base}/eleven/` when the destination is empty.
     * Existing `eleven/` and `hitomi/` trees are left untouched.
     */
    fun migrateLegacyElevenDirs(base: File): List<MigratedDownloadDir> {
        if (!base.isDirectory) return emptyList()
        val results = mutableListOf<MigratedDownloadDir>()
        val top = base.listFiles { file -> file.isDirectory } ?: return emptyList()
        for (toonDir in top) {
            if (toonDir.name in sourceDirNames) continue
            val workId = WorkId.eleven(toonDir.name)
            val destToon = toonDir(base, workId)
            val wrDirs = toonDir.listFiles { file -> file.isDirectory } ?: emptyArray()
            if (wrDirs.isEmpty()) continue

            if (!destToon.exists()) {
                destToon.parentFile?.mkdirs()
                val moved = moveDir(toonDir, destToon)
                for (wrDir in wrDirs) {
                    results.add(MigratedDownloadDir(wrDir, File(destToon, wrDir.name), moved))
                }
                continue
            }

            for (wrDir in wrDirs) {
                val dest = episodeDir(base, workId, wrDir.name)
                if (hasImages(dest)) {
                    results.add(MigratedDownloadDir(wrDir, dest, moved = false))
                    continue
                }
                if (dest.exists()) dest.deleteRecursively()
                dest.parentFile?.mkdirs()
                val moved = moveDir(wrDir, dest)
                results.add(MigratedDownloadDir(wrDir, dest, moved))
            }
            val leftover = toonDir.listFiles()
            if (leftover.isNullOrEmpty()) toonDir.delete()
        }
        return results
    }

    fun migrateLegacyEpisode(base: File, toonId: String, wrId: String): File? {
        val workId = WorkId.eleven(toonId)
        val dest = episodeDir(base, workId, wrId)
        if (hasImages(dest)) return dest
        val legacy = legacyEpisodeDir(base, toonId, wrId)
        if (!legacy.exists()) return if (dest.exists()) dest else null
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        return if (moveDir(legacy, dest)) dest else legacy
    }

    internal fun hasImages(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return dir.listFiles { file -> file.isFile && file.length() > 0 }?.isNotEmpty() == true
    }

    private fun moveDir(from: File, to: File): Boolean {
        if (!from.exists()) return false
        if (from.renameTo(to)) return true
        return try {
            from.copyRecursively(to, overwrite = false)
            from.deleteRecursively()
            to.exists()
        } catch (_: Exception) {
            false
        }
    }
}

data class MigratedDownloadDir(
    val from: File,
    val to: File,
    val moved: Boolean,
)
