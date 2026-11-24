package com.comics8.core.data

import com.comics8.core.download.DownloadProgressState
import com.comics8.core.download.DownloadQueueEngine
import com.comics8.core.download.DownloadTask
import com.comics8.core.model.DownloadedToonSummary
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
import com.comics8.core.storage.ComicLocalStore
import com.comics8.core.storage.DownloadedEpisodeRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

interface DownloadNotificationSink {
    fun onResolving(task: DownloadTask) {}
    fun onProgress(task: DownloadTask, completed: Int, total: Int, queueSize: Int) {}
    fun onIdle() {}

    companion object {
        val NO_OP = object : DownloadNotificationSink {}
    }
}

open class SharedDownloadManager(
    private val store: ComicLocalStore,
    private val client: ToonClient,
    private val isSourceEnabled: (String) -> Boolean = { false },
    protected val baseDir: File,
    private val sources: SourceRegistry,
    private val installedIds: () -> Set<String> = { sources.knownIds() },
    private val notifications: DownloadNotificationSink = DownloadNotificationSink.NO_OP,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rootDir = baseDir.apply { mkdirs() }

    private val queue = DownloadQueueEngine(scope, ::processTask) { notifications.onIdle() }
    val progress: StateFlow<DownloadProgressState> = queue.progress

    fun enqueueEpisodes(series: ToonItem, episodes: List<EpisodeItem>) {
        if (episodes.isEmpty()) return
        if (sources.getOrNull(series.sourceId)?.writesDownloads != true) return
        val workId = SourceAccess.writable(series.sourceId, series.id, isSourceEnabled, installedIds()) ?: return
        scope.launch {
            val existing = store.getDownloadedEpisodesByToon(workId).map { it.wrId }.toSet()
            val newEpisodes = episodes.filter { it.wrId !in existing }
            if (newEpisodes.isEmpty()) return@launch

            queue.enqueue(
                newEpisodes.map { ep ->
                    DownloadTask(
                        sourceId = workId.sourceId,
                        toonId = workId.toonId,
                        toonTitle = series.title,
                        toonThumbUrl = series.thumbUrl,
                        toonHref = series.href,
                        episode = ep,
                    )
                },
            )
        }
    }

    private suspend fun processTask(task: DownloadTask, report: (Int, Int) -> Unit) {
        val epDir = DownloadLayout.episodeDir(rootDir, task.workId(), task.episode.wrId).apply { mkdirs() }
        try {
            notifications.onResolving(task)
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
                    notifications.onProgress(task, completed, total, progress.value.queueSize)
                },
            )

            val record = DownloadedEpisodeRecord(
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
            store.upsertDownloadedEpisode(record)
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
        store.getDownloadedEpisode(workId, wrId) != null
    }

    suspend fun getDownloadedEpisodeRecord(workId: WorkId, wrId: String): DownloadedEpisodeRecord? =
        withContext(Dispatchers.IO) {
            store.getDownloadedEpisode(workId, wrId)
        }

    suspend fun getLocalEpisodeImages(workId: WorkId, wrId: String): List<String>? = withContext(Dispatchers.IO) {
        val entity = store.getDownloadedEpisode(workId, wrId)
        val dir = DownloadLayout.resolveEpisodeDir(rootDir, workId, wrId, entity?.localDirPath)
            ?: return@withContext null
        val files = dir.listFiles { f -> f.isFile && f.length() > 0 && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
        if (files.isNullOrEmpty()) return@withContext null
        files.map { LocalImageUri.fromFile(it) }
    }

    suspend fun deleteToonDownloads(workId: WorkId) = withContext(Dispatchers.IO) {
        store.deleteDownloadedEpisodesByToon(workId)
        val toonDir = DownloadLayout.toonDir(rootDir, workId)
        if (toonDir.exists()) {
            toonDir.deleteRecursively()
        }
    }

    suspend fun deleteEpisodeDownload(workId: WorkId, wrId: String) = withContext(Dispatchers.IO) {
        store.deleteDownloadedEpisode(workId, wrId)
        val epDir = DownloadLayout.episodeDir(rootDir, workId, wrId)
        if (epDir.exists()) {
            epDir.deleteRecursively()
        }
    }

    suspend fun getDownloadedToonSummaries(sourceId: String): List<DownloadedToonSummary> = withContext(Dispatchers.IO) {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        val all = store.getDownloadedEpisodesBySource(sid)
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

    suspend fun getDownloadedEpisodeRecords(workId: WorkId): List<DownloadedEpisodeRecord> =
        withContext(Dispatchers.IO) {
            store.getDownloadedEpisodesByToon(workId)
        }
}
