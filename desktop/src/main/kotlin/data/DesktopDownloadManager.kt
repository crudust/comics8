package com.comics8.desktop.data

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

data class DesktopDownloadTask(
    val sourceId: String,
    val toonId: String,
    val toonTitle: String,
    val toonThumbUrl: String,
    val toonHref: String,
    val episode: EpisodeItem,
) {
    fun workId(): WorkId = WorkId(sourceId, toonId)
}

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
    private val isSourceEnabled: (String) -> Boolean = { false },
    baseDir: File = File(System.getProperty("user.home"), ".comics8/downloads"),
    private val sources: SourceRegistry,
    private val installedIds: () -> Set<String> = { sources.knownIds() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val baseDir = baseDir.apply { mkdirs() }
    private val taskQueue = ConcurrentLinkedQueue<DesktopDownloadTask>()
    private var workerJob: Job? = null
    private val migrateMutex = Mutex()
    private var migrated = false

    private val _progress = MutableStateFlow(DesktopDownloadProgressState())
    val progress: StateFlow<DesktopDownloadProgressState> = _progress.asStateFlow()

    init {
        scope.launch { migrateLegacyDownloads() }
    }

    fun enqueueEpisodes(series: ToonItem, episodes: List<EpisodeItem>) {
        if (episodes.isEmpty()) return
        if (sources.getOrNull(series.sourceId)?.writesDownloads != true) return
        val workId = SourceAccess.writable(series.sourceId, series.id, isSourceEnabled, installedIds()) ?: return
        scope.launch {
            migrateLegacyDownloads()
            val existing = database.getDownloadedEpisodesByToon(workId).map { it.wrId }.toSet()
            val newEpisodes = episodes.filter { it.wrId !in existing }
            if (newEpisodes.isEmpty()) return@launch

            for (ep in newEpisodes) {
                taskQueue.add(
                    DesktopDownloadTask(
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
        }
    }

    private suspend fun processTask(task: DesktopDownloadTask) {
        migrateLegacyDownloads()
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

            _progress.update { it.copy(totalImages = imageUrls.size) }

            val batch = ImageBatchDownload.toNumberedFiles(
                urls = imageUrls,
                destDir = epDir,
                fetchBytes = { url -> ImageFallbacks.fetchBytes(url, sources) { client.fetchBytes(it) } },
                onProgress = { completed, total ->
                    _progress.update { it.copy(currentImage = completed, totalImages = total) }
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

    suspend fun isEpisodeDownloaded(workId: WorkId, wrId: String): Boolean = withContext(Dispatchers.IO) {
        migrateLegacyDownloads()
        database.getDownloadedEpisode(workId, wrId) != null
    }

    suspend fun getDownloadedEpisode(workId: WorkId, wrId: String): DownloadedEpisodeRecord? = withContext(Dispatchers.IO) {
        migrateLegacyDownloads()
        database.getDownloadedEpisode(workId, wrId)
    }

    suspend fun getLocalEpisodeImages(workId: WorkId, wrId: String): List<String>? = withContext(Dispatchers.IO) {
        migrateLegacyDownloads()
        val entity = database.getDownloadedEpisode(workId, wrId)
        val dir = DownloadLayout.resolveEpisodeDir(baseDir, workId, wrId, entity?.localDirPath)
            ?: return@withContext null
        val files = dir.listFiles { f -> f.isFile && f.length() > 0 && f.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
        if (files.isNullOrEmpty()) return@withContext null
        files.map { LocalImageUri.fromFile(it) }
    }

    suspend fun deleteToonDownloads(workId: WorkId) = withContext(Dispatchers.IO) {
        migrateLegacyDownloads()
        database.deleteDownloadedEpisodesByToon(workId)
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
        database.deleteDownloadedEpisode(workId, wrId)
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
        migrateLegacyDownloads()
        database.getDownloadedEpisodesByToon(workId)
    }

    internal suspend fun migrateLegacyDownloads() = migrateMutex.withLock {
        if (migrated) return
        DownloadLayout.migrateLegacyElevenDirs(baseDir)
        for (entity in database.getAllDownloadedEpisodes()) {
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
                database.saveDownloadedEpisode(entity.copy(localDirPath = resolved.absolutePath))
            }
        }
        migrated = true
    }
}
