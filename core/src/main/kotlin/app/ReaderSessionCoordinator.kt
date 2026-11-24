package com.comics8.core.app

import com.comics8.core.i18n.I18n
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ReaderDomain
import com.comics8.core.model.ReaderProgress
import com.comics8.core.model.RepositoryTransforms
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ToonItem
import com.comics8.core.model.ViewMode
import com.comics8.core.model.sortedWithOrder
import com.comics8.core.source.network.NetworkSourceRuntime
import com.comics8.core.source.WorkId
import com.comics8.core.storage.ReadHistoryRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private data class ExternalReaderSession(
    val returnScreen: AppScreen,
    val viewMode: ViewMode,
    val readDirection: ReadDirection,
    val splitMode: SplitMode,
)

internal class ReaderSessionCoordinator(
    private val controller: SharedAppController,
) {
    private val state get() = controller._state
    private val repository get() = controller.repository
    private val platform get() = controller.platform
    private val scope get() = controller.scope

    private var readerJob: Job? = null
    private var readerSettingSaveJob: Job? = null
    private var pageSaveJob: Job? = null
    private var pendingPageSave: Triple<WorkId, String, Int>? = null
    private var externalReaderSession: ExternalReaderSession? = null
    private var autoSwitchedEpisodeId: String? = null
    private var preAutoSwitchViewMode: ViewMode? = null
    private var readerToastJob: Job? = null

    var lastViewedEpisode: EpisodeItem? = null
    val hasExternalSession: Boolean get() = externalReaderSession != null

    fun cancelReaderJob() {
        readerJob?.cancel()
    }

    fun close() {
        readerJob?.cancel()
        readerSettingSaveJob?.cancel()
        readerToastJob?.cancel()
        pageSaveJob?.cancel()
        val pending = pendingPageSave
        pendingPageSave = null
        if (pending != null) {
            runBlocking(Dispatchers.IO) {
                repository.saveEpisodePage(pending.first, pending.second, pending.third)
            }
        }
    }

    fun openEpisode(episode: EpisodeItem) {
        controller.browseSearch.cancelEpisodeJob()
        platform.cancelPendingImagePreviews()
        platform.clearImageMemory(ImageCacheRole.READER)
        val current = state.value
        val series = current.series
        if (series != null) {
            controller.browseSearch.lastViewedSeries = series
            controller.browseSearch.lastViewedEpisodePage = current.episodePage
        }
        lastViewedEpisode = episode
        val nowMs = System.currentTimeMillis()
        val rawEpisodes = current.rawEpisodes.ifEmpty { current.episodes }
        val seriesKey = series?.workId()?.storageKey().orEmpty()
        val position = ReaderDomain.episodePosition(
            episodeIds = rawEpisodes.map(EpisodeItem::wrId),
            currentEpisodeId = episode.wrId,
            currentPage = current.episodePage,
            lastPage = current.episodeLastPage,
            knownTotal = current.catalogTotals[seriesKey],
            pageSize = series?.let { repository.sourceOrNull(it.sourceId)?.episodePageSize } ?: 100,
        )
        val nextEp = position.nextEpisodeIndex?.let(rawEpisodes::get)
        val restoredMode = if (platform.autoSwitchWebtoonInReader) restoreAutoSwitchedViewMode() else current.viewMode

        val startPage = ReaderProgress.startPageOnOpen(episode.lastReadPage)
        state.update { curr ->
            val updatedRaw = curr.rawEpisodes.map {
                if (it.wrId == episode.wrId) it.copy(isRead = true, readAt = nowMs) else it
            }
            val updatedEpisodes = curr.episodes.map {
                if (it.wrId == episode.wrId) it.copy(isRead = true, readAt = nowMs) else it
            }
            curr.copy(
                screen = AppScreen.Reader,
                currentEpisode = episode.copy(isRead = true, readAt = nowMs, lastReadPage = startPage),
                rawEpisodes = updatedRaw,
                episodes = updatedEpisodes,
                readerImages = emptyList(),
                imageAspectRatios = emptyMap(),
                readerLoading = true,
                readerError = null,
                readerToastMessage = null,
                viewMode = restoredMode,
            )
        }
        loadReaderImages(episode)
        if (series != null) {
            controller.browseSearch.refreshSeriesFavorited(series)
            scope.launch {
                val savedSetting = repository.getReaderSetting(series.workId())
                if (savedSetting != null) {
                    state.update {
                        it.copy(
                            viewMode = savedSetting.viewMode,
                            readDirection = savedSetting.readDirection,
                            splitMode = savedSetting.splitMode,
                        )
                    }
                }
                repository.markEpisodeRead(series.workId(), episode.wrId, episode.lastReadPage)
                val existingHistory = repository.getHistoryRecord(series.workId())

                val counted = state.value.catalogTotals[seriesKey]
                val progress = RepositoryTransforms.historyProgress(
                    position.readOrder ?: existingHistory?.lastReadOrder ?: 1,
                    counted,
                )
                val finalOrder = progress.lastReadOrder
                val finalTotal = counted?.count ?: 0

                val (progressText, readCount) = controller.listingProgress(
                    series.sourceId,
                    finalOrder,
                    counted,
                    series.workId(),
                )
                val savedHistory = ReadHistoryRecord(
                    sourceId = series.sourceId,
                    toonId = series.id,
                    toonTitle = series.title,
                    toonThumbUrl = series.thumbUrl,
                    toonHref = series.href,
                    lastWrId = episode.wrId,
                    lastEpisodeTitle = episode.title,
                    lastEpisodeHref = episode.href,
                    lastReadOrder = finalOrder,
                    totalEpisodes = finalTotal,
                    lastReadAt = nowMs,
                    nextWrId = nextEp?.wrId ?: existingHistory?.nextWrId,
                    nextEpisodeTitle = nextEp?.title ?: existingHistory?.nextEpisodeTitle,
                    nextEpisodeHref = nextEp?.href ?: existingHistory?.nextEpisodeHref,
                    hasNew = progress.hasNew,
                )
                repository.saveHistoryRecord(savedHistory)
                state.update { curr ->
                    val updatedItems = curr.items.map { item ->
                        if (item.workId() == series.workId()) item.copy(readProgress = progressText) else item
                    }
                    curr.copy(
                        items = updatedItems,
                        seriesHistory = if (curr.series?.workId() == series.workId()) savedHistory else curr.seriesHistory,
                        readCounts = curr.readCounts.withCount(series.workId(), readCount),
                    )
                }
            }
        }
    }

    fun openExternalReaderSession(
        episode: EpisodeItem,
        resolveImages: suspend () -> List<String>,
    ) {
        val current = state.value
        if (externalReaderSession == null) {
            if (current.screen == AppScreen.Reader) {
                flushPendingPageSave()
                repository.syncManager?.triggerDebouncedPush()
            }
            externalReaderSession = ExternalReaderSession(
                returnScreen = if (current.screen == AppScreen.Reader) AppScreen.Series else current.screen,
                viewMode = current.viewMode,
                readDirection = current.readDirection,
                splitMode = current.splitMode,
            )
        } else if (current.screen == AppScreen.Reader) {
            flushPendingPageSave()
        }
        platform.cancelPendingImagePreviews()
        platform.clearImageMemory(ImageCacheRole.READER)
        state.update {
            it.copy(
                screen = AppScreen.Reader,
                currentEpisode = episode,
                readerImages = emptyList(),
                imageAspectRatios = emptyMap(),
                readerLoading = true,
                readerError = null,
                sourceError = null,
            )
        }
        loadReaderImages(episode, asRefresh = false, resolveImages = resolveImages)
    }

    fun savePage(page: Int, seenThroughPage: Int = page) {
        val current = state.value
        if (externalReaderSession != null) {
            state.update { curr ->
                curr.copy(currentEpisode = curr.currentEpisode?.copy(lastReadPage = page))
            }
            return
        }
        val series = current.series ?: return
        val episode = current.currentEpisode ?: return
        val totalImages = current.readerImages.size
        val targetPage = ReaderProgress.persistPage(
            page = page,
            totalImages = totalImages,
            seenThroughPage = seenThroughPage,
        )
        if (episode.lastReadPage == page && pendingPageSave?.third == targetPage) return

        state.update { curr ->
            curr.copy(currentEpisode = curr.currentEpisode?.copy(lastReadPage = page))
        }
        val workId = series.workId()
        val wrId = episode.wrId
        pendingPageSave = Triple(workId, wrId, targetPage)
        pageSaveJob?.cancel()
        pageSaveJob = scope.launch {
            delay(400)
            repository.saveEpisodePage(workId, wrId, targetPage)
            if (pendingPageSave == Triple(workId, wrId, targetPage)) {
                pendingPageSave = null
            }
        }
    }

    fun flushPendingPageSave() {
        pageSaveJob?.cancel()
        pageSaveJob = null
        val pending = pendingPageSave
        pendingPageSave = null
        if (pending != null) {
            scope.launch {
                repository.saveEpisodePage(pending.first, pending.second, pending.third)
            }
        }
    }

    fun closeReader() {
        val externalSession = externalReaderSession
        if (externalSession != null) {
            externalReaderSession = null
            readerJob?.cancel()
            state.value.series?.sourceId?.let { NetworkSourceRuntime.disconnect(it) }
            platform.clearImageMemory(ImageCacheRole.READER)
            restoreAutoSwitchedViewMode()
            state.update {
                it.copy(
                    screen = externalSession.returnScreen,
                    currentEpisode = null,
                    readerImages = emptyList(),
                    imageAspectRatios = emptyMap(),
                    readerLoading = false,
                    readerError = null,
                    readerToastMessage = null,
                    showViewerOptions = false,
                    viewMode = externalSession.viewMode,
                    readDirection = externalSession.readDirection,
                    splitMode = externalSession.splitMode,
                )
            }
            return
        }
        flushPendingPageSave()
        readerJob?.cancel()
        state.value.series?.sourceId?.let { NetworkSourceRuntime.disconnect(it) }
        platform.clearImageMemory(ImageCacheRole.READER)
        val current = state.value
        lastViewedEpisode = current.currentEpisode
        val restoredMode = if (platform.autoSwitchWebtoonInReader) restoreAutoSwitchedViewMode() else current.viewMode
        state.update {
            it.copy(
                screen = AppScreen.Series,
                currentEpisode = null,
                readerImages = emptyList(),
                imageAspectRatios = emptyMap(),
                readerLoading = false,
                readerError = null,
                readerToastMessage = null,
                showViewerOptions = false,
                viewMode = restoredMode,
            )
        }
        repository.syncManager?.triggerDebouncedPush()
    }

    fun evictReaderImage(url: String) {
        platform.evictReaderImage(url)
    }

    fun toggleFullscreen() {
        state.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    fun openNextEpisode() {
        val current = state.value
        val series = current.series
        val currentEp = current.currentEpisode
        if (series != null && currentEp != null && externalReaderSession == null) {
            val totalImages = current.readerImages.size
            val lastPage = if (totalImages > 0) totalImages - 1 else currentEp.lastReadPage
            val completedPage = ReaderProgress.encodePage(lastPage, completed = true)
            scope.launch {
                repository.saveEpisodePage(series.workId(), currentEp.wrId, completedPage)
            }
        }
        val epList = current.episodes.ifEmpty { current.rawEpisodes }
        when (
            val navigation = ReaderDomain.nextEpisode(
                currentIndex = current.currentEpisodeIndex,
                itemCount = epList.size,
                currentPage = current.episodePage,
                sortOrder = current.episodeSortOrder,
            )
        ) {
            is ReaderDomain.EpisodeNavigation.InCurrentPage -> openEpisode(epList[navigation.index])
            is ReaderDomain.EpisodeNavigation.LoadPage -> if (series != null) {
                scope.launch {
                    navigateToEpisodePage(series, navigation.page, navigation.edge)
                }
            }
            ReaderDomain.EpisodeNavigation.None -> Unit
        }
    }

    fun openPrevEpisode() {
        val current = state.value
        val series = current.series
        val epList = current.episodes.ifEmpty { current.rawEpisodes }
        when (
            val navigation = ReaderDomain.prevEpisode(
                currentIndex = current.currentEpisodeIndex,
                itemCount = epList.size,
                currentPage = current.episodePage,
                lastPage = current.episodeLastPage,
                sortOrder = current.episodeSortOrder,
            )
        ) {
            is ReaderDomain.EpisodeNavigation.InCurrentPage -> openEpisode(epList[navigation.index])
            is ReaderDomain.EpisodeNavigation.LoadPage -> if (series != null) {
                scope.launch {
                    navigateToEpisodePage(series, navigation.page, navigation.edge)
                }
            }
            ReaderDomain.EpisodeNavigation.None -> Unit
        }
    }

    private suspend fun navigateToEpisodePage(
        series: ToonItem,
        page: Int,
        edge: ReaderDomain.PageEdge,
    ) {
        try {
            val result = repository.loadEpisodes(series, page)
            state.update {
                it.copy(
                    rawEpisodes = result.items,
                    episodes = result.items.sortedWithOrder(it.episodeSortOrder),
                    episodePage = result.currentPage,
                    episodeLastPage = result.lastPage,
                )
            }
            val target = when (edge) {
                ReaderDomain.PageEdge.FIRST -> result.items.firstOrNull()
                ReaderDomain.PageEdge.LAST -> result.items.lastOrNull()
            }
            target?.let(::openEpisode)
        } catch (_: Exception) {
        }
    }

    private fun persistCurrentReaderSetting() {
        if (externalReaderSession != null) return
        val current = state.value
        val series = current.series ?: return
        readerSettingSaveJob?.cancel()
        readerSettingSaveJob = scope.launch {
            repository.saveReaderSetting(
                series.workId(),
                current.viewMode,
                current.readDirection,
                current.splitMode,
            )
        }
    }

    fun setViewMode(mode: ViewMode) {
        if (platform.persistGlobalReaderDefaults &&
            (mode == ViewMode.AUTO || mode == ViewMode.SCROLL || mode == ViewMode.PAGE)
        ) {
            controller.singleViewPreference = mode
        }
        preAutoSwitchViewMode = null
        if (mode == ViewMode.AUTO) autoSwitchedEpisodeId = null
        state.update { it.copy(viewMode = mode) }
        persistCurrentReaderSetting()
    }

    fun setReadDirection(direction: ReadDirection) {
        if (platform.persistGlobalReaderDefaults) {
            controller.globalReadDirection = direction
        }
        state.update { it.copy(readDirection = direction) }
        persistCurrentReaderSetting()
    }

    fun setSplitMode(mode: SplitMode) {
        state.update { it.copy(splitMode = mode) }
        persistCurrentReaderSetting()
    }

    fun refreshReaderImages() {
        platform.clearImageFailures()
        platform.clearImageMemory(ImageCacheRole.READER)
        state.update { it.copy(refreshEpoch = it.refreshEpoch + 1) }
        if (externalReaderSession != null) return
        val episode = state.value.currentEpisode ?: return
        loadReaderImages(episode, asRefresh = false)
    }

    fun loadReaderImages(episode: EpisodeItem, asRefresh: Boolean = false) {
        val workId = state.value.series?.workId()
        loadReaderImages(episode, asRefresh) { repository.loadImages(episode, workId) }
    }

    private fun loadReaderImages(
        episode: EpisodeItem,
        asRefresh: Boolean,
        resolveImages: suspend () -> List<String>,
    ) {
        readerJob?.cancel()
        readerJob = scope.launch {
            state.update {
                it.copy(
                    readerLoading = it.readerImages.isEmpty() || !asRefresh,
                    readerError = null,
                    refreshing = asRefresh,
                )
            }
            try {
                val images = resolveImages()
                if (images.isEmpty()) {
                    state.update {
                        it.copy(
                            readerImages = emptyList(),
                            imageAspectRatios = emptyMap(),
                            readerLoading = false,
                            refreshing = false,
                            readerError = "이미지가 없습니다.",
                        )
                    }
                    return@launch
                }
                state.update {
                    it.copy(
                        readerImages = images,
                        imageAspectRatios = emptyMap(),
                        readerLoading = false,
                        refreshing = false,
                        readerError = null,
                    )
                }
                val effectivePage = state.value.currentEpisode?.lastReadPage ?: episode.lastReadPage
                val targetPage = ReaderDomain.initialImagePage(effectivePage, images.size) ?: 0
                val preloadUrls = images.subList(
                    (targetPage - 1).coerceAtLeast(0),
                    (targetPage + 3).coerceAtMost(images.size),
                )
                platform.preloadReaderImages(preloadUrls)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update {
                    it.copy(
                        readerLoading = false,
                        refreshing = false,
                        readerError = humanError("만화 이미지를 불러오지 못했습니다.", e),
                    )
                }
            }
        }
    }

    fun recordImageAspectRatio(index: Int, width: Int, height: Int) {
        val ratio = ReaderDomain.imageAspectRatio(width, height) ?: return
        state.update { current ->
            if (current.imageAspectRatios[index] == ratio) {
                current
            } else {
                current.copy(imageAspectRatios = current.imageAspectRatios + (index to ratio))
            }
        }
        if (platform.autoSwitchWebtoonInReader && ReaderDomain.isAutoScrollCandidate(index)) {
            checkAndAutoSwitchToScroll(width, height)
        }
    }

    private fun checkAndAutoSwitchToScroll(width: Int, height: Int) {
        val currentEp = state.value.currentEpisode ?: return
        if (autoSwitchedEpisodeId == currentEp.wrId) return
        val currentMode = state.value.viewMode
        if (currentMode != ViewMode.AUTO) return

        if (ReaderDomain.isWebtoonStrip(width, height)) {
            autoSwitchedEpisodeId = currentEp.wrId
            preAutoSwitchViewMode = currentMode
            val toast = I18n.strings(state.value.appLanguage).toastAutoSwitchedToScroll
            state.update {
                it.copy(
                    viewMode = ViewMode.SCROLL,
                    readerToastMessage = toast,
                )
            }
            readerToastJob?.cancel()
            readerToastJob = scope.launch {
                delay(1000)
                state.update { it.copy(readerToastMessage = null) }
            }
        }
    }

    private fun restoreAutoSwitchedViewMode(): ViewMode {
        val mode = preAutoSwitchViewMode ?: state.value.viewMode
        preAutoSwitchViewMode = null
        autoSwitchedEpisodeId = null
        readerToastJob?.cancel()
        return mode
    }
}
