package com.comics8.desktop.data

import com.comics8.core.model.DownloadedToonSummary
import com.comics8.core.download.DownloadProgressState as CoreDownloadProgressState
import com.comics8.core.download.DownloadQueueEngine
import com.comics8.core.download.DownloadTask as CoreDownloadTask
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ImageBatchDownload
import com.comics8.core.network.ImageFallbacks
import com.comics8.core.network.ToonClient
import com.comics8.core.source.DownloadLayout
import com.comics8.core.source.LocalImageUri
import com.comics8.core.source.SourceAccess
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
typealias DesktopDownloadTask = CoreDownloadTask
typealias DesktopDownloadProgressState = CoreDownloadProgressState

class DesktopDownloadManager(
    private val database: DesktopDatabase,
    private val client: ToonClient,
    private val isSourceEnabled: (String) -> Boolean = { false },
    baseDir: File = File(System.getProperty("user.home"), ".comics8/downloads"),
    private val sources: SourceRegistry,
    private val installedIds: () -> Set<String> = { sources.knownIds() },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val baseDir = baseDir.apply { mkdirs() }

    private val queue = DownloadQueueEngine(scope, ::processTask)
    val progress: StateFlow<DesktopDownloadProgressState> = queue.progress

    fun enqueueEpisodes(series: ToonItem, episodes: List<EpisodeItem>) {
        if (episodes.isEmpty()) return
        if (sources.getOrNull(series.sourceId)?.writesDownloads != true) return
        val workId = SourceAccess.writable(series.sourceId, series.id, isSourceEnabled, installedIds()) ?: return
        scope.launch {
            val existing = database.getDownloadedEpisodesByToon(workId).map { it.wrId }.toSet()
            val newEpisodes = episodes.filter { it.wrId !in existing }
            if (newEpisodes.isEmpty()) return@launch

            queue.enqueue(newEpisodes.map { ep ->
                DesktopDownloadTask(
                    sourceId = workId.sourceId,
                    toonId = workId.toonId,
                    toonTitle = series.title,
                    toonThumbUrl = series.thumbUrl,
                    toonHref = series.href,
                    episode = ep,
                )
            })
        }
    }

    private suspend fun processTask(task: DesktopDownloadTask, report: (Int, Int) -> Unit) {
        val epDir = DownloadLayout.episodeDir(baseDir, task.workId(), task.episode.wrId).apply { mkdirs() }
        try {
            val series = ToonItem(
                id = task.toonId,
                title = task.toonTitle,
                thumbUrl = task.toonThumbUrl,
                href = task.toonHref,
                sourceId = task.sourceId,
            )
            val imageUrls = sources.get(task.sourceId).resolveImages(task.episode, series, client)
            if (imageUrls.isEmpty()) return

            report(0, imageUrls.size)

            val batch = ImageBatchDownload.toNumberedFiles(
                urls = imageUrls,
                destDir = epDir,
                fetchBytes = { url -> ImageFallbacks.fetchBytes(url, sources) { client.fetchBytes(it) } },
                onProgress = { completed, total ->
                    report(completed, total)
                },
            )

            val entity = DownloadedEpisodeRecord(
                sourceId = task.sourceId,
                toonId = task.toonId,
                wrId = task.episode.wrId,
                toonTitle = task.toonTitle,
                toonThumbUrl = task.toonThumbUrl,
                toonHref = task.toonHref,
                episodeTitle = task.episode.title,
                episodeHref = task.episode.href,
                imageCount = batch.files.size,
                totalBytes = batch.totalBytes,
                downloadedAt = System.currentTimeMillis(),
                localDirPath = epDir.absolutePath,
            )
            database.saveDownloadedEpisode(entity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Partial retry or continue
        }
    }

    fun cancelAll() {
        queue.cancelAll()
    }

    override fun close() {
        queue.close()
        scope.cancel()
    }

    suspend fun isEpisodeDownloaded(workId: WorkId, wrId: String): Boolean = withContext(Dispatchers.IO) {
        database.getDownloadedEpisode(workId, wrId) != null
    }

    suspend fun getDownloadedEpisode(workId: WorkId, wrId: String): DownloadedEpisodeRecord? = withContext(Dispatchers.IO) {
        database.getDownloadedEpisode(workId, wrId)
    }

    suspend fun getLocalEpisodeImages(workId: WorkId, wrId: String): List<String>? = withContext(Dispatchers.IO) {
        val entity = database.getDownloadedEpisode(workId, wrId)
        val dir = DownloadLayout.resolveEpisodeDir(baseDir, workId, wrId, entity?.localDirPath)
            ?: return@withContext null
        val files = dir.listFiles { f -> f.isFile && f.length() > 0 && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
        if (files.isNullOrEmpty()) return@withContext null
        files.map { LocalImageUri.fromFile(it) }
    }

    suspend fun deleteToonDownloads(workId: WorkId) = withContext(Dispatchers.IO) {
        database.deleteDownloadedEpisodesByToon(workId)
        val toonDir = DownloadLayout.toonDir(baseDir, workId)
        if (toonDir.exists()) {
            toonDir.deleteRecursively()
        }
    }

    suspend fun deleteEpisodeDownload(workId: WorkId, wrId: String) = withContext(Dispatchers.IO) {
        database.deleteDownloadedEpisode(workId, wrId)
        val epDir = DownloadLayout.episodeDir(baseDir, workId, wrId)
        if (epDir.exists()) {
            epDir.deleteRecursively()
        }
    }

    suspend fun getDownloadedToonSummaries(sourceId: String): List<DownloadedToonSummary> = withContext(Dispatchers.IO) {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        val all = database.getDownloadedEpisodesBySource(sid)
        val grouped = all.groupBy { it.workId().storageKey() }
        grouped.map { (_, epList) ->
            val first = epList.first()
            val totalBytes = epList.sumOf { it.totalBytes }
            val latestDownloadedAt = epList.maxOf { it.downloadedAt }
            DownloadedToonSummary(
                toonId = first.toonId,
                toonTitle = first.toonTitle,
                toonThumbUrl = first.toonThumbUrl,
                toonHref = first.toonHref,
                episodeCount = epList.size,
                totalBytes = totalBytes,
                latestDownloadedAt = latestDownloadedAt,
                sourceId = first.sourceId,
            )
        }.sortedByDescending { it.latestDownloadedAt }
    }

    suspend fun getDownloadedEpisodes(workId: WorkId): List<DownloadedEpisodeRecord> = withContext(Dispatchers.IO) {
        database.getDownloadedEpisodesByToon(workId)
    }
}
