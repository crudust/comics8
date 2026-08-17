package com.comics8.monitor.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.comics8.core.model.DownloadedToonSummary
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ToonClient
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.data.local.DownloadedEpisode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

data class DownloadTask(
    val toonId: String,
    val toonTitle: String,
    val toonThumbUrl: String,
    val toonHref: String,
    val episode: EpisodeItem,
)

data class DownloadProgressState(
    val isRunning: Boolean = false,
    val currentTask: DownloadTask? = null,
    val currentImage: Int = 0,
    val totalImages: Int = 0,
    val queueSize: Int = 0,
    val activeToonTitle: String = "",
)

class DownloadManager(
    private val context: Context,
    private val database: AppDatabase,
    private val client: ToonClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val baseDir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val taskQueue = ConcurrentLinkedQueue<DownloadTask>()
    private var workerJob: Job? = null

    private val _progress = MutableStateFlow(DownloadProgressState())
    val progress: StateFlow<DownloadProgressState> = _progress.asStateFlow()

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
        scope.launch {
            val existing = database.downloadedEpisodeDao().byToon(series.id).map { it.wrId }.toSet()
            val newEpisodes = episodes.filter { it.wrId !in existing }
            if (newEpisodes.isEmpty()) return@launch

            for (ep in newEpisodes) {
                taskQueue.add(
                    DownloadTask(
                        toonId = series.id,
                        toonTitle = series.title,
                        toonThumbUrl = series.thumbUrl,
                        toonHref = series.href,
                        episode = ep,
                    )
                )
            }
            _progress.update { it.copy(queueSize = taskQueue.size) }
            startWorkerIfNeeded()
        }
    }

    @Synchronized
    private fun startWorkerIfNeeded() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            _progress.update { it.copy(isRunning = true) }
            while (taskQueue.isNotEmpty()) {
                val task = taskQueue.poll() ?: break
                _progress.update {
                    it.copy(
                        currentTask = task,
                        queueSize = taskQueue.size,
                        activeToonTitle = task.toonTitle,
                        currentImage = 0,
                        totalImages = 0,
                    )
                }
                processTask(task)
            }
            _progress.update {
                it.copy(
                    isRunning = false,
                    currentTask = null,
                    queueSize = 0,
                    currentImage = 0,
                    totalImages = 0,
                )
            }
            dismissNotification()
        }
    }

    private suspend fun processTask(task: DownloadTask) {
        val epDir = File(baseDir, "${task.toonId}/${task.episode.wrId}").apply { mkdirs() }
        try {
            updateNotification(
                title = task.toonTitle,
                text = "${task.episode.title} 이미지 목록 조회 중...",
                progress = 0,
                max = 0,
                indeterminate = true,
            )
            val imageUrls = client.fetchImages(task.episode.href)
            if (imageUrls.isEmpty()) return

            _progress.update { it.copy(totalImages = imageUrls.size) }

            var downloadedBytes = 0L
            val downloadedFiles = mutableListOf<File>()

            for ((index, url) in imageUrls.withIndex()) {
                val imgIndex = index + 1
                _progress.update { it.copy(currentImage = imgIndex) }
                updateNotification(
                    title = "${task.toonTitle} - ${task.episode.title}",
                    text = "$imgIndex / ${imageUrls.size} 다운로드 중 (남은 회차: ${taskQueue.size})",
                    progress = imgIndex,
                    max = imageUrls.size,
                    indeterminate = false,
                )
                val imgFile = File(epDir, String.format("%04d.jpg", imgIndex))
                if (!imgFile.exists() || imgFile.length() == 0L) {
                    val bytes = client.fetchBytes(url)
                    imgFile.writeBytes(bytes)
                    downloadedBytes += bytes.size
                } else {
                    downloadedBytes += imgFile.length()
                }
                downloadedFiles.add(imgFile)
                delay(150) // Rate limit protection
            }

            val entity = DownloadedEpisode(
                toonId = task.toonId,
                wrId = task.episode.wrId,
                toonTitle = task.toonTitle,
                toonThumbUrl = task.toonThumbUrl,
                toonHref = task.toonHref,
                episodeTitle = task.episode.title,
                episodeHref = task.episode.href,
                imageCount = downloadedFiles.size,
                totalBytes = downloadedBytes,
                downloadedAt = System.currentTimeMillis(),
                localDirPath = epDir.absolutePath,
            )
            database.downloadedEpisodeDao().upsert(entity)
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
            .setSmallIcon(android.R.drawable.stat_sys_download)
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
        taskQueue.clear()
        workerJob?.cancel()
        _progress.update {
            it.copy(
                isRunning = false,
                currentTask = null,
                queueSize = 0,
                currentImage = 0,
                totalImages = 0,
            )
        }
        dismissNotification()
    }

    suspend fun isEpisodeDownloaded(toonId: String, wrId: String): Boolean = withContext(Dispatchers.IO) {
        database.downloadedEpisodeDao().get(toonId, wrId) != null
    }

    suspend fun getLocalEpisodeImages(toonId: String, wrId: String): List<String>? = withContext(Dispatchers.IO) {
        val entity = database.downloadedEpisodeDao().get(toonId, wrId) ?: return@withContext null
        val dir = File(entity.localDirPath)
        if (!dir.exists() || !dir.isDirectory) return@withContext null
        val files = dir.listFiles { f -> f.isFile && f.length() > 0 && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
        if (files.isNullOrEmpty()) return@withContext null
        files.map { "file://${it.absolutePath}" }
    }

    suspend fun deleteToonDownloads(toonId: String) = withContext(Dispatchers.IO) {
        database.downloadedEpisodeDao().deleteByToon(toonId)
        val toonDir = File(baseDir, toonId)
        if (toonDir.exists()) {
            toonDir.deleteRecursively()
        }
    }

    suspend fun deleteEpisodeDownload(toonId: String, wrId: String) = withContext(Dispatchers.IO) {
        database.downloadedEpisodeDao().delete(toonId, wrId)
        val epDir = File(baseDir, "$toonId/$wrId")
        if (epDir.exists()) {
            epDir.deleteRecursively()
        }
    }

    suspend fun getDownloadedToonSummaries(): List<DownloadedToonSummary> = withContext(Dispatchers.IO) {
        val all = database.downloadedEpisodeDao().all()
        val grouped = all.groupBy { it.toonId }
        grouped.map { (toonId, epList) ->
            val first = epList.first()
            val totalBytes = epList.sumOf { it.totalBytes }
            val latestDownloadedAt = epList.maxOf { it.downloadedAt }
            DownloadedToonSummary(
                toonId = toonId,
                toonTitle = first.toonTitle,
                toonThumbUrl = first.toonThumbUrl,
                toonHref = first.toonHref,
                episodeCount = epList.size,
                totalBytes = totalBytes,
                latestDownloadedAt = latestDownloadedAt,
            )
        }.sortedByDescending { it.latestDownloadedAt }
    }

    suspend fun getDownloadedEpisodes(toonId: String): List<DownloadedEpisode> = withContext(Dispatchers.IO) {
        database.downloadedEpisodeDao().byToon(toonId)
    }

    companion object {
        private const val CHANNEL_ID = "comic_downloads"
        private const val NOTIFICATION_ID = 99
    }
}
