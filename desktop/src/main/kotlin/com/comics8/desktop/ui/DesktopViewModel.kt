package com.comics8.desktop.ui

import com.comics8.core.sync.AppUpdateChecker
import com.comics8.core.sync.SyncConstants
import com.comics8.desktop.DesktopVersion
import com.comics8.desktop.data.Catalog
import com.comics8.desktop.data.DesktopToonRepository
import com.comics8.desktop.data.DesktopUpdateManager
import com.comics8.desktop.data.EpisodeItem
import com.comics8.desktop.data.ReadDirection
import com.comics8.desktop.data.ReadHistoryRecord
import com.comics8.desktop.data.ToonItem
import com.comics8.desktop.data.ViewMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class DesktopViewModel(
    val repository: DesktopToonRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(DesktopUiState())
    val state: StateFlow<DesktopUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var episodeJob: Job? = null
    private var readerJob: Job? = null

    init {
        loadPage(1)
        checkForUpdate()
        repository.syncManager?.let { sm ->
            scope.launch {
                sm.syncState.collect { s ->
                    _state.update { it.copy(syncState = s) }
                }
            }
            scope.launch {
                val res = sm.syncPull()
                if (res.success && (res.favoritesCount > 0 || res.historyCount > 0)) {
                    loadPage(_state.value.page, replace = true)
                }
            }
        }
    }

    fun checkForUpdate() {
        scope.launch(Dispatchers.IO) {
            val currentVersionName = DesktopVersion.VERSION_NAME
            val currentVersionCode = DesktopVersion.VERSION_CODE
            val serverUrl = _state.value.syncState.serverUrl.ifBlank { SyncConstants.DEFAULT_SERVER_URL }
            val updateState = AppUpdateChecker.checkDesktopUpdate(
                currentVersionName = currentVersionName,
                currentVersionCode = currentVersionCode,
                serverUrl = serverUrl,
            )
            _state.update { it.copy(updateState = updateState) }
        }
    }

    fun openUpdateDialog() {
        _state.update { it.copy(showUpdateDialog = true) }
    }

    fun closeUpdateDialog() {
        _state.update { it.copy(showUpdateDialog = false) }
    }

    fun startUpdate() {
        val updateUrl = _state.value.updateState.downloadUrl
        if (updateUrl.isBlank()) return
        scope.launch {
            _state.update {
                it.copy(
                    updateState = it.updateState.copy(
                        isDownloading = true,
                        downloadProgress = 0f,
                        error = null,
                    )
                )
            }
            val result = DesktopUpdateManager.downloadAndApplyUpdate(
                downloadUrl = updateUrl,
                onProgress = { progress ->
                    _state.update { it.copy(updateState = it.updateState.copy(downloadProgress = progress)) }
                }
            )
            result.fold(
                onSuccess = {
                    _state.update { it.copy(updateState = it.updateState.copy(isDownloading = false)) }
                },
                onFailure = { err ->
                    _state.update {
                        it.copy(
                            updateState = it.updateState.copy(
                                isDownloading = false,
                                error = "업데이트 실패: ${err.localizedMessage ?: err.message}"
                            )
                        )
                    }
                }
            )
        }
    }

    fun selectCatalog(catalog: Catalog) {
        if (_state.value.catalog == catalog && _state.value.screen == Screen.Browse && !_state.value.isSearch) {
            refresh()
            return
        }
        loadJob?.cancel()
        _state.update {
            it.copy(
                catalog = catalog,
                page = 1,
                lastPage = 1,
                items = emptyList(),
                loading = true,
                error = null,
                screen = Screen.Browse,
                searchQuery = null,
                searchInput = "",
            )
        }
        loadPage(1)
    }

    fun loadPage(page: Int, replace: Boolean = false) {
        loadJob?.cancel()
        val catalog = _state.value.catalog
        val targetPage = page.coerceAtLeast(1)

        loadJob = scope.launch {
            _state.update {
                it.copy(
                    loading = if (replace) it.items.isEmpty() else true,
                    refreshing = replace && it.items.isNotEmpty(),
                    error = null,
                )
            }
            try {
                val result = repository.loadListing(catalog, targetPage)
                _state.update {
                    it.copy(
                        page = result.currentPage,
                        lastPage = result.lastPage,
                        items = result.items,
                        loading = false,
                        refreshing = false,
                        error = null,
                    )
                }
                if (result.items.isNotEmpty() && catalog != Catalog.FAVORITE) {
                    repository.markSeen(result.items)
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = e.localizedMessage ?: "목록을 불러오지 못했습니다.",
                    )
                }
            }
        }
    }

    fun refresh() {
        checkForUpdate()
        if (_state.value.isSearch) {
            val query = _state.value.searchQuery.orEmpty()
            if (query.isNotEmpty()) search(query)
        } else {
            loadPage(_state.value.page, replace = true)
        }
    }

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        loadJob?.cancel()
        _state.update {
            it.copy(
                searchQuery = q,
                searchInput = q,
                items = emptyList(),
                page = 1,
                lastPage = 1,
                loading = true,
                error = null,
                screen = Screen.Browse,
            )
        }
        loadJob = scope.launch {
            try {
                val results = repository.search(q)
                _state.update {
                    it.copy(
                        items = results,
                        loading = false,
                        error = if (results.isEmpty()) "'$q' 검색 결과가 없습니다." else null,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        error = e.localizedMessage ?: "검색에 실패했습니다.",
                    )
                }
            }
        }
    }

    fun updateSearchInput(input: String) {
        _state.update { it.copy(searchInput = input) }
    }

    fun toggleSearchBar() {
        _state.update {
            val nextExpanded = !it.searchExpanded
            it.copy(
                searchExpanded = nextExpanded,
                searchInput = if (!nextExpanded) "" else it.searchInput,
            )
        }
    }

    fun openSeries(item: ToonItem) {
        episodeJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.Series,
                series = item,
                seriesHistory = null,
                episodes = emptyList(),
                episodePage = 1,
                episodeLastPage = 1,
                episodeError = null,
                seriesFavorited = false,
            )
        }
        loadEpisodes(item, 1)
        scope.launch {
            val favorited = repository.isFavorite(item.id)
            val history = repository.getHistory().firstOrNull { it.toonId == item.id }
            _state.update { current ->
                if (current.series?.id == item.id) {
                    current.copy(
                        seriesFavorited = favorited,
                        seriesHistory = history,
                    )
                } else {
                    current
                }
            }
        }
    }

    fun loadEpisodes(item: ToonItem, page: Int) {
        episodeJob?.cancel()
        episodeJob = scope.launch {
            _state.update {
                it.copy(
                    episodeLoading = true,
                    episodeError = null,
                )
            }
            try {
                val result = repository.loadEpisodes(item, page)
                _state.update {
                    it.copy(
                        episodes = result.items,
                        episodePage = result.currentPage,
                        episodeLastPage = result.lastPage,
                        episodeLoading = false,
                        episodeError = null,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                _state.update {
                    it.copy(
                        episodeLoading = false,
                        episodeError = e.localizedMessage ?: "회차 목록을 불러오지 못했습니다.",
                    )
                }
            }
        }
    }

    fun goToEpisodePage(page: Int) {
        val series = _state.value.series ?: return
        val clamped = page.coerceIn(1, _state.value.episodeLastPage.coerceAtLeast(1))
        loadEpisodes(series, clamped)
    }

    fun goToPage(page: Int) {
        val clamped = page.coerceIn(1, _state.value.lastPage.coerceAtLeast(1))
        loadPage(clamped)
    }

    fun togglePageJump(show: Boolean) {
        _state.update { it.copy(showPageJump = show) }
    }

    fun toggleFavorite() {
        val series = _state.value.series ?: return
        toggleFavorite(series)
    }

    fun toggleFavorite(item: ToonItem) {
        scope.launch {
            val favorited = repository.toggleFavorite(item)
            _state.update { current ->
                val updated = current.items.map { row ->
                    if (row.id == item.id) row.copy(isFavorite = favorited) else row
                }
                val visible = if (
                    !favorited &&
                    current.catalog == Catalog.FAVORITE &&
                    current.screen == Screen.Browse &&
                    !current.isSearch
                ) {
                    updated.filter { it.id != item.id }
                } else {
                    updated
                }
                current.copy(
                    seriesFavorited = if (current.series?.id == item.id) favorited else current.seriesFavorited,
                    items = visible,
                )
            }
        }
    }

    fun resumeSeries() {
        val current = _state.value
        val series = current.series ?: return
        val hist = current.seriesHistory ?: run {
            openFirstEpisode()
            return
        }
        scope.launch {
            val readEp = repository.getReadEpisode(series.id, hist.lastWrId)
            val lastSavedPage = readEp?.lastPage ?: 0
            if (lastSavedPage > 0) {
                reopenHistoryEpisode(hist, startPage = lastSavedPage)
            } else {
                continueHistoryEpisode(hist)
            }
        }
    }

    fun openFirstEpisode() {
        val current = _state.value
        val series = current.series ?: return
        val lastPage = current.episodeLastPage
        if (lastPage <= 1) {
            val firstEp = current.episodes.lastOrNull()
            if (firstEp != null) {
                openEpisode(firstEp)
            } else {
                scope.launch {
                    try {
                        val result = repository.loadEpisodes(series, 1)
                        _state.update {
                            it.copy(
                                episodes = result.items,
                                episodePage = result.currentPage,
                                episodeLastPage = result.lastPage,
                            )
                        }
                        result.items.lastOrNull()?.let { openEpisode(it) }
                    } catch (_: Exception) {
                    }
                }
            }
        } else {
            scope.launch {
                try {
                    val result = repository.loadEpisodes(series, lastPage)
                    _state.update {
                        it.copy(
                            episodes = result.items,
                            episodePage = result.currentPage,
                            episodeLastPage = result.lastPage,
                        )
                    }
                    val firstEp = result.items.lastOrNull()
                    if (firstEp != null) {
                        openEpisode(firstEp)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun closeSeries() {
        readerJob?.cancel()
        episodeJob?.cancel()
        val catalog = _state.value.catalog
        val searching = _state.value.isSearch
        _state.update {
            it.copy(
                screen = Screen.Browse,
                series = null,
                seriesHistory = null,
                episodes = emptyList(),
                episodeError = null,
                showPageJump = false,
                seriesFavorited = false,
                currentEpisode = null,
                readerImages = emptyList(),
                readerError = null,
            )
        }
        if (catalog == Catalog.FAVORITE && !searching) {
            loadPage(1, replace = true)
        }
    }

    fun openHistory() {
        loadHistory()
        _state.update { it.copy(screen = Screen.History) }
    }

    fun closeHistory() {
        _state.update { it.copy(screen = Screen.Browse) }
    }

    fun loadHistory() {
        scope.launch {
            _state.update { it.copy(historyLoading = it.historyItems.isEmpty()) }
            val items = repository.getHistory()
            _state.update { it.copy(historyItems = items, historyLoading = false) }
        }
    }

    fun deleteHistory(toonId: String) {
        scope.launch {
            repository.deleteHistory(toonId)
            _state.update { current ->
                val updatedItems = current.items.map {
                    if (it.id == toonId) it.copy(readProgress = null) else it
                }
                current.copy(
                    historyItems = current.historyItems.filter { it.toonId != toonId },
                    items = updatedItems,
                    seriesHistory = if (current.series?.id == toonId) null else current.seriesHistory,
                )
            }
        }
    }

    fun clearAllHistory() {
        scope.launch {
            repository.clearHistory()
            _state.update { current ->
                val updatedItems = current.items.map { it.copy(readProgress = null) }
                current.copy(
                    historyItems = emptyList(),
                    items = updatedItems,
                    seriesHistory = null,
                )
            }
        }
    }

    fun openSyncDialog() {
        _state.update { it.copy(showSyncDialog = true) }
    }

    fun closeSyncDialog() {
        _state.update { it.copy(showSyncDialog = false) }
    }

    fun syncNow() {
        scope.launch {
            val res = repository.syncManager?.syncFull()
            if (res?.success == true) {
                loadPage(_state.value.page, replace = true)
                val updatedHistory = repository.getHistory()
                _state.update { it.copy(historyItems = updatedHistory) }
            }
        }
    }

    fun updateSyncKey(key: String) {
        repository.syncManager?.updateSyncKey(key)
    }

    fun generateNewSyncKey() {
        repository.syncManager?.generateNewKey()
    }

    fun updateSyncServerUrl(url: String) {
        repository.syncManager?.updateServerUrl(url)
    }

    fun toggleAutoSync(enabled: Boolean) {
        repository.syncManager?.setAutoSyncEnabled(enabled)
    }

    fun openSeriesFromHistory(item: ReadHistoryRecord) {
        val toon = ToonItem(
            id = item.toonId,
            title = item.toonTitle,
            thumbUrl = item.toonThumbUrl,
            href = item.toonHref,
            genre = "",
            updatedAt = null,
        )
        openSeries(toon)
    }

    fun reopenHistoryEpisode(item: ReadHistoryRecord, startPage: Int = 0) {
        val toon = ToonItem(
            id = item.toonId,
            title = item.toonTitle,
            thumbUrl = item.toonThumbUrl,
            href = item.toonHref,
            genre = "",
            updatedAt = null,
        )
        episodeJob?.cancel()
        readerJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.Reader,
                series = toon,
                episodes = emptyList(),
                readerLoading = true,
                readerError = null,
                readerImages = emptyList(),
            )
        }
        scope.launch {
            try {
                val pageToFetch = if (startPage == 0) {
                    val readEp = repository.getReadEpisode(item.toonId, item.lastWrId)
                    readEp?.lastPage ?: 0
                } else startPage

                val predictedPage = if (item.totalEpisodes > item.lastReadOrder && item.totalEpisodes > 100) {
                    ((item.totalEpisodes - item.lastReadOrder) / 100 + 1).coerceAtLeast(1)
                } else 1

                var epPage = repository.loadEpisodes(toon, predictedPage)
                var targetEp = epPage.items.firstOrNull { it.wrId == item.lastWrId }
                var actualPage = predictedPage

                if (targetEp == null && predictedPage != 1) {
                    epPage = repository.loadEpisodes(toon, 1)
                    targetEp = epPage.items.firstOrNull { it.wrId == item.lastWrId }
                    actualPage = 1
                }

                _state.update {
                    it.copy(
                        episodes = epPage.items,
                        episodePage = actualPage,
                        episodeLastPage = epPage.lastPage,
                    )
                }

                val epToOpen = (targetEp ?: EpisodeItem(
                    wrId = item.lastWrId,
                    title = item.lastEpisodeTitle,
                    date = null,
                    thumbUrl = null,
                    href = item.lastEpisodeHref,
                    lastReadPage = pageToFetch,
                )).copy(lastReadPage = pageToFetch)
                openEpisode(epToOpen)
            } catch (_: Exception) {
                val fallback = EpisodeItem(
                    wrId = item.lastWrId,
                    title = item.lastEpisodeTitle,
                    date = null,
                    thumbUrl = null,
                    href = item.lastEpisodeHref,
                    lastReadPage = startPage,
                )
                openEpisode(fallback)
            }
        }
    }

    fun continueHistoryEpisode(item: ReadHistoryRecord) {
        val toon = ToonItem(
            id = item.toonId,
            title = item.toonTitle,
            thumbUrl = item.toonThumbUrl,
            href = item.toonHref,
            genre = "",
            updatedAt = null,
        )
        episodeJob?.cancel()
        readerJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.Reader,
                series = toon,
                episodes = emptyList(),
                readerLoading = true,
                readerError = null,
                readerImages = emptyList(),
            )
        }
        scope.launch {
            try {
                val nextOrder = item.lastReadOrder + 1
                val predictedPage = if (item.totalEpisodes > nextOrder && item.totalEpisodes > 100) {
                    ((item.totalEpisodes - nextOrder) / 100 + 1).coerceAtLeast(1)
                } else 1

                val epPage = repository.loadEpisodes(toon, predictedPage)
                _state.update {
                    it.copy(
                        episodes = epPage.items,
                        episodePage = epPage.currentPage,
                        episodeLastPage = epPage.lastPage,
                    )
                }

                val lastIdx = epPage.items.indexOfFirst { it.wrId == item.lastWrId }
                val nextEp = when {
                    lastIdx > 0 -> epPage.items[lastIdx - 1]
                    lastIdx == 0 && epPage.currentPage > 1 -> {
                        try {
                            val prevEpPage = repository.loadEpisodes(toon, epPage.currentPage - 1)
                            _state.update {
                                it.copy(
                                    episodes = prevEpPage.items,
                                    episodePage = prevEpPage.currentPage,
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
                    openEpisode(nextEp)
                } else {
                    val fallback = EpisodeItem(
                        wrId = item.lastWrId,
                        title = item.lastEpisodeTitle,
                        date = null,
                        thumbUrl = null,
                        href = item.lastEpisodeHref,
                        lastReadPage = 0,
                    )
                    openEpisode(fallback)
                }
            } catch (_: Exception) {
                val fallback = EpisodeItem(
                    wrId = item.lastWrId,
                    title = item.lastEpisodeTitle,
                    date = null,
                    thumbUrl = null,
                    href = item.lastEpisodeHref,
                    lastReadPage = 0,
                )
                openEpisode(fallback)
            }
        }
    }

    fun openEpisode(episode: EpisodeItem) {
        val current = _state.value
        val series = current.series
        val nowMs = System.currentTimeMillis()
        val episodes = current.episodes
        val idx = episodes.indexOfFirst { it.wrId == episode.wrId }
        val lastPage = current.episodeLastPage.coerceAtLeast(1)
        val currentPage = current.episodePage.coerceIn(1, lastPage)
        val pageSize = 100

        val totalEpCount = if (lastPage <= 1) {
            episodes.size.coerceAtLeast(1)
        } else if (currentPage == lastPage && episodes.isNotEmpty()) {
            (lastPage - 1) * pageSize + episodes.size
        } else {
            lastPage * pageSize
        }

        val calculatedOrder = if (idx >= 0 && episodes.isNotEmpty()) {
            val olderCount = (lastPage - currentPage) * pageSize
            val onPageOrder = (episodes.size - idx).coerceAtLeast(1)
            (olderCount + onPageOrder).coerceIn(1, totalEpCount)
        } else null

        val nextEp = if (idx in 1 until episodes.size) episodes[idx - 1] else null

        _state.update { curr ->
            val updatedEpisodes = curr.episodes.map {
                if (it.wrId == episode.wrId) it.copy(isRead = true, readAt = nowMs) else it
            }
            curr.copy(
                screen = Screen.Reader,
                currentEpisode = episode.copy(isRead = true, readAt = nowMs),
                episodes = updatedEpisodes,
                readerImages = emptyList(),
                imageAspectRatios = emptyMap(),
                readerLoading = true,
                readerError = null,
            )
        }
        loadReaderImages(episode)
        if (series != null) {
            scope.launch {
                val savedSetting = repository.getReaderSetting(series.id)
                if (savedSetting != null) {
                    _state.update { it.copy(viewMode = savedSetting.first, readDirection = savedSetting.second) }
                }
                repository.markEpisodeRead(series.id, episode.wrId, episode.lastReadPage)
                val existingHistory = repository.getHistory().firstOrNull { it.toonId == series.id }

                val finalOrder = calculatedOrder
                    ?: existingHistory?.lastReadOrder
                    ?: 1

                val candidateTotal = if (totalEpCount > 1) totalEpCount else (existingHistory?.totalEpisodes ?: 1)
                val finalTotal = maxOf(candidateTotal, finalOrder, existingHistory?.totalEpisodes ?: 1)

                val progressText = "$finalOrder/$finalTotal"
                val savedHistory = ReadHistoryRecord(
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
                    hasNew = false,
                )
                repository.saveHistory(savedHistory)
                _state.update { curr ->
                    val updatedItems = curr.items.map { item ->
                        if (item.id == series.id) item.copy(readProgress = progressText) else item
                    }
                    curr.copy(
                        items = updatedItems,
                        seriesHistory = if (curr.series?.id == series.id) savedHistory else curr.seriesHistory,
                    )
                }
            }
        }
    }

    private fun loadReaderImages(episode: EpisodeItem) {
        readerJob?.cancel()
        readerJob = scope.launch {
            try {
                val images = repository.loadImages(episode)
                _state.update {
                    it.copy(
                        readerImages = images,
                        readerLoading = false,
                        readerError = null,
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                _state.update {
                    it.copy(
                        readerLoading = false,
                        readerError = e.localizedMessage ?: "이미지를 불러오지 못했습니다.",
                    )
                }
            }
        }
    }

    fun openNextEpisode() {
        val current = _state.value
        val idx = current.currentEpisodeIndex
        if (idx in 1 until current.episodes.size) {
            openEpisode(current.episodes[idx - 1])
        } else if (idx == 0 && current.episodePage > 1) {
            val series = current.series ?: return
            val targetPage = current.episodePage - 1
            scope.launch {
                try {
                    val result = repository.loadEpisodes(series, targetPage)
                    _state.update {
                        it.copy(
                            episodes = result.items,
                            episodePage = result.currentPage,
                            episodeLastPage = result.lastPage,
                        )
                    }
                    result.items.lastOrNull()?.let { openEpisode(it) }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun openPrevEpisode() {
        val current = _state.value
        val idx = current.currentEpisodeIndex
        if (idx in 0 until (current.episodes.size - 1)) {
            openEpisode(current.episodes[idx + 1])
        } else if (idx == current.episodes.size - 1 && current.episodePage < current.episodeLastPage) {
            val series = current.series ?: return
            val targetPage = current.episodePage + 1
            scope.launch {
                try {
                    val result = repository.loadEpisodes(series, targetPage)
                    _state.update {
                        it.copy(
                            episodes = result.items,
                            episodePage = result.currentPage,
                            episodeLastPage = result.lastPage,
                        )
                    }
                    result.items.firstOrNull()?.let { openEpisode(it) }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun savePage(page: Int) {
        val current = _state.value
        val series = current.series ?: return
        val episode = current.currentEpisode ?: return
        val totalImages = current.readerImages.size
        val isLastPage = totalImages > 0 && page >= totalImages - 1
        val targetPage = if (isLastPage) 0 else page.coerceAtLeast(0)

        _state.update { state ->
            val updatedEpisode = state.currentEpisode?.copy(lastReadPage = targetPage)
            val updatedEpisodes = state.episodes.map {
                if (it.wrId == episode.wrId) it.copy(lastReadPage = targetPage) else it
            }
            state.copy(
                currentEpisode = updatedEpisode,
                episodes = updatedEpisodes,
            )
        }
        scope.launch {
            repository.saveEpisodePage(series.id, episode.wrId, targetPage)
        }
    }

    fun setViewMode(mode: ViewMode) {
        _state.update { it.copy(viewMode = mode) }
        val series = _state.value.series ?: return
        val direction = _state.value.readDirection
        scope.launch {
            repository.saveReaderSetting(series.id, mode, direction)
        }
    }

    fun setReadDirection(direction: ReadDirection) {
        _state.update { it.copy(readDirection = direction) }
        val series = _state.value.series ?: return
        val mode = _state.value.viewMode
        scope.launch {
            repository.saveReaderSetting(series.id, mode, direction)
        }
    }

    fun recordImageAspectRatio(index: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val ratio = width.toFloat() / height.toFloat()
        _state.update {
            it.copy(imageAspectRatios = it.imageAspectRatios + (index to ratio))
        }
    }

    fun closeReader() {
        readerJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.Series,
                currentEpisode = null,
                readerImages = emptyList(),
                imageAspectRatios = emptyMap(),
                readerLoading = false,
                readerError = null,
            )
        }
        repository.syncManager?.triggerDebouncedPush()
    }

    fun toggleFullscreen() {
        _state.update { it.copy(isFullscreen = !it.isFullscreen) }
    }
}
