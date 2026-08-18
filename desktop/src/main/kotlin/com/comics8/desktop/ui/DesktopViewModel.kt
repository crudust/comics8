package com.comics8.desktop.ui

import com.comics8.core.model.ArtistRef
import com.comics8.core.model.BrowseTab
import com.comics8.core.source.WorkId
import com.comics8.core.sync.AppUpdateChecker
import com.comics8.core.sync.SyncConstants
import com.comics8.desktop.DesktopVersion
import com.comics8.desktop.data.DesktopSourcePrefs
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
    private var countUpdateJob: Job? = null
    private var downloadCatalogJob: Job? = null

    private val catalogCache = mutableMapOf<BrowseTab, List<ToonItem>>()
    private val catalogPages = mutableMapOf<BrowseTab, Pair<Int, Int>>()

    private val toonTotalCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val toonLastPageCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // 계층적 내비게이션 상태 (그리드 <-> 회차리스트 <-> 뷰어)
    private var lastViewedSeries: ToonItem? = null
    private var lastViewedEpisodePage: Int = 1
    private var lastViewedEpisode: EpisodeItem? = null
    private var previousScreenBeforeSeries: Screen = Screen.Browse

    fun goBack(): Boolean {
        val current = _state.value
        if (current.artistPick != null) {
            dismissArtistPick()
            return true
        }
        if (current.showPageJump) {
            togglePageJump(false)
            return true
        }
        if (current.showSyncDialog) {
            closeSyncDialog()
            return true
        }
        if (current.showUpdateDialog) {
            closeUpdateDialog()
            return true
        }
        if (current.showDownloadOptions) {
            closeDownloadOptions()
            return true
        }

        return when (current.screen) {
            Screen.Reader -> {
                // 3단계(뷰어) -> 2단계(회차리스트)로 즉시 복귀
                lastViewedEpisode = current.currentEpisode
                closeReader()
                true
            }
            Screen.Series -> {
                // 2단계(회차리스트) -> 1단계(그리드/히스토리/다운로드)로 즉시 복귀
                lastViewedSeries = current.series
                lastViewedEpisodePage = current.episodePage
                val prev = previousScreenBeforeSeries
                if (prev == Screen.History) {
                    openHistory()
                } else if (prev == Screen.Downloads) {
                    openDownloads()
                } else {
                    closeSeries()
                }
                true
            }
            Screen.History -> {
                closeHistory()
                true
            }
            Screen.Downloads -> {
                closeDownloads()
                true
            }
            Screen.Browse -> {
                if (current.isSearch) {
                    selectTab(current.tab)
                    true
                } else {
                    false
                }
            }
        }
    }

    fun goForward(): Boolean {
        val current = _state.value
        return when (current.screen) {
            Screen.Browse, Screen.History, Screen.Downloads -> {
                // 1단계(그리드) -> 2단계(회차리스트)로 이동
                val targetSeries = lastViewedSeries ?: current.items.firstOrNull()
                if (targetSeries != null) {
                    openSeries(targetSeries)
                    if (lastViewedEpisodePage > 1) {
                        goToEpisodePage(lastViewedEpisodePage)
                    }
                    true
                } else false
            }
            Screen.Series -> {
                // 2단계(회차리스트) -> 3단계(뷰어)로 이동
                val targetEp = lastViewedEpisode
                    ?: current.seriesHistory?.let { h -> current.episodes.firstOrNull { it.wrId == h.lastWrId } }
                    ?: current.episodes.lastOrNull() // 1화
                    ?: current.episodes.firstOrNull()
                if (targetEp != null) {
                    openEpisode(targetEp)
                    true
                } else false
            }
            Screen.Reader -> {
                // 3단계(뷰어) -> 다음 회차가 있으면 다음 회차로 이동
                if (current.hasNextEpisode) {
                    openNextEpisode()
                    true
                } else false
            }
        }
    }

    init {
        val storedSource = DesktopSourcePrefs.activeSourceId
        val activeSource = repository.sourceOrDefault(storedSource).id
        repository.applyPreferences { id ->
            DesktopSourcePrefs.language(id).ifBlank { null }
        }
        repository.setSourceWriteAccess(DesktopSourcePrefs::isEnabled)
        val tabs = BrowseTab.forSource(repository.source(activeSource))
        _state.update {
            it.copy(
                activeSourceId = activeSource,
                browseTabs = tabs,
                tab = tabs.first(),
            )
        }
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
        repository.downloadManager?.let { dm ->
            scope.launch {
                dm.progress.collect { p ->
                    val prev = _state.value.downloadProgress
                    _state.update { it.copy(downloadProgress = p) }
                    val prevWrId = prev.currentTask?.episode?.wrId
                    val nextWrId = p.currentTask?.episode?.wrId
                    if ((prev.isRunning && !p.isRunning) || (prevWrId != null && prevWrId != nextWrId)) {
                        refreshDownloadedWrIds()
                        if (_state.value.screen == Screen.Downloads) {
                            loadDownloads()
                        }
                    }
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

    fun selectTab(tab: BrowseTab) {
        if (
            tab == _state.value.tab &&
            _state.value.screen == Screen.Browse &&
            !_state.value.isSearch
        ) return
        loadJob?.cancel()
        episodeJob?.cancel()
        readerJob?.cancel()
        val cached = catalogCache[tab]
        val (cachedPage, cachedLastPage) = catalogPages[tab] ?: Pair(1, 1)
        _state.update {
            it.copy(
                tab = tab,
                page = cachedPage,
                lastPage = cachedLastPage,
                items = cached ?: emptyList(),
                loading = cached == null,
                error = null,
                screen = Screen.Browse,
                searchQuery = null,
                searchInput = "",
                series = null,
                episodes = emptyList(),
                currentEpisode = null,
                readerImages = emptyList(),
                readerError = null,
            )
        }
        if (cached == null) {
            loadPage(cachedPage)
        }
    }

    fun setActiveSource(sourceId: String) {
        val resolved = repository.sourceOrDefault(sourceId).id
        if (resolved != WorkId.DEFAULT_SOURCE) {
            DesktopSourcePrefs.setEnabled(resolved, true)
        }
        repository.setSourceWriteAccess(DesktopSourcePrefs::isEnabled)
        DesktopSourcePrefs.activeSourceId = resolved
        val tabs = BrowseTab.forSource(repository.source(resolved))
        val nextTab = BrowseTab.afterSourceChange(_state.value.tab, tabs)
        _state.update {
            it.copy(
                activeSourceId = resolved,
                browseTabs = tabs,
            )
        }
        selectTab(nextTab)
    }

    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        val resolved = repository.sourceOrDefault(sourceId).id
        if (resolved == WorkId.DEFAULT_SOURCE) return
        DesktopSourcePrefs.setEnabled(resolved, enabled)
        repository.setSourceWriteAccess(DesktopSourcePrefs::isEnabled)
        if (!enabled && _state.value.activeSourceId == resolved) {
            setActiveSource(WorkId.DEFAULT_SOURCE)
        } else {
            val tabs = BrowseTab.forSource(repository.source(_state.value.activeSourceId))
            _state.update { it.copy(browseTabs = tabs) }
        }
    }

    fun onListingOpen(item: ToonItem) {
        if (item.artistChoices.size >= 2) {
            _state.update { it.copy(artistPick = ArtistPickRequest(item)) }
        } else {
            openSeries(item)
        }
    }

    fun openArtistPicker(item: ToonItem, entryEpisodeId: String? = item.entryEpisodeId) {
        if (item.artistChoices.size < 2) return
        _state.update { it.copy(artistPick = ArtistPickRequest(item, entryEpisodeId)) }
    }

    fun dismissArtistPick() {
        _state.update { it.copy(artistPick = null) }
    }

    fun pickArtist(artist: ArtistRef) {
        val request = _state.value.artistPick ?: return
        dismissArtistPick()
        val parent = repository.sourceOrDefault(request.item.sourceId)
            .resolveParent(request.item, artist, request.entryEpisodeId)
        if (parent != null) {
            openSeries(parent)
        }
    }

    private fun updateEpisodeCountsForUpdatedItems(items: List<ToonItem>) {
        val targets = items.filter { it.isNew && it.readProgress != null }
        if (targets.isEmpty()) return

        countUpdateJob?.cancel()
        countUpdateJob = scope.launch(Dispatchers.IO) {
            for (target in targets) {
                try {
                    val exactTotal = repository.fetchTotalEpisodes(target)
                    if (exactTotal <= 0) continue

                    toonTotalCounts[target.workId().storageKey()] = exactTotal
                    val existing = repository.getHistory(target.workId())
                    if (existing != null) {
                        val safeOrder = existing.lastReadOrder.coerceIn(1, exactTotal)
                        val hasNew = safeOrder < exactTotal
                        val updated = existing.copy(
                            totalEpisodes = exactTotal,
                            lastReadOrder = safeOrder,
                            hasNew = hasNew,
                        )
                        repository.saveHistory(updated)
                        val progressText = "${updated.lastReadOrder}/${updated.totalEpisodes}"
                        _state.update { curr ->
                            val updatedItems = curr.items.map { row ->
                                if (row.workId() == target.workId()) row.copy(readProgress = progressText) else row
                            }
                            if (!curr.isSearch) {
                                catalogCache[curr.tab] = updatedItems
                            }
                            curr.copy(
                                items = updatedItems,
                                seriesHistory = if (curr.series?.workId() == target.workId()) updated else curr.seriesHistory,
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun loadPage(page: Int, replace: Boolean = false) {
        loadJob?.cancel()
        val tab = _state.value.tab
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
                val result = repository.loadListing(tab, targetPage)
                catalogCache[tab] = result.items
                catalogPages[tab] = Pair(result.currentPage, result.lastPage)
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
                if (result.items.isNotEmpty() && tab !is BrowseTab.Favorite) {
                    repository.markSeen(result.items)
                }
                updateEpisodeCountsForUpdatedItems(result.items)
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
            catalogCache.remove(_state.value.tab)
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
                val results = repository.search(q, _state.value.activeSourceId)
                _state.update {
                    it.copy(
                        items = results,
                        loading = false,
                        error = if (results.isEmpty()) "'$q' 검색 결과가 없습니다." else null,
                    )
                }
                updateEpisodeCountsForUpdatedItems(results)
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
        val currentScreen = _state.value.screen
        if (currentScreen != Screen.Series && currentScreen != Screen.Reader) {
            previousScreenBeforeSeries = currentScreen
        }
        lastViewedSeries = item
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
                highlightedEpisodeId = item.entryEpisodeId,
                downloadedWrIds = emptySet(),
            )
        }
        loadEpisodes(item, 1)
        scope.launch {
            val favorited = repository.isFavorite(item.workId())
            val history = repository.getHistory(item.workId())
            _state.update { current ->
                if (current.series?.workId() == item.workId()) {
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
            val local = if (page <= 1) {
                repository.loadLocalEpisodes(item)
            } else {
                com.comics8.core.model.EpisodePage(emptyList(), page, 1)
            }
            val hasLocal = local.items.isNotEmpty()
            _state.update {
                it.copy(
                    episodeLoading = !hasLocal,
                    episodeError = null,
                    episodes = if (hasLocal) local.items else it.episodes,
                    episodePage = if (hasLocal) 1 else it.episodePage,
                    episodeLastPage = if (hasLocal) 1 else it.episodeLastPage,
                )
            }
            if (hasLocal) {
                refreshDownloadedWrIds()
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
                refreshDownloadedWrIds()
                if (result.items.isNotEmpty()) {
                    val lastP = result.lastPage.coerceAtLeast(1)
                    val currP = result.currentPage.coerceIn(1, lastP)
                    val pageSize = 100

                    val itemKey = item.workId().storageKey()
                    if (lastP <= 1) {
                        toonTotalCounts[itemKey] = result.items.size
                        toonLastPageCounts[itemKey] = result.items.size
                    } else if (currP == lastP) {
                        toonLastPageCounts[itemKey] = result.items.size
                        val total = (lastP - 1) * pageSize + result.items.size
                        toonTotalCounts[itemKey] = total
                    } else if (!toonLastPageCounts.containsKey(itemKey)) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val lastRes = repository.loadEpisodes(item, lastP)
                                val lastCount = lastRes.items.size
                                toonLastPageCounts[itemKey] = lastCount
                                val exactTotal = (lastP - 1) * pageSize + lastCount
                                toonTotalCounts[itemKey] = exactTotal

                                val existing = repository.getHistory(item.workId())
                                if (existing != null) {
                                    val safeOrder = existing.lastReadOrder.coerceIn(1, exactTotal)
                                    val updated = existing.copy(
                                        totalEpisodes = exactTotal,
                                        lastReadOrder = safeOrder,
                                        hasNew = safeOrder < exactTotal,
                                    )
                                    repository.saveHistory(updated)
                                    val progressText = "${updated.lastReadOrder}/${updated.totalEpisodes}"
                                    _state.update { curr ->
                                        val updatedItems = curr.items.map { row ->
                                            if (row.workId() == item.workId()) row.copy(readProgress = progressText) else row
                                        }
                                        curr.copy(
                                            items = updatedItems,
                                            seriesHistory = if (curr.series?.workId() == item.workId()) updated else curr.seriesHistory,
                                        )
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    val knownLast = toonLastPageCounts[itemKey]
                    val totalCalculated = when {
                        lastP <= 1 -> result.items.size
                        currP == lastP -> (lastP - 1) * pageSize + result.items.size
                        knownLast != null -> (lastP - 1) * pageSize + knownLast
                        else -> (lastP - 1) * pageSize + result.items.size
                    }

                    val existing = repository.getHistory(item.workId())
                    if (existing != null) {
                        val safeOrder = existing.lastReadOrder.coerceIn(1, totalCalculated)
                        val hasNew = safeOrder < totalCalculated
                        if (existing.totalEpisodes != totalCalculated || existing.lastReadOrder != safeOrder) {
                            val updated = existing.copy(
                                totalEpisodes = totalCalculated,
                                lastReadOrder = safeOrder,
                                hasNew = hasNew,
                            )
                            repository.saveHistory(updated)
                            val progressText = "${updated.lastReadOrder}/${updated.totalEpisodes}"
                            _state.update { curr ->
                                val updatedItems = curr.items.map { row ->
                                    if (row.workId() == item.workId()) row.copy(readProgress = progressText) else row
                                }
                                curr.copy(
                                    items = updatedItems,
                                    seriesHistory = if (curr.series?.workId() == item.workId()) updated else curr.seriesHistory,
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                val keepLocal = hasLocal || _state.value.episodes.isNotEmpty()
                _state.update {
                    it.copy(
                        episodeLoading = false,
                        episodeError = if (keepLocal) {
                            null
                        } else {
                            e.localizedMessage ?: "회차 목록을 불러오지 못했습니다."
                        },
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
        if (!_state.value.tab.paginated) return
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
            val favorited = repository.toggleFavorite(item) ?: return@launch
            _state.update { current ->
                val updated = current.items.map { row ->
                    if (row.workId() == item.workId()) row.copy(isFavorite = favorited) else row
                }
                val visible = if (
                    !favorited &&
                    current.tab is BrowseTab.Favorite &&
                    current.screen == Screen.Browse &&
                    !current.isSearch
                ) {
                    updated.filter { it.workId() != item.workId() }
                } else {
                    updated
                }
                if (current.tab !is BrowseTab.Favorite) {
                    catalogCache.remove(BrowseTab.Favorite)
                }
                if (!current.isSearch) {
                    catalogCache[current.tab] = visible
                }
                current.copy(
                    seriesFavorited = if (current.series?.workId() == item.workId()) favorited else current.seriesFavorited,
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
            val readEp = repository.getReadEpisode(series.workId(), hist.lastWrId)
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
        val current = _state.value
        lastViewedSeries = current.series
        lastViewedEpisodePage = current.episodePage
        val tab = current.tab
        val searching = current.isSearch
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
                highlightedEpisodeId = null,
            )
        }
        if (tab is BrowseTab.Favorite && !searching && catalogCache[BrowseTab.Favorite] == null) {
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

    fun openDownloadOptions() {
        val series = _state.value.series ?: return
        downloadCatalogJob?.cancel()
        _state.update {
            it.copy(
                showDownloadOptions = true,
                downloadCatalog = emptyList(),
                downloadCatalogLoading = true,
            )
        }
        downloadCatalogJob = scope.launch {
            try {
                val all = repository.loadAllEpisodes(series)
                _state.update {
                    it.copy(
                        downloadCatalog = all,
                        downloadCatalogLoading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { current ->
                    current.copy(
                        downloadCatalog = current.episodes,
                        downloadCatalogLoading = false,
                    )
                }
            }
        }
    }

    fun closeDownloadOptions() {
        downloadCatalogJob?.cancel()
        _state.update {
            it.copy(
                showDownloadOptions = false,
                downloadCatalog = emptyList(),
                downloadCatalogLoading = false,
            )
        }
    }

    fun startDownloadEpisodes(episodesToDownload: List<EpisodeItem>) {
        val series = _state.value.series ?: return
        closeDownloadOptions()
        repository.downloadManager?.enqueueEpisodes(series, episodesToDownload)
    }

    fun startDownloadEpisode(episode: EpisodeItem) {
        val series = _state.value.series ?: return
        repository.downloadManager?.enqueueEpisodes(series, listOf(episode))
    }

    private fun refreshDownloadedWrIds() {
        val series = _state.value.series ?: return
        scope.launch {
            val ids = repository.downloadManager
                ?.getDownloadedEpisodes(series.workId())
                .orEmpty()
                .map { it.wrId }
                .toSet()
            _state.update { current ->
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
        readerJob?.cancel()
        episodeJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.Downloads,
                downloadLoading = true,
            )
        }
        loadDownloads()
    }

    fun closeDownloads() {
        _state.update { it.copy(screen = Screen.Browse) }
    }

    fun loadDownloads() {
        scope.launch {
            _state.update { it.copy(downloadLoading = it.downloadSummaries.isEmpty()) }
            val items = repository.downloadManager?.getDownloadedToonSummaries().orEmpty()
            _state.update { it.copy(downloadSummaries = items, downloadLoading = false) }
        }
    }

    fun deleteToonDownloads(workId: WorkId) {
        scope.launch {
            repository.downloadManager?.deleteToonDownloads(workId)
            loadDownloads()
        }
    }

    fun deleteHistory(workId: WorkId) {
        scope.launch {
            repository.deleteHistory(workId)
            _state.update { current ->
                val updatedItems = current.items.map {
                    if (it.workId() == workId) it.copy(readProgress = null) else it
                }
                current.copy(
                    historyItems = current.historyItems.filter { it.workId() != workId },
                    items = updatedItems,
                    seriesHistory = if (current.series?.workId() == workId) null else current.seriesHistory,
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

    fun toggleServerProxy(enabled: Boolean) {
        repository.syncManager?.setUseServerProxy(enabled)
    }

    fun openSeriesFromHistory(item: ReadHistoryRecord) {
        openSeries(item.toToonItem())
    }

    fun reopenHistoryEpisode(item: ReadHistoryRecord, startPage: Int = 0) {
        val toon = item.toToonItem()
        if (_state.value.screen == Screen.History) {
            previousScreenBeforeSeries = Screen.History
        }
        episodeJob?.cancel()
        readerJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.Series,
                series = toon,
                episodes = emptyList(),
                episodeLoading = true,
                highlightedEpisodeId = item.lastWrId,
                readerLoading = true,
                readerError = null,
                readerImages = emptyList(),
            )
        }
        scope.launch {
            try {
                val pageToFetch = if (startPage == 0) {
                    val readEp = repository.getReadEpisode(item.workId(), item.lastWrId)
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
                        episodeLoading = false,
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
                _state.update { it.copy(episodeLoading = false) }
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
        val toon = item.toToonItem()
        if (_state.value.screen == Screen.History) {
            previousScreenBeforeSeries = Screen.History
        }
        episodeJob?.cancel()
        readerJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.Series,
                series = toon,
                episodes = emptyList(),
                episodeLoading = true,
                highlightedEpisodeId = item.lastWrId,
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
                        episodeLoading = false,
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
                _state.update { it.copy(episodeLoading = false) }
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
        if (series != null) {
            lastViewedSeries = series
            lastViewedEpisodePage = current.episodePage
        }
        lastViewedEpisode = episode
        val nowMs = System.currentTimeMillis()
        val episodes = current.episodes
        val idx = episodes.indexOfFirst { it.wrId == episode.wrId }
        val lastPage = current.episodeLastPage.coerceAtLeast(1)
        val currentPage = current.episodePage.coerceIn(1, lastPage)
        val pageSize = 100

        val seriesKey = series?.workId()?.storageKey().orEmpty()
        val knownLastPageCount = toonLastPageCounts[seriesKey]
        val knownTotalCount = toonTotalCounts[seriesKey]

        val totalEpCount = when {
            knownTotalCount != null && knownTotalCount > 0 -> knownTotalCount
            lastPage <= 1 -> episodes.size.coerceAtLeast(1)
            currentPage == lastPage && episodes.isNotEmpty() -> (lastPage - 1) * pageSize + episodes.size
            knownLastPageCount != null -> (lastPage - 1) * pageSize + knownLastPageCount
            else -> (lastPage - 1) * pageSize + episodes.size
        }

        val calculatedOrder = if (idx >= 0 && episodes.isNotEmpty()) {
            val olderCount = if (currentPage == lastPage) {
                0
            } else {
                val lastPageItems = knownLastPageCount ?: pageSize
                val middlePagesCount = (lastPage - 1 - currentPage).coerceAtLeast(0) * pageSize
                lastPageItems + middlePagesCount
            }
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
                val savedSetting = repository.getReaderSetting(series.workId())
                if (savedSetting != null) {
                    _state.update { it.copy(viewMode = savedSetting.first, readDirection = savedSetting.second) }
                }
                repository.markEpisodeRead(series.workId(), episode.wrId, episode.lastReadPage)
                val existingHistory = repository.getHistory(series.workId())

                val finalOrder = calculatedOrder
                    ?: existingHistory?.lastReadOrder
                    ?: 1

                val candidateTotal = if (totalEpCount > 1) totalEpCount else (existingHistory?.totalEpisodes ?: 1)
                val finalTotal = maxOf(candidateTotal, finalOrder, existingHistory?.totalEpisodes ?: 1)

                val progressText = "$finalOrder/$finalTotal"
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
                    hasNew = false,
                )
                repository.saveHistory(savedHistory)
                _state.update { curr ->
                    val updatedItems = curr.items.map { item ->
                        if (item.workId() == series.workId()) item.copy(readProgress = progressText) else item
                    }
                    curr.copy(
                        items = updatedItems,
                        seriesHistory = if (curr.series?.workId() == series.workId()) savedHistory else curr.seriesHistory,
                    )
                }
            }
        }
    }

    private fun loadReaderImages(episode: EpisodeItem) {
        readerJob?.cancel()
        readerJob = scope.launch {
            try {
                val seriesWorkId = _state.value.series?.workId()
                val images = repository.loadImages(episode, seriesWorkId)
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

    fun savePage(page: Int, seenThroughPage: Int = page) {
        val current = _state.value
        val series = current.series ?: return
        val episode = current.currentEpisode ?: return
        val totalImages = current.readerImages.size
        val targetPage = com.comics8.core.model.ReaderProgress.persistPage(
            page = page,
            totalImages = totalImages,
            seenThroughPage = seenThroughPage,
        )

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
            repository.saveEpisodePage(series.workId(), episode.wrId, targetPage)
        }
    }

    fun setViewMode(mode: ViewMode) {
        _state.update { it.copy(viewMode = mode) }
        val series = _state.value.series ?: return
        val direction = _state.value.readDirection
        scope.launch {
            repository.saveReaderSetting(series.workId(), mode, direction)
        }
    }

    fun setReadDirection(direction: ReadDirection) {
        _state.update { it.copy(readDirection = direction) }
        val series = _state.value.series ?: return
        val mode = _state.value.viewMode
        scope.launch {
            repository.saveReaderSetting(series.workId(), mode, direction)
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
        val current = _state.value
        lastViewedEpisode = current.currentEpisode
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

    private fun ReadHistoryRecord.toToonItem(): ToonItem = ToonItem(
        id = toonId,
        title = toonTitle,
        thumbUrl = toonThumbUrl,
        href = toonHref,
        genre = "",
        updatedAt = null,
        sourceId = sourceId,
    )
}
