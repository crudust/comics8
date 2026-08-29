package com.comics8.monitor.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.comics8.core.download.DownloadProgressState as CoreDownloadProgressState
import com.comics8.core.download.DownloadQueueEngine
import com.comics8.core.download.DownloadTask as CoreDownloadTask
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
import com.comics8.monitor.R
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.data.local.DownloadedEpisode
import com.comics8.monitor.data.local.byToon
import com.comics8.monitor.data.local.delete
import com.comics8.monitor.data.local.deleteByToon
import com.comics8.monitor.data.local.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
typealias DownloadTask = CoreDownloadTask
typealias DownloadProgressState = CoreDownloadProgressState

class DownloadManager(
    private val context: Context,
    private val database: AppDatabase,
    private val client: ToonClient,
    private val isSourceEnabled: (String) -> Boolean = { false },
    baseDir: File = File(context.filesDir, "downloads"),
    private val sources: SourceRegistry,
    private val installedIds: () -> Set<String> = { sources.knownIds() },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val baseDir = baseDir.apply { mkdirs() }

    private val queue = DownloadQueueEngine(scope, ::processTask, ::dismissNotification)
    val progress: StateFlow<DownloadProgressState> = queue.progress

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (manager != null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "다운로드 진행 상태",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "오프라인 만화 다운로드 진행 상태를 표시합니다."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun enqueueEpisodes(series: ToonItem, episodes: List<EpisodeItem>) {
        if (episodes.isEmpty()) return
        if (sources.getOrNull(series.sourceId)?.writesDownloads != true) return
        val workId = SourceAccess.writable(series.sourceId, series.id, isSourceEnabled, installedIds()) ?: return
        scope.launch {
            val existing = database.downloadedEpisodeDao().byToon(workId).map { it.wrId }.toSet()
            val newEpisodes = episodes.filter { it.wrId !in existing }
            if (newEpisodes.isEmpty()) return@launch

            queue.enqueue(newEpisodes.map { ep ->
                DownloadTask(
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

    private suspend fun processTask(task: DownloadTask, report: (Int, Int) -> Unit) {
        val epDir = DownloadLayout.episodeDir(baseDir, task.workId(), task.episode.wrId).apply { mkdirs() }
        try {
            updateNotification(
                title = task.toonTitle,
                text = "${task.episode.title} 이미지 목록 조회 중...",
                progress = 0,
                max = 0,
                indeterminate = true,
            )
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
                    updateNotification(
                        title = "${task.toonTitle} - ${task.episode.title}",
                        text = "$completed / $total 다운로드 중 (남은 회차: ${progress.value.queueSize})",
                        progress = completed,
                        max = total,
                        indeterminate = false,
                    )
                },
            )

            val entity = DownloadedEpisode(
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
            database.downloadedEpisodeDao().upsert(entity)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // In case of failure, keep partial or retry
        }
    }

    private fun updateNotification(
        title: String,
        text: String,
        progress: Int,
        max: Int,
        indeterminate: Boolean,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setProgress(max, progress, indeterminate)
            .setOnlyAlertOnce(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {}
    }

    private fun dismissNotification() {
        val manager = NotificationManagerCompat.from(context)
        try {
            manager.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    fun cancelAll() {
        queue.cancelAll()
    }

    override fun close() {
        queue.close()
        scope.cancel()
    }

    suspend fun isEpisodeDownloaded(workId: WorkId, wrId: String): Boolean = withContext(Dispatchers.IO) {
        database.downloadedEpisodeDao().get(workId, wrId) != null
    }

    suspend fun getDownloadedEpisode(workId: WorkId, wrId: String): DownloadedEpisode? = withContext(Dispatchers.IO) {
        database.downloadedEpisodeDao().get(workId, wrId)
    }

    suspend fun getLocalEpisodeImages(workId: WorkId, wrId: String): List<String>? = withContext(Dispatchers.IO) {
        val entity = database.downloadedEpisodeDao().get(workId, wrId)
        val dir = DownloadLayout.resolveEpisodeDir(baseDir, workId, wrId, entity?.localDirPath)
            ?: return@withContext null
        val files = dir.listFiles { f -> f.isFile && f.length() > 0 && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
        if (files.isNullOrEmpty()) return@withContext null
        files.map { LocalImageUri.fromFile(it) }
    }

    suspend fun deleteToonDownloads(workId: WorkId) = withContext(Dispatchers.IO) {
        database.downloadedEpisodeDao().deleteByToon(workId)
        val toonDir = DownloadLayout.toonDir(baseDir, workId)
        if (toonDir.exists()) {
            toonDir.deleteRecursively()
        }
    }

    suspend fun deleteEpisodeDownload(workId: WorkId, wrId: String) = withContext(Dispatchers.IO) {
        database.downloadedEpisodeDao().delete(workId, wrId)
        val epDir = DownloadLayout.episodeDir(baseDir, workId, wrId)
        if (epDir.exists()) {
            epDir.deleteRecursively()
        }
    }

    suspend fun getDownloadedToonSummaries(sourceId: String): List<DownloadedToonSummary> = withContext(Dispatchers.IO) {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        val all = database.downloadedEpisodeDao().bySource(sid)
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

    suspend fun getDownloadedEpisodes(workId: WorkId): List<DownloadedEpisode> = withContext(Dispatchers.IO) {
        database.downloadedEpisodeDao().byToon(workId)
    }

    companion object {
        private const val CHANNEL_ID = "comic_downloads"
        private const val NOTIFICATION_ID = 99
    }
}
