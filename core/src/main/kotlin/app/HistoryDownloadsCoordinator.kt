package com.comics8.core.app

import com.comics8.core.image.ImageCacheRole
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ReaderDomain
import com.comics8.core.model.ReaderProgress
import com.comics8.core.model.sortedWithOrder
import com.comics8.core.source.WorkId
import com.comics8.core.storage.ReadHistoryRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class HistoryDownloadsCoordinator(
    private val controller: SharedAppController,
) {
    private val state get() = controller._state
    private val repository get() = controller.repository
    private val sourceSettings get() = controller.sourceSettings
    private val platform get() = controller.platform
    private val scope get() = controller.scope

    private var downloadCatalogJob: Job? = null

    fun close() {
        downloadCatalogJob?.cancel()
    }

    fun openHistory() {
        if (state.value.screen == AppScreen.Series) {
            platform.clearImageMemory(ImageCacheRole.EPISODE)
        }
        controller.readerSession.cancelReaderJob()
        controller.browseSearch.cancelEpisodeJob()
        state.update {
            it.copy(
                screen = AppScreen.History,
                historyLoading = true,
            )
        }
        loadHistory()
    }

    fun closeHistory() {
        state.update { it.copy(screen = AppScreen.Browse) }
    }

    fun loadHistory() {
        val sourceId = state.value.activeSourceId
        if (sourceId == null) {
            state.update { it.copy(historyLoading = false, historyItems = emptyList()) }
            return
        }
        scope.launch {
            state.update { it.copy(historyLoading = it.historyItems.isEmpty()) }
            val items = repository.getHistoryRecords(sourceId)
            val workIds = items.map { item -> item.workId() }
            val counts = controller.countIfNeeded(workIds, sourceId)
            val totals = repository.getCatalogTotals(workIds)
            state.update {
                it.copy(
                    historyItems = items,
                    historyLoading = false,
                    readCounts = it.readCounts + counts,
                    catalogTotals = it.catalogTotals + totals,
                    scrollToTopTrigger = it.scrollToTopTrigger + 1,
                )
            }
        }
    }

    fun deleteHistory(workId: WorkId) {
        scope.launch {
            repository.deleteHistory(workId)
            state.update { current ->
                val updatedItems = current.items.map {
                    if (it.workId() == workId) it.copy(readProgress = null) else it
                }
                current.copy(
                    historyItems = current.historyItems.filter { it.workId() != workId },
                    items = updatedItems,
                    seriesHistory = if (current.series?.workId() == workId) null else current.seriesHistory,
                    readCounts = current.readCounts - workId.storageKey(),
                )
            }
        }
    }

    fun clearAllHistory() {
        val sourceId = state.value.activeSourceId ?: return
        scope.launch {
            repository.clearHistory(sourceId)
            state.update { current ->
                val updatedItems = current.items.map { it.copy(readProgress = null) }
                current.copy(
                    historyItems = emptyList(),
                    items = updatedItems,
                    seriesHistory = null,
                    readCounts = emptyMap(),
                )
            }
        }
    }

    fun openSeriesFromHistory(item: ReadHistoryRecord) {
        controller.browseSearch.openSeries(controller.toToonItem(item))
    }

    fun continueHistoryEpisode(item: ReadHistoryRecord) {
        val toon = controller.toToonItem(item)
        if (state.value.screen == AppScreen.History) {
            controller.browseSearch.previousScreenBeforeSeries = AppScreen.History
        }
        if (platform.persistGlobalReaderDefaults) {
            platform.clearImageMemory(ImageCacheRole.EPISODE)
        }
        controller.browseSearch.cancelEpisodeJob()
        controller.readerSession.cancelReaderJob()
        val sortOrder = sourceSettings.episodeSortOrder(toon.sourceId)
        state.update {
            it.copy(
                screen = AppScreen.Series,
                series = toon,
                seriesFavorited = false,
                rawEpisodes = emptyList(),
                episodes = emptyList(),
                episodeSortOrder = sortOrder,
                episodeLoading = true,
                highlightedEpisodeId = item.lastWrId,
                readerLoading = true,
                readerError = null,
                readerImages = emptyList(),
                viewMode = if (platform.persistGlobalReaderDefaults) controller.resolveTargetViewMode() else it.viewMode,
                readDirection = if (platform.persistGlobalReaderDefaults) controller.globalReadDirection else it.readDirection,
            )
        }
        controller.browseSearch.refreshSeriesFavorited(toon)
        scope.launch {
            try {
                val readEp = repository.getReadEpisodeRecord(item.workId(), item.lastWrId)
                val isCompleted = readEp == null || ReaderProgress.isCompleted(readEp.lastPage)
                val targetOrder = if (isCompleted) item.lastReadOrder + 1 else item.lastReadOrder
                val predictedPage = ReaderDomain.predictedEpisodePage(item.totalEpisodes, targetOrder)

                var epPage = repository.loadEpisodes(toon, predictedPage)
                var lastIdx = epPage.items.indexOfFirst { it.wrId == item.lastWrId }
                var actualPage = predictedPage

                if (lastIdx < 0 && predictedPage != 1) {
                    epPage = repository.loadEpisodes(toon, 1)
                    lastIdx = epPage.items.indexOfFirst { it.wrId == item.lastWrId }
                    actualPage = 1
                }

                state.update {
                    it.copy(
                        rawEpisodes = epPage.items,
                        episodes = epPage.items.sortedWithOrder(it.episodeSortOrder),
                        episodePage = actualPage,
                        episodeLastPage = epPage.lastPage,
                        episodeLoading = false,
                    )
                }

                if (!isCompleted) {
                    val targetEp = (if (lastIdx >= 0) epPage.items[lastIdx] else null)
                        ?: EpisodeItem(
                            wrId = item.lastWrId,
                            title = item.lastEpisodeTitle,
                            date = null,
                            thumbUrl = null,
                            href = item.lastEpisodeHref,
                            lastReadPage = readEp.lastPage,
                        )
                    val resumePage = ReaderProgress.startPageOnOpen(readEp.lastPage)
                    controller.readerSession.openEpisode(targetEp.copy(lastReadPage = resumePage))
                } else {
                    val nextEp = when {
                        lastIdx > 0 -> epPage.items[lastIdx - 1]
                        lastIdx == 0 && actualPage > 1 -> {
                            try {
                                val prevEpPage = repository.loadEpisodes(toon, actualPage - 1)
                                state.update {
                                    it.copy(
                                        rawEpisodes = prevEpPage.items,
                                        episodes = prevEpPage.items.sortedWithOrder(it.episodeSortOrder),
                                        episodePage = actualPage - 1,
                                        episodeLastPage = prevEpPage.lastPage,
                                    )
                                }
                                prevEpPage.items.lastOrNull() ?: epPage.items[0]
                            } catch (_: Exception) {
                                epPage.items[0]
                            }
                        }
                        lastIdx == 0 -> epPage.items[0]
                        else -> {
                            if (!item.nextWrId.isNullOrBlank()) {
                                epPage.items.firstOrNull { it.wrId == item.nextWrId } ?: epPage.items.firstOrNull()
                            } else {
                                epPage.items.firstOrNull()
                            }
                        }
                    }

                    if (nextEp != null) {
                        controller.readerSession.openEpisode(nextEp.copy(lastReadPage = 0))
                    } else {
                        val fallback = EpisodeItem(
                            wrId = item.lastWrId,
                            title = item.lastEpisodeTitle,
                            date = null,
                            thumbUrl = null,
                            href = item.lastEpisodeHref,
                            lastReadPage = 0,
                        )
                        controller.readerSession.openEpisode(fallback)
                    }
                }
            } catch (_: Exception) {
                state.update { it.copy(episodeLoading = false) }
                val fallback = EpisodeItem(
                    wrId = item.lastWrId,
                    title = item.lastEpisodeTitle,
                    date = null,
                    thumbUrl = null,
                    href = item.lastEpisodeHref,
                    lastReadPage = 0,
                )
                controller.readerSession.openEpisode(fallback)
            }
        }
    }

    fun openDownloadOptions() {
        if (!state.value.writesDownloads) return
        val series = state.value.series ?: return
        downloadCatalogJob?.cancel()
        state.update {
            it.copy(
                showDownloadOptions = true,
                downloadCatalog = emptyList(),
                downloadCatalogLoading = true,
            )
        }
        downloadCatalogJob = scope.launch {
            try {
                val all = repository.loadAllEpisodes(series)
                state.update {
                    it.copy(
                        downloadCatalog = all,
                        downloadCatalogLoading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state.update { current ->
                    current.copy(
                        downloadCatalog = current.rawEpisodes.ifEmpty { current.episodes },
                        downloadCatalogLoading = false,
                    )
                }
            }
        }
    }

    fun closeDownloadOptions() {
        downloadCatalogJob?.cancel()
        state.update {
            it.copy(
                showDownloadOptions = false,
                downloadCatalog = emptyList(),
                downloadCatalogLoading = false,
            )
        }
    }

    fun startDownloadEpisodes(episodesToDownload: List<EpisodeItem>) {
        if (!state.value.writesDownloads) return
        val series = state.value.series ?: return
        closeDownloadOptions()
        repository.downloadManager?.enqueueEpisodes(series, episodesToDownload)
    }

    fun startDownloadEpisode(episode: EpisodeItem) {
        if (!state.value.writesDownloads) return
        val series = state.value.series ?: return
        repository.downloadManager?.enqueueEpisodes(series, listOf(episode))
    }

    fun refreshDownloadedWrIds() {
        val series = state.value.series ?: return
        scope.launch {
            val ids = repository.downloadManager
                ?.getDownloadedEpisodeRecords(series.workId())
                .orEmpty()
                .map { it.wrId }
                .toSet()
            state.update { current ->
                if (current.series?.workId() == series.workId()) {
                    current.copy(downloadedWrIds = ids)
                } else {
                    current
                }
            }
        }
    }

    fun cancelDownloads() {
        repository.downloadManager?.cancelAll()
    }

    fun openDownloads() {
        if (!state.value.writesDownloads) return
        if (state.value.screen == AppScreen.Series) {
            platform.clearImageMemory(ImageCacheRole.EPISODE)
        }
        controller.readerSession.cancelReaderJob()
        controller.browseSearch.cancelEpisodeJob()
        state.update {
            it.copy(
                screen = AppScreen.Downloads,
                downloadLoading = true,
            )
        }
        loadDownloads()
    }

    fun closeDownloads() {
        state.update { it.copy(screen = AppScreen.Browse) }
    }

    fun loadDownloads() {
        val sourceId = state.value.activeSourceId
        if (sourceId == null) {
            state.update { it.copy(downloadLoading = false, downloadSummaries = emptyList()) }
            return
        }
        scope.launch {
            state.update { it.copy(downloadLoading = it.downloadSummaries.isEmpty()) }
            val items = repository.downloadManager
                ?.getDownloadedToonSummaries(sourceId)
                .orEmpty()
            state.update {
                it.copy(
                    downloadSummaries = items,
                    downloadLoading = false,
                    scrollToTopTrigger = it.scrollToTopTrigger + 1,
                )
            }
        }
    }

    fun deleteToonDownloads(workId: WorkId) {
        scope.launch {
            repository.downloadManager?.deleteToonDownloads(workId)
            loadDownloads()
        }
    }
}
