package com.comics8.monitor.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.comics8.core.model.DownloadedToonSummary
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

data class DownloadTask(
    val sourceId: String,
    val toonId: String,
    val toonTitle: String,
    val toonThumbUrl: String,
    val toonHref: String,
    val episode: EpisodeItem,
) {
    fun workId(): WorkId = WorkId(sourceId, toonId)
}

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
    private val isSourceEnabled: (String) -> Boolean = { false },
    baseDir: File = File(context.filesDir, "downloads"),
    private val sources: SourceRegistry = SourceRegistry.defaults(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val baseDir = baseDir.apply { mkdirs() }
    private val taskQueue = ConcurrentLinkedQueue<DownloadTask>()
    private var workerJob: Job? = null
    private val migrateMutex = Mutex()
    private var migrated = false

    private val _progress = MutableStateFlow(DownloadProgressState())
    val progress: StateFlow<DownloadProgressState> = _progress.asStateFlow()

    init {
        createNotificationChannel()
        scope.launch { migrateLegacyDownloads() }
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
        val workId = SourceAccess.writable(series.sourceId, series.id, isSourceEnabled) ?: return
        scope.launch {
            migrateLegacyDownloads()
            val existing = database.downloadedEpisodeDao().byToon(workId).map { it.wrId }.toSet()
            val newEpisodes = episodes.filter { it.wrId !in existing }
            if (newEpisodes.isEmpty()) return@launch

            for (ep in newEpisodes) {
                taskQueue.add(
                    DownloadTask(
                        sourceId = workId.sourceId,
                        toonId = workId.toonId,
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
        migrateLegacyDownloads()
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
                    val bytes = ImageFallbacks.fetchBytes(url) { client.fetchBytes(it) }
                    imgFile.writeBytes(bytes)
                    downloadedBytes += bytes.size
                } else {
                    downloadedBytes += imgFile.length()
                }
                downloadedFiles.add(imgFile)
                delay(150) // Rate limit protection
            }

            val entity = DownloadedEpisode(
                sourceId = task.sourceId,
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

    suspend fun isEpisodeDownloaded(workId: WorkId, wrId: String): Boolean = withContext(Dispatchers.IO) {
        migrateLegacyDownloads()
        database.downloadedEpisodeDao().get(workId, wrId) != null
    }

    suspend fun getDownloadedEpisode(workId: WorkId, wrId: String): DownloadedEpisode? = withContext(Dispatchers.IO) {
        migrateLegacyDownloads()
        database.downloadedEpisodeDao().get(workId, wrId)
    }

    suspend fun getLocalEpisodeImages(workId: WorkId, wrId: String): List<String>? = withContext(Dispatchers.IO) {
        migrateLegacyDownloads()
        val entity = database.downloadedEpisodeDao().get(workId, wrId)
        val dir = DownloadLayout.resolveEpisodeDir(baseDir, workId, wrId, entity?.localDirPath)
            ?: return@withContext null
        val files = dir.listFiles { f -> f.isFile && f.length() > 0 && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
        if (files.isNullOrEmpty()) return@withContext null
        files.map { LocalImageUri.fromFile(it) }
    }

    suspend fun deleteToonDownloads(workId: WorkId) = withContext(Dispatchers.IO) {
        migrateLegacyDownloads()
        database.downloadedEpisodeDao().deleteByToon(workId)
        val toonDir = DownloadLayout.toonDir(baseDir, workId)
        if (toonDir.exists()) {
            toonDir.deleteRecursively()
        }
        val legacy = DownloadLayout.legacyToonDir(baseDir, workId.toonId)
        if (legacy.exists()) {
            legacy.deleteRecursively()
        }
    }

    suspend fun deleteEpisodeDownload(workId: WorkId, wrId: String) = withContext(Dispatchers.IO) {
        migrateLegacyDownloads()
        database.downloadedEpisodeDao().delete(workId, wrId)
        val epDir = DownloadLayout.episodeDir(baseDir, workId, wrId)
        if (epDir.exists()) {
            epDir.deleteRecursively()
        }
        val legacy = DownloadLayout.legacyEpisodeDir(baseDir, workId.toonId, wrId)
        if (legacy.exists()) {
            legacy.deleteRecursively()
        }
    }

    suspend fun getDownloadedToonSummaries(sourceId: String): List<DownloadedToonSummary> = withContext(Dispatchers.IO) {
        migrateLegacyDownloads()
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
        migrateLegacyDownloads()
        database.downloadedEpisodeDao().byToon(workId)
    }

    internal suspend fun migrateLegacyDownloads() = migrateMutex.withLock {
        if (migrated) return
        DownloadLayout.migrateLegacyElevenDirs(baseDir)
        val dao = database.downloadedEpisodeDao()
        for (entity in dao.all()) {
            val workId = entity.workId()
            if (workId.sourceId == WorkId.DEFAULT_SOURCE) {
                DownloadLayout.migrateLegacyEpisode(baseDir, entity.toonId, entity.wrId)
            }
            val resolved = DownloadLayout.resolveEpisodeDir(
                baseDir,
                workId,
                entity.wrId,
                entity.localDirPath,
            ) ?: continue
            if (entity.localDirPath != resolved.absolutePath) {
                dao.upsert(entity.copy(localDirPath = resolved.absolutePath))
            }
        }
        migrated = true
    }

    companion object {
        private const val CHANNEL_ID = "comic_downloads"
        private const val NOTIFICATION_ID = 99
    }
}
