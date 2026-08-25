package com.comics8.core.source

import java.io.File

object DownloadLayout {
    private val SOURCE_DIR_NAMES = setOf("eleven", "hitomi", "local")

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
        val legacy = legacyEpisodeDir(base, workId.toonId, wrId)
        val neuCount = imageCount(neu)
        val legacyCount = imageCount(legacy)
        if (neuCount > 0 && neuCount >= legacyCount) return neu
        if (legacyCount > 0) return legacy
        if (!storedPath.isNullOrBlank()) {
            val stored = File(storedPath)
            if (hasImages(stored)) return stored
        }
        return null
    }

    /**
     * Moves `{toonId}/{wrId}` under `{base}/eleven/` when the destination is empty.
     * Existing `eleven/`, `hitomi/`, and `local/` trees are left untouched.
     */
    fun migrateLegacyElevenDirs(base: File): List<MigratedDownloadDir> {
        if (!base.isDirectory) return emptyList()
        val results = mutableListOf<MigratedDownloadDir>()
        val top = base.listFiles { file -> file.isDirectory } ?: return emptyList()
        for (toonDir in top) {
            if (toonDir.name in SOURCE_DIR_NAMES) continue
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
                val destCount = imageCount(dest)
                val srcCount = imageCount(wrDir)
                if (destCount > 0 && destCount >= srcCount) {
                    results.add(MigratedDownloadDir(wrDir, dest, moved = false))
                    continue
                }
                if (destCount > 0 && destCount < srcCount) {
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
        val legacy = legacyEpisodeDir(base, toonId, wrId)
        val destCount = imageCount(dest)
        val legacyCount = imageCount(legacy)
        if (destCount > 0 && destCount >= legacyCount) return dest
        if (legacyCount == 0) return dest.takeIf { destCount > 0 }
        if (destCount > 0) return legacy
        if (dest.exists()) dest.deleteRecursively()
        dest.parentFile?.mkdirs()
        return if (moveDir(legacy, dest)) dest else legacy
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

    internal fun moveDir(from: File, to: File): Boolean {
        if (!from.exists()) return false
        if (from.renameTo(to)) return true
        val destHadImages = hasImages(to)
        return try {
            from.copyRecursively(to, overwrite = false)
            val sourceCount = imageCount(from)
            if (sourceCount > 0 && imageCount(to) < sourceCount) {
                if (!destHadImages) to.deleteRecursively()
                return false
            }
            from.deleteRecursively()
            hasImages(to)
        } catch (_: Exception) {
            if (!destHadImages) to.deleteRecursively()
            false
        }
    }
}

private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif")

data class MigratedDownloadDir(
    val from: File,
    val to: File,
    val moved: Boolean,
)
