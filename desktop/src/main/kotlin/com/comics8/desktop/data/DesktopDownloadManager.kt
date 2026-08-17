package com.comics8.desktop.data

import com.comics8.core.model.DownloadedToonSummary
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ToonClient
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

data class DesktopDownloadTask(
    val toonId: String,
    val toonTitle: String,
    val toonThumbUrl: String,
    val toonHref: String,
    val episode: EpisodeItem,
)

data class DesktopDownloadProgressState(
    val isRunning: Boolean = false,
    val currentTask: DesktopDownloadTask? = null,
    val currentImage: Int = 0,
    val totalImages: Int = 0,
    val queueSize: Int = 0,
    val activeToonTitle: String = "",
)

class DesktopDownloadManager(
    private val database: DesktopDatabase,
    private val client: ToonClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val baseDir = File(System.getProperty("user.home"), ".comics8/downloads").apply { mkdirs() }
    private val taskQueue = ConcurrentLinkedQueue<DesktopDownloadTask>()
    private var workerJob: Job? = null

    private val _progress = MutableStateFlow(DesktopDownloadProgressState())
    val progress: StateFlow<DesktopDownloadProgressState> = _progress.asStateFlow()

    fun enqueueEpisodes(series: ToonItem, episodes: List<EpisodeItem>) {
        if (episodes.isEmpty()) return
        scope.launch {
            val existing = database.getDownloadedEpisodesByToon(series.id).map { it.wrId }.toSet()
            val newEpisodes = episodes.filter { it.wrId !in existing }
            if (newEpisodes.isEmpty()) return@launch

            for (ep in newEpisodes) {
                taskQueue.add(
                    DesktopDownloadTask(
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
        }
    }

    private suspend fun processTask(task: DesktopDownloadTask) {
        val epDir = File(baseDir, "${task.toonId}/${task.episode.wrId}").apply { mkdirs() }
        try {
            val imageUrls = client.fetchImages(task.episode.href)
            if (imageUrls.isEmpty()) return

            _progress.update { it.copy(totalImages = imageUrls.size) }

            var downloadedBytes = 0L
            val downloadedFiles = mutableListOf<File>()

            for ((index, url) in imageUrls.withIndex()) {
                val imgIndex = index + 1
                _progress.update { it.copy(currentImage = imgIndex) }
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

            val entity = DownloadedEpisodeRecord(
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
            database.saveDownloadedEpisode(entity)
        } catch (_: Exception) {
            // Partial retry or continue
        }
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
    }

    suspend fun isEpisodeDownloaded(toonId: String, wrId: String): Boolean = withContext(Dispatchers.IO) {
        database.getDownloadedEpisode(toonId, wrId) != null
    }

    suspend fun getLocalEpisodeImages(toonId: String, wrId: String): List<String>? = withContext(Dispatchers.IO) {
        val entity = database.getDownloadedEpisode(toonId, wrId) ?: return@withContext null
        val dir = File(entity.localDirPath)
        if (!dir.exists() || !dir.isDirectory) return@withContext null
        val files = dir.listFiles { f -> f.isFile && f.length() > 0 && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
        if (files.isNullOrEmpty()) return@withContext null
        files.map { "file://${it.absolutePath}" }
    }

    suspend fun deleteToonDownloads(toonId: String) = withContext(Dispatchers.IO) {
        database.deleteDownloadedEpisodesByToon(toonId)
        val toonDir = File(baseDir, toonId)
        if (toonDir.exists()) {
            toonDir.deleteRecursively()
        }
    }

    suspend fun deleteEpisodeDownload(toonId: String, wrId: String) = withContext(Dispatchers.IO) {
        database.deleteDownloadedEpisode(toonId, wrId)
        val epDir = File(baseDir, "$toonId/$wrId")
        if (epDir.exists()) {
            epDir.deleteRecursively()
        }
    }

    suspend fun getDownloadedToonSummaries(): List<DownloadedToonSummary> = withContext(Dispatchers.IO) {
        val all = database.getAllDownloadedEpisodes()
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

    suspend fun getDownloadedEpisodes(toonId: String): List<DownloadedEpisodeRecord> = withContext(Dispatchers.IO) {
        database.getDownloadedEpisodesByToon(toonId)
    }
}
