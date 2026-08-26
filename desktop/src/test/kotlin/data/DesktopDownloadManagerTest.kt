package com.comics8.desktop.data

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ToonClient
import com.comics8.core.source.DownloadLayout
import com.comics8.core.source.SourceLocator
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.core.source.local.CoverThumbCache
import com.comics8.core.source.local.LocalSource
import com.comics8.core.source.local.ThumbEncoder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DesktopDownloadManagerTest {
    @Test
    fun migrateMovesLegacyDirAndUpdatesLocalDirPath() = runBlocking {
        val root = createTempDirectory("desk-dl").toFile()
        root.deleteOnExit()
        val downloads = File(root, "downloads").apply { mkdirs() }
        val db = DesktopDatabase(File(root, "comics8.db")).apply {
            isSourceEnabled = { true }
            installedIds = { setOf(WorkId.DEFAULT_SOURCE) }
        }
        val legacy = DownloadLayout.legacyEpisodeDir(downloads, "123", "999")
        legacy.mkdirs()
        File(legacy, "0001.jpg").writeText("img")
        db.saveDownloadedEpisode(
            DownloadedEpisodeRecord(
                sourceId = WorkId.DEFAULT_SOURCE,
                toonId = "123",
                wrId = "999",
                toonTitle = "원피스",
                toonThumbUrl = "t",
                toonHref = "h",
                episodeTitle = "1화",
                episodeHref = "e",
                imageCount = 1,
                totalBytes = 3,
                downloadedAt = 1L,
                localDirPath = legacy.absolutePath,
            ),
        )

        val manager = DesktopDownloadManager(
            db,
            ToonClient(isProxyEnabled = false, sources = emptyLocator()),
            baseDir = downloads,
            sources = SourceRegistry.forTests(),
        )
        manager.migrateLegacyDownloads()

        val neu = DownloadLayout.episodeDir(downloads, WorkId.eleven("123"), "999")
        assertThat(File(neu, "0001.jpg").readText()).isEqualTo("img")
        assertThat(legacy.exists()).isFalse()
        val stored = db.getDownloadedEpisode(WorkId.eleven("123"), "999")
        assertThat(stored?.localDirPath).isEqualTo(neu.absolutePath)
        val images = manager.getLocalEpisodeImages(WorkId.eleven("123"), "999")
        assertThat(images).isNotNull()
        assertThat(images!!.single()).endsWith("/0001.jpg")
    }

    @Test
    fun incompleteDestDoesNotRewriteLocalDirPath() = runBlocking {
        val root = createTempDirectory("desk-partial").toFile()
        root.deleteOnExit()
        val downloads = File(root, "downloads").apply { mkdirs() }
        val db = DesktopDatabase(File(root, "comics8.db")).apply {
            isSourceEnabled = { true }
            installedIds = { setOf(WorkId.DEFAULT_SOURCE) }
        }
        val workId = WorkId.eleven("123")
        val dest = DownloadLayout.episodeDir(downloads, workId, "999")
        dest.mkdirs()
        File(dest, "0001.jpg").writeText("partial")
        val legacy = DownloadLayout.legacyEpisodeDir(downloads, "123", "999")
        legacy.mkdirs()
        File(legacy, "0001.jpg").writeText("1")
        File(legacy, "0002.jpg").writeText("2")
        db.saveDownloadedEpisode(
            DownloadedEpisodeRecord(
                sourceId = WorkId.DEFAULT_SOURCE,
                toonId = "123",
                wrId = "999",
                toonTitle = "원피스",
                toonThumbUrl = "t",
                toonHref = "h",
                episodeTitle = "1화",
                episodeHref = "e",
                imageCount = 2,
                totalBytes = 2,
                downloadedAt = 1L,
                localDirPath = legacy.absolutePath,
            ),
        )

        val manager = DesktopDownloadManager(
            db,
            ToonClient(isProxyEnabled = false, sources = emptyLocator()),
            baseDir = downloads,
            sources = SourceRegistry.forTests(),
        )
        manager.migrateLegacyDownloads()

        val stored = db.getDownloadedEpisode(workId, "999")
        assertThat(stored?.localDirPath).isEqualTo(legacy.absolutePath)
        assertThat(File(legacy, "0002.jpg").exists()).isTrue()
        val images = manager.getLocalEpisodeImages(workId, "999")
        assertThat(images).hasSize(2)
    }

    @Test
    fun enqueueLocalDoesNotCreateDownloadFiles() {
        val root = createTempDirectory("desk-local-dl").toFile()
        root.deleteOnExit()
        val downloads = File(root, "downloads").apply { mkdirs() }
        val series = ToonItem(
            id = "zip:/tmp/a.zip",
            title = "Local",
            thumbUrl = "",
            href = "",
            sourceId = WorkId.LOCAL_SOURCE,
        )
        val episode = EpisodeItem(
            wrId = "/tmp/a.zip",
            title = "a",
            date = null,
            thumbUrl = null,
            href = "",
        )
        val unloaded = SourceRegistry()
        enqueueLocal(downloads, File(root, "unloaded.db"), unloaded)
        assertThat(File(downloads, WorkId.LOCAL_SOURCE).exists()).isFalse()
        assertThat(
            DownloadLayout.episodeDir(downloads, WorkId.local(series.id), episode.wrId).exists(),
        ).isFalse()

        val thumbs = CoverThumbCache(File(root, "thumbs"), ThumbEncoder { bytes, _, _ -> bytes })
        val loaded = SourceRegistry(
            listOf(LocalSource(roots = { emptyList() }, thumbs = thumbs)),
        )
        enqueueLocal(downloads, File(root, "loaded.db"), loaded)
        assertThat(File(downloads, WorkId.LOCAL_SOURCE).exists()).isFalse()
        assertThat(
            DownloadLayout.episodeDir(downloads, WorkId.local(series.id), episode.wrId).exists(),
        ).isFalse()
    }

    private fun enqueueLocal(downloads: File, dbFile: File, registry: SourceRegistry) {
        val db = DesktopDatabase(dbFile).apply {
            isSourceEnabled = { true }
            installedIds = { setOf(WorkId.LOCAL_SOURCE) }
        }
        val manager = DesktopDownloadManager(
            db,
            ToonClient(isProxyEnabled = false, sources = SourceLocator { registry }),
            isSourceEnabled = { true },
            baseDir = downloads,
            sources = registry,
            installedIds = { setOf(WorkId.LOCAL_SOURCE) },
        )
        manager.enqueueEpisodes(
            ToonItem(
                id = "zip:/tmp/a.zip",
                title = "Local",
                thumbUrl = "",
                href = "",
                sourceId = WorkId.LOCAL_SOURCE,
            ),
            listOf(
                EpisodeItem(
                    wrId = "/tmp/a.zip",
                    title = "a",
                    date = null,
                    thumbUrl = null,
                    href = "",
                ),
            ),
        )
    }

    private fun emptyLocator(): SourceLocator = SourceLocator { SourceRegistry() }
}
