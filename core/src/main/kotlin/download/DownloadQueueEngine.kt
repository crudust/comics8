package com.comics8.core.download

import com.comics8.core.model.EpisodeItem
import com.comics8.core.source.WorkId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

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

/** Serial, lifecycle-bound queue shared by Android and Desktop download managers. */
class DownloadQueueEngine(
    scope: CoroutineScope,
    private val processTask: suspend (DownloadTask, (Int, Int) -> Unit) -> Unit,
    private val onIdle: () -> Unit = {},
) {
    private val channel = Channel<DownloadTask>(Channel.UNLIMITED)
    private val waiting = AtomicInteger(0)
    private val _progress = MutableStateFlow(DownloadProgressState())
    val progress: StateFlow<DownloadProgressState> = _progress.asStateFlow()

    @Volatile
    private var currentJob: Job? = null

    private val worker = scope.launch {
        for (task in channel) {
            waiting.decrementAndGet()
            _progress.update {
                it.copy(
                    isRunning = true,
                    currentTask = task,
                    currentImage = 0,
                    totalImages = 0,
                    queueSize = waiting.get(),
                    activeToonTitle = task.toonTitle,
                )
            }
            currentJob = launch {
                processTask(task) { completed, total ->
                    _progress.update {
                        it.copy(currentImage = completed, totalImages = total, queueSize = waiting.get())
                    }
                }
            }
            try {
                currentJob?.join()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                currentJob = null
            }
            if (waiting.get() == 0) {
                _progress.value = DownloadProgressState()
                onIdle()
            }
        }
    }

    fun enqueue(tasks: Collection<DownloadTask>) {
        if (tasks.isEmpty()) return
        waiting.addAndGet(tasks.size)
        tasks.forEach { task ->
            check(channel.trySend(task).isSuccess) { "Download queue is closed" }
        }
        _progress.update { it.copy(queueSize = waiting.get()) }
    }

    fun cancelAll() {
        var removed = 0
        while (channel.tryReceive().isSuccess) removed++
        waiting.addAndGet(-removed)
        currentJob?.cancel()
        waiting.set(0)
        _progress.value = DownloadProgressState()
        onIdle()
    }

    fun close() {
        cancelAll()
        channel.close()
        worker.cancel()
    }
}
