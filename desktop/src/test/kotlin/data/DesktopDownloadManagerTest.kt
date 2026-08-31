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
    fun getLocalEpisodeImagesResolvesPopulatedDir() = runBlocking {
        val root = createTempDirectory("desk-dl").toFile()
        root.deleteOnExit()
        val downloads = File(root, "downloads").apply { mkdirs() }
        val db = DesktopDatabase(File(root, "comics8.db")).apply {
            isSourceEnabled = { true }
            installedIds = { setOf(WorkId.DEFAULT_SOURCE) }
        }
        val workId = WorkId.eleven("123")
        val dir = DownloadLayout.episodeDir(downloads, workId, "999").apply { mkdirs() }
        File(dir, "0001.jpg").writeText("img")
        db.saveDownloadedEpisode(
            DownloadedEpisodeRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                wrId = "999",
                toonTitle = "원피스",
                toonThumbUrl = "t",
                toonHref = "h",
                episodeTitle = "1화",
                episodeHref = "e",
                imageCount = 1,
                totalBytes = 3,
                downloadedAt = 1L,
                localDirPath = dir.absolutePath,
            ),
        )

        val manager = DesktopDownloadManager(
            db,
            ToonClient(isProxyEnabled = false, sources = emptyLocator()),
            baseDir = downloads,
            sources = SourceRegistry.forTests(),
        )

        val images = manager.getLocalEpisodeImages(workId, "999")
        assertThat(images).isNotNull()
        assertThat(images!!.single()).endsWith("/0001.jpg")
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

        val loaded = SourceRegistry(
            listOf(LocalSource(roots = { emptyList() })),
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
