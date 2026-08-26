package com.comics8.core.source.local

import java.io.File

enum class LocalWorkKind { ZIP, DIR, SERIES }

enum class LocalEpisodeKind { ZIP, DIR }

data class ScannedWork(
    val kind: LocalWorkKind,
    val toonId: String,
    val title: String,
    val path: File,
    val episodes: List<ScannedEpisode>,
)

data class ScannedEpisode(
    val kind: LocalEpisodeKind,
    val wrId: String,
    val title: String,
    val path: File,
)

object LocalWorkId {
    fun zip(file: File): String = "zip:${canonical(file)}"
    fun dir(file: File): String = "dir:${canonical(file)}"
    fun series(file: File): String = "series:${canonical(file)}"
    fun episode(file: File): String = canonical(file)

    fun canonical(file: File): String =
        try {
            file.canonicalPath
        } catch (_: Exception) {
            file.absolutePath
        }
}

class LibraryScanner {
    fun scan(roots: Iterable<File>): List<ScannedWork> = roots.flatMap { scan(it) }

    fun scan(root: File): List<ScannedWork> {
        if (!root.isDirectory) return emptyList()
        val children = listed(root) ?: return emptyList()
        val works = ArrayList<ScannedWork>()
        for (child in children.sortedWith(compareBy(NaturalSort) { it.name })) {
            if (ZipImageNames.isJunkName(child.name)) continue
            if (child.isFile && ZipImageNames.isZipName(child.name)) {
                works += zipWork(child)
                continue
            }
            if (child.isDirectory) {
                classifyFolder(child)?.let { works += it }
            }
        }
        return works
    }

    fun listFolderImages(dir: File): List<File> {
        val files = listed(dir) ?: return emptyList()
        return files
            .filter { it.isFile && ZipImageNames.isImageEntry(it.name) }
            .sortedWith(compareBy(NaturalSort) { it.name })
    }

    private fun classifyFolder(dir: File): ScannedWork? {
        val children = listed(dir) ?: return null
        val visible = children.filterNot { ZipImageNames.isJunkName(it.name) }
        val zips = visible.filter { it.isFile && ZipImageNames.isZipName(it.name) }
        val imageFolders = visible.filter { it.isDirectory && hasDirectImages(it) }
        if (zips.isNotEmpty() || imageFolders.isNotEmpty()) {
            val episodes = ArrayList<ScannedEpisode>(zips.size + imageFolders.size)
            for (zip in zips) episodes += zipEpisode(zip)
            for (folder in imageFolders) episodes += dirEpisode(folder)
            episodes.sortWith(compareBy(NaturalSort) { it.path.name })
            return ScannedWork(
                kind = LocalWorkKind.SERIES,
                toonId = LocalWorkId.series(dir),
                title = dir.name,
                path = dir,
                episodes = episodes,
            )
        }
        if (hasDirectImages(dir)) {
            return dirWork(dir)
        }
        return null
    }

    private fun zipWork(file: File): ScannedWork {
        val episode = zipEpisode(file)
        return ScannedWork(
            kind = LocalWorkKind.ZIP,
            toonId = LocalWorkId.zip(file),
            title = stem(file.name),
            path = file,
            episodes = listOf(episode),
        )
    }

    private fun dirWork(dir: File): ScannedWork =
        ScannedWork(
            kind = LocalWorkKind.DIR,
            toonId = LocalWorkId.dir(dir),
            title = dir.name,
            path = dir,
            episodes = listOf(dirEpisode(dir)),
        )

    private fun zipEpisode(file: File): ScannedEpisode =
        ScannedEpisode(
            kind = LocalEpisodeKind.ZIP,
            wrId = LocalWorkId.episode(file),
            title = stem(file.name),
            path = file,
        )

    private fun dirEpisode(dir: File): ScannedEpisode =
        ScannedEpisode(
            kind = LocalEpisodeKind.DIR,
            wrId = LocalWorkId.episode(dir),
            title = dir.name,
            path = dir,
        )

    private fun hasDirectImages(dir: File): Boolean {
        val files = listed(dir) ?: return false
        return files.any { it.isFile && ZipImageNames.isImageEntry(it.name) }
    }

    private fun listed(dir: File): Array<File>? = dir.listFiles()

    private fun stem(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}
