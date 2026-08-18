package com.comics8.monitor.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comics8.core.model.AppUpdateState
import com.comics8.core.model.ArtistRef
import com.comics8.core.model.BrowseTab
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ToonItem
import com.comics8.core.model.ViewMode
import com.comics8.core.source.SourcePrefs
import com.comics8.core.source.WorkId
import com.comics8.core.sync.AppUpdateChecker
import com.comics8.core.sync.SyncConstants
import com.comics8.monitor.BuildConfig
import com.comics8.monitor.ComicsApp
import com.comics8.monitor.data.AndroidUpdateManager
import com.comics8.monitor.data.BackupStats
import com.comics8.monitor.data.SyncState
import com.comics8.monitor.data.local.ReadHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArtistPickRequest(
    val item: ToonItem,
    val entryEpisodeId: String? = item.entryEpisodeId,
)

data class ListingUiState(
    val tab: BrowseTab = BrowseTab.Remote(
        WorkId.DEFAULT_SOURCE,
        com.comics8.core.source.SourceCatalog("LATEST", "최신", paginated = true),
    ),
    val browseTabs: List<BrowseTab> = emptyList(),
    val activeSourceId: String = WorkId.DEFAULT_SOURCE,
    val artistPick: ArtistPickRequest? = null,
    val highlightedEpisodeId: String? = null,
    val page: Int = 1,
    val lastPage: Int = 1,
    val items: List<ToonItem> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val screen: Screen = Screen.Browse,
    val series: ToonItem? = null,
    val episodes: List<EpisodeItem> = emptyList(),
    val episodePage: Int = 1,
    val episodeLastPage: Int = 1,
    val episodeLoading: Boolean = false,
    val episodeError: String? = null,
    val showPageJump: Boolean = false,
    val searchInput: String = "",
    val searchQuery: String? = null,
    val searchExpanded: Boolean = false,
    val searchSuggestions: List<com.comics8.core.source.SearchSuggestion> = emptyList(),
    val seriesFavorited: Boolean = false,
    val currentEpisode: EpisodeItem? = null,
    val readerImages: List<String> = emptyList(),
    val imageAspectRatios: Map<Int, Float> = emptyMap(),
    val readerLoading: Boolean = false,
    val readerError: String? = null,
    val viewMode: ViewMode = ViewMode.SCROLL,
    val readDirection: ReadDirection = ReadDirection.RIGHT_TO_LEFT,
    val scrollToTopTrigger: Int = 0,
    val seriesHistory: ReadHistory? = null,
    val historyItems: List<ReadHistory> = emptyList(),
    val historyLoading: Boolean = false,
    val showSyncDialog: Boolean = false,
    val syncState: SyncState = SyncState(),
    val showUpdateDialog: Boolean = false,
    val updateState: AppUpdateState = AppUpdateState(),
    val downloadSummaries: List<com.comics8.core.model.DownloadedToonSummary> = emptyList(),
    val downloadLoading: Boolean = false,
    val showDownloadOptions: Boolean = false,
    val downloadCatalog: List<EpisodeItem> = emptyList(),
    val downloadCatalogLoading: Boolean = false,
    val downloadedWrIds: Set<String> = emptySet(),
    val downloadProgress: com.comics8.monitor.data.DownloadProgressState = com.comics8.monitor.data.DownloadProgressState(),
) {
    val isSearch: Boolean get() = searchQuery != null

    val currentEpisodeIndex: Int
        get() = currentEpisode?.let { ep -> episodes.indexOfFirst { it.wrId == ep.wrId } } ?: -1

    val hasNextEpisode: Boolean
        get() = (currentEpisodeIndex in 1 until episodes.size) || (currentEpisodeIndex == 0 && episodePage > 1)

    val hasPrevEpisode: Boolean
        get() = (currentEpisodeIndex in 0 until (episodes.size - 1)) || (currentEpisodeIndex == episodes.size - 1 && episodePage < episodeLastPage)
}

sealed interface Screen {
    data object Browse : Screen
    data object Series : Screen
    data object Reader : Screen
    data object History : Screen
    data object Downloads : Screen
}

class ListingViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as ComicsApp).repository
    private val prefs = application.getSharedPreferences("comics_reader_prefs", Context.MODE_PRIVATE)

    var singleViewPreference: ViewMode
        get() = try {
            val name = prefs.getString("single_view_mode", ViewMode.SCROLL.name)
            if (name == ViewMode.PAGE.name) ViewMode.PAGE else ViewMode.SCROLL
        } catch (_: Exception) {
            ViewMode.SCROLL
        }
        private set(value) {
            prefs.edit().putString("single_view_mode", value.name).apply()
        }

    var globalReadDirection: ReadDirection
        get() = try {
            val name = prefs.getString("read_direction", ReadDirection.RIGHT_TO_LEFT.name)
            if (name == ReadDirection.LEFT_TO_RIGHT.name) ReadDirection.LEFT_TO_RIGHT else ReadDirection.RIGHT_TO_LEFT
        } catch (_: Exception) {
            ReadDirection.RIGHT_TO_LEFT
        }
        private set(value) {
            prefs.edit().putString("read_direction", value.name).apply()
        }

    private var currentScreenWidthDp: Int = 0

    private val _state = MutableStateFlow(
        ListingUiState(
            viewMode = ViewMode.SCROLL,
            readDirection = ReadDirection.RIGHT_TO_LEFT,
        ),
    )
    val state: StateFlow<ListingUiState> = _state.asStateFlow()

    private var listJob: Job? = null
    private var episodeJob: Job? = null
    private var readerJob: Job? = null
    private var countUpdateJob: Job? = null
    private var downloadCatalogJob: Job? = null
    private var suggestJob: Job? = null
    private var savePageJob: Job? = null

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
            dismissUpdateDialog()
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
        val storedSource = prefs.getString(SourcePrefs.ACTIVE_SOURCE_KEY, WorkId.DEFAULT_SOURCE)
        val activeSource = repository.sourceOrDefault(storedSource.orEmpty()).id
        repository.applyPreferences { id ->
            prefs.getString(SourcePrefs.languageKey(id), null)
        }
        val tabs = BrowseTab.forSource(repository.source(activeSource))
        _state.update {
            it.copy(
                viewMode = singleViewPreference,
                readDirection = globalReadDirection,
                activeSourceId = activeSource,
                browseTabs = tabs,
                tab = tabs.first(),
            )
        }
        repository.syncManager?.let { sm ->
            viewModelScope.launch {
                sm.syncState.collect { s ->
                    _state.update { it.copy(syncState = s) }
                }
            }
            viewModelScope.launch {
                val res = sm.syncPull()
                if (res.success && (res.favoritesCount > 0 || res.historyCount > 0)) {
                    loadPage(page = _state.value.page, replace = true)
                }
            }
        }
        repository.downloadManager?.let { dm ->
            viewModelScope.launch {
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
        refresh(force = true)
        checkForUpdate()
    }

    fun checkForUpdate() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentVersionName = BuildConfig.VERSION_NAME
            val currentVersionCode = BuildConfig.VERSION_CODE
            val serverUrl = _state.value.syncState.serverUrl.ifBlank { SyncConstants.DEFAULT_SERVER_URL }
            val updateState = AppUpdateChecker.checkAndroidUpdate(
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

    fun dismissUpdateDialog() {
        _state.update { it.copy(showUpdateDialog = false) }
    }

    fun startUpdate() {
        val updateUrl = _state.value.updateState.downloadUrl
        if (updateUrl.isBlank()) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    updateState = it.updateState.copy(
                        isDownloading = true,
                        downloadProgress = 0f,
                        error = null,
                    )
                )
            }
            val result = AndroidUpdateManager.downloadAndInstall(
                context = getApplication(),
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

    fun handleNotificationLaunch(targetTab: String? = null) {
        readerJob?.cancel()
        episodeJob?.cancel()
        val dest = BrowseTab.resolveLaunchTarget(targetTab, _state.value.browseTabs)
        _state.update {
            it.copy(
                screen = Screen.Browse,
                tab = dest,
                series = null,
                episodes = emptyList(),
                currentEpisode = null,
                readerImages = emptyList(),
                page = 1,
                lastPage = 1,
                items = emptyList(),
                searchQuery = null,
                searchExpanded = false,
                searchInput = "",
                error = null,
                scrollToTopTrigger = it.scrollToTopTrigger + 1,
            )
        }
        loadPage(1, replace = true, asRefresh = true)
    }

    fun selectTab(tab: BrowseTab) {
        if (
            tab == _state.value.tab &&
            _state.value.screen == Screen.Browse &&
            !_state.value.isSearch
        ) return
        readerJob?.cancel()
        episodeJob?.cancel()
        val cached = catalogCache[tab]
        val (cachedPage, cachedLastPage) = catalogPages[tab] ?: Pair(1, 1)
        _state.update {
            it.copy(
                tab = tab,
                page = cachedPage,
                lastPage = cachedLastPage,
                items = cached ?: emptyList(),
                loading = cached == null,
                screen = Screen.Browse,
                series = null,
                episodes = emptyList(),
                error = null,
                searchQuery = null,
                searchExpanded = false,
                currentEpisode = null,
                readerImages = emptyList(),
                readerError = null,
                scrollToTopTrigger = it.scrollToTopTrigger + 1,
            )
        }
        if (cached == null) {
            loadPage(cachedPage, replace = true)
        }
    }

    fun setActiveSource(sourceId: String) {
        val resolved = repository.sourceOrDefault(sourceId).id
        val editor = prefs.edit().putString(SourcePrefs.ACTIVE_SOURCE_KEY, resolved)
        if (resolved != WorkId.DEFAULT_SOURCE) {
            editor.putBoolean(SourcePrefs.enabledKey(resolved), true)
        }
        editor.apply()
        val tabs = BrowseTab.forSource(repository.source(resolved))
        val nextTab = BrowseTab.afterSourceChange(_state.value.tab, tabs)
        _state.update {
            it.copy(
                activeSourceId = resolved,
                browseTabs = tabs,
                searchSuggestions = emptyList(),
            )
        }
        selectTab(nextTab)
        if (_state.value.screen == Screen.History) {
            loadHistory()
        }
        if (_state.value.screen == Screen.Downloads) {
            loadDownloads()
        }
    }

    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        val resolved = repository.sourceOrDefault(sourceId).id
        if (resolved == WorkId.DEFAULT_SOURCE) return
        prefs.edit().putBoolean(SourcePrefs.enabledKey(resolved), enabled).apply()
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

    fun toggleSearchBar() {
        val current = _state.value
        if (current.searchExpanded) {
            if (current.isSearch) {
                selectTab(current.tab)
            } else {
                _state.update { it.copy(searchExpanded = false, searchInput = "", searchSuggestions = emptyList()) }
            }
        } else {
            _state.update { it.copy(searchExpanded = true) }
        }
    }

    fun updateSearchInput(value: String) {
        _state.update { it.copy(searchInput = value) }
        requestSuggestions(value)
    }

    fun applySearchSuggestion(suggestion: com.comics8.core.source.SearchSuggestion) {
        updateSearchInput(suggestion.applyTo(_state.value.searchInput))
    }

    private fun requestSuggestions(value: String) {
        suggestJob?.cancel()
        if (value.isBlank()) {
            _state.update { it.copy(searchSuggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            kotlinx.coroutines.delay(200)
            try {
                val items = repository.suggest(value, _state.value.activeSourceId)
                _state.update { current ->
                    if (current.searchInput == value) current.copy(searchSuggestions = items) else current
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(searchSuggestions = emptyList()) }
            }
        }
    }

    fun submitSearch() {
        val query = _state.value.searchInput.trim()
        if (query.isEmpty()) return
        readerJob?.cancel()
        episodeJob?.cancel()
        _state.update {
            it.copy(
                searchQuery = query,
                searchInput = query,
                searchSuggestions = emptyList(),
                screen = Screen.Browse,
                series = null,
                episodes = emptyList(),
                items = emptyList(),
                error = null,
                loading = true,
                currentEpisode = null,
                readerImages = emptyList(),
                readerError = null,
                scrollToTopTrigger = it.scrollToTopTrigger + 1,
            )
        }
        runSearch(query, asRefresh = false)
    }

    fun refresh(force: Boolean = false) {
        checkForUpdate()
        if (force) {
            catalogCache.clear()
            catalogPages.clear()
        }
        val current = _state.value
        if (current.screen == Screen.Reader) {
            val episode = current.currentEpisode ?: return
            loadReaderImages(episode, asRefresh = true)
            return
        }
        if (current.screen == Screen.Series) {
            val series = current.series ?: return
            loadEpisodes(series, current.episodePage, asRefresh = true)
            return
        }
        if (current.isSearch) {
            val query = current.searchQuery ?: return
            runSearch(query, asRefresh = current.items.isNotEmpty())
            return
        }
        loadPage(if (force) 1 else current.page, replace = true, asRefresh = !force && current.items.isNotEmpty())
    }

    fun goToPage(page: Int) {
        val current = _state.value
        val target = page.coerceIn(1, current.lastPage.coerceAtLeast(1))
        if (!current.tab.paginated) return
        _state.update {
            it.copy(
                items = emptyList(),
                loading = true,
                error = null,
                page = target,
                showPageJump = false,
                scrollToTopTrigger = it.scrollToTopTrigger + 1,
            )
        }
        loadPage(target, replace = true)
    }

    fun nextPage() {
        val current = _state.value
        if (current.page < current.lastPage) goToPage(current.page + 1)
    }

    fun prevPage() {
        val current = _state.value
        if (current.page > 1) goToPage(current.page - 1)
    }

    fun togglePageJump(show: Boolean) {
        _state.update { it.copy(showPageJump = show) }
    }

    fun updateScreenWidthDp(widthDp: Int) {
        currentScreenWidthDp = widthDp
        val targetMode = if (widthDp >= 600) ViewMode.DUAL else singleViewPreference
        _state.update {
            it.copy(
                viewMode = targetMode,
                readDirection = globalReadDirection,
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
        val targetMode = if (currentScreenWidthDp >= 600) ViewMode.DUAL else singleViewPreference
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
                viewMode = targetMode,
                readDirection = globalReadDirection,
                downloadedWrIds = emptySet(),
            )
        }
        loadEpisodes(item, 1)
        viewModelScope.launch {
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

    fun toggleFavorite() {
        val series = _state.value.series ?: return
        toggleFavorite(series)
    }

    fun toggleFavorite(item: ToonItem) {
        viewModelScope.launch {
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
                    catalogCache.remove(BrowseTab.Favorite(item.sourceId.ifBlank { WorkId.DEFAULT_SOURCE }))
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
        viewModelScope.launch {
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
                viewModelScope.launch {
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
            viewModelScope.launch {
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
        if (tab is BrowseTab.Favorite && !searching) {
            val cached = catalogCache[tab]
            if (cached == null) {
                loadPage(1, replace = true)
            }
        }
    }

    fun openHistory() {
        readerJob?.cancel()
        episodeJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.History,
                historyLoading = true,
            )
        }
        loadHistory()
    }

    fun closeHistory() {
        _state.update { it.copy(screen = Screen.Browse) }
    }

    fun loadHistory() {
        viewModelScope.launch {
            _state.update { it.copy(historyLoading = it.historyItems.isEmpty()) }
            val items = repository.getHistory(_state.value.activeSourceId)
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
        downloadCatalogJob = viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            _state.update { it.copy(downloadLoading = it.downloadSummaries.isEmpty()) }
            val items = repository.downloadManager
                ?.getDownloadedToonSummaries(_state.value.activeSourceId)
                .orEmpty()
            _state.update { it.copy(downloadSummaries = items, downloadLoading = false) }
        }
    }

    fun deleteToonDownloads(workId: WorkId) {
        viewModelScope.launch {
            repository.downloadManager?.deleteToonDownloads(workId)
            loadDownloads()
        }
    }

    fun deleteHistory(workId: WorkId) {
        viewModelScope.launch {
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
        viewModelScope.launch {
            repository.clearHistory(_state.value.activeSourceId)
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
        viewModelScope.launch {
            val res = repository.syncManager?.syncFull()
            if (res?.success == true) {
                loadPage(page = _state.value.page, replace = true)
                val updatedHistory = repository.getHistory(_state.value.activeSourceId)
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

    fun reopenHistoryEpisode(item: ReadHistory, startPage: Int = 0) {
        val toon = item.toToonItem()
        if (_state.value.screen == Screen.History) {
            previousScreenBeforeSeries = Screen.History
        }
        episodeJob?.cancel()
        readerJob?.cancel()
        val targetMode = if (currentScreenWidthDp >= 600) ViewMode.DUAL else singleViewPreference
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
                viewMode = targetMode,
                readDirection = globalReadDirection,
            )
        }
        viewModelScope.launch {
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

    fun openSeriesFromHistory(item: ReadHistory) {
        openSeries(item.toToonItem())
    }

    fun continueHistoryEpisode(item: ReadHistory) {
        val toon = item.toToonItem()
        if (_state.value.screen == Screen.History) {
            previousScreenBeforeSeries = Screen.History
        }
        episodeJob?.cancel()
        readerJob?.cancel()
        val targetMode = if (currentScreenWidthDp >= 600) ViewMode.DUAL else singleViewPreference
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
                viewMode = targetMode,
                readDirection = globalReadDirection,
            )
        }
        viewModelScope.launch {
            try {
                val nextOrder = item.lastReadOrder + 1
                val predictedPage = if (item.totalEpisodes > nextOrder && item.totalEpisodes > 100) {
                    ((item.totalEpisodes - nextOrder) / 100 + 1).coerceAtLeast(1)
                } else 1

                var epPage = repository.loadEpisodes(toon, predictedPage)
                var lastIdx = epPage.items.indexOfFirst { it.wrId == item.lastWrId }
                var actualPage = predictedPage

                if (lastIdx < 0 && predictedPage != 1) {
                    epPage = repository.loadEpisodes(toon, 1)
                    lastIdx = epPage.items.indexOfFirst { it.wrId == item.lastWrId }
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

                val nextEp = when {
                    lastIdx > 0 -> epPage.items[lastIdx - 1]
                    lastIdx == 0 && actualPage > 1 -> {
                        try {
                            val prevEpPage = repository.loadEpisodes(toon, actualPage - 1)
                            _state.update {
                                it.copy(
                                    episodes = prevEpPage.items,
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
                readerLoading = true,
                readerError = null,
            )
        }
        loadReaderImages(episode)
        if (series != null) {
            viewModelScope.launch {
                repository.markEpisodeRead(series.workId(), episode.wrId, episode.lastReadPage)
                val existingHistory = repository.getHistory(series.workId())

                val finalOrder = calculatedOrder
                    ?: existingHistory?.lastReadOrder
                    ?: 1

                val candidateTotal = if (totalEpCount > 1) totalEpCount else (existingHistory?.totalEpisodes ?: 1)
                val finalTotal = maxOf(candidateTotal, finalOrder, existingHistory?.totalEpisodes ?: 1)

                val progressText = "$finalOrder/$finalTotal"
                val savedHistory = ReadHistory(
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
        if (episode.lastReadPage == targetPage) return

        _state.update { state ->
            state.copy(currentEpisode = state.currentEpisode?.copy(lastReadPage = targetPage))
        }
        savePageJob?.cancel()
        savePageJob = viewModelScope.launch {
            delay(400)
            repository.saveEpisodePage(series.workId(), episode.wrId, targetPage)
        }
    }

    fun closeReader() {
        readerJob?.cancel()
        val current = _state.value
        lastViewedEpisode = current.currentEpisode
        flushPendingPageSave(current)
        _state.update {
            it.copy(
                screen = Screen.Series,
                currentEpisode = null,
                readerImages = emptyList(),
                readerLoading = false,
                readerError = null,
            )
        }
        repository.syncManager?.triggerDebouncedPush()
    }

    fun onAppBackgrounded() {
        flushPendingPageSave(_state.value)
        repository.syncManager?.triggerDebouncedPush()
    }

    private fun flushPendingPageSave(current: ListingUiState) {
        val pending = savePageJob
        savePageJob?.cancel()
        savePageJob = null
        if (pending == null) return
        val series = current.series ?: return
        val episode = current.currentEpisode ?: return
        viewModelScope.launch {
            repository.saveEpisodePage(series.workId(), episode.wrId, episode.lastReadPage)
        }
    }

    fun openNextEpisode() {
        val current = _state.value
        val series = current.series
        val currentEp = current.currentEpisode
        if (series != null && currentEp != null) {
            viewModelScope.launch {
                repository.saveEpisodePage(series.workId(), currentEp.wrId, 0)
            }
        }
        val idx = current.currentEpisodeIndex
        if (idx in 1 until current.episodes.size) {
            openEpisode(current.episodes[idx - 1])
        } else if (idx == 0 && current.episodePage > 1 && series != null) {
            val prevPageNum = current.episodePage - 1
            viewModelScope.launch {
                try {
                    val result = repository.loadEpisodes(series, prevPageNum)
                    _state.update {
                        it.copy(
                            episodes = result.items,
                            episodePage = result.currentPage,
                            episodeLastPage = result.lastPage,
                        )
                    }
                    val target = result.items.lastOrNull()
                    if (target != null) {
                        openEpisode(target)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun openPrevEpisode() {
        val current = _state.value
        val series = current.series
        val idx = current.currentEpisodeIndex
        if (idx in 0 until (current.episodes.size - 1)) {
            openEpisode(current.episodes[idx + 1])
        } else if (idx == current.episodes.size - 1 && current.episodePage < current.episodeLastPage && series != null) {
            val nextPageNum = current.episodePage + 1
            viewModelScope.launch {
                try {
                    val result = repository.loadEpisodes(series, nextPageNum)
                    _state.update {
                        it.copy(
                            episodes = result.items,
                            episodePage = result.currentPage,
                            episodeLastPage = result.lastPage,
                        )
                    }
                    val target = result.items.firstOrNull()
                    if (target != null) {
                        openEpisode(target)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun setViewMode(mode: ViewMode) {
        if (mode == ViewMode.SCROLL || mode == ViewMode.PAGE) {
            singleViewPreference = mode
        }
        _state.update { it.copy(viewMode = mode) }
    }

    fun setReadDirection(direction: ReadDirection) {
        globalReadDirection = direction
        _state.update { it.copy(readDirection = direction) }
    }

    fun goToEpisodePage(page: Int) {
        val current = _state.value
        val series = current.series ?: return
        val target = page.coerceIn(1, current.episodeLastPage.coerceAtLeast(1))
        loadEpisodes(series, target)
    }

    private fun updateEpisodeCountsForUpdatedItems(items: List<ToonItem>) {
        val targets = items.filter { it.isNew && it.readProgress != null }
        if (targets.isEmpty()) return

        countUpdateJob?.cancel()
        countUpdateJob = viewModelScope.launch(Dispatchers.IO) {
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

    private fun runSearch(query: String, asRefresh: Boolean) {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = it.items.isEmpty() || !asRefresh,
                    refreshing = asRefresh,
                    error = null,
                    showPageJump = false,
                )
            }
            try {
                val items = repository.search(query, _state.value.activeSourceId)
                repository.markSeen(items)
                _state.update {
                    it.copy(
                        items = items,
                        page = 1,
                        lastPage = 1,
                        loading = false,
                        refreshing = false,
                        error = null,
                    )
                }
                updateEpisodeCountsForUpdatedItems(items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = humanError("검색에 실패했습니다.", e),
                    )
                }
            }
        }
    }

    private fun loadPage(page: Int, replace: Boolean, asRefresh: Boolean = false) {
        listJob?.cancel()
        listJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    loading = it.items.isEmpty() || replace && !asRefresh,
                    refreshing = asRefresh,
                    error = null,
                    page = page,
                    showPageJump = false,
                )
            }
            try {
                val currentTab = _state.value.tab
                val result = repository.loadListing(currentTab, page)
                repository.markSeen(result.items)
                catalogCache[currentTab] = result.items
                catalogPages[currentTab] = Pair(result.currentPage, result.lastPage)
                _state.update {
                    it.copy(
                        items = result.items,
                        page = result.currentPage,
                        lastPage = result.lastPage,
                        loading = false,
                        refreshing = false,
                        error = null,
                    )
                }
                updateEpisodeCountsForUpdatedItems(result.items)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = humanError("목록을 불러오지 못했습니다.", e),
                    )
                }
            }
        }
    }

    private fun loadEpisodes(item: ToonItem, page: Int, asRefresh: Boolean = false) {
        episodeJob?.cancel()
        episodeJob = viewModelScope.launch {
            val local = if (page <= 1) {
                repository.loadLocalEpisodes(item)
            } else {
                com.comics8.core.model.EpisodePage(emptyList(), page, 1)
            }
            val hasLocal = local.items.isNotEmpty()
            _state.update {
                it.copy(
                    episodeLoading = !hasLocal && (it.episodes.isEmpty() || !asRefresh),
                    episodeError = null,
                    episodePage = if (hasLocal) 1 else page,
                    episodeLastPage = if (hasLocal) 1 else it.episodeLastPage,
                    episodes = if (hasLocal) local.items else it.episodes,
                    refreshing = asRefresh,
                    showPageJump = false,
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
                        refreshing = false,
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
                        viewModelScope.launch(Dispatchers.IO) {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val keepLocal = hasLocal || _state.value.episodes.isNotEmpty()
                _state.update {
                    it.copy(
                        episodeLoading = false,
                        refreshing = false,
                        episodeError = if (keepLocal) null else humanError("회차를 불러오지 못했습니다.", e),
                    )
                }
            }
        }
    }

    private fun loadReaderImages(episode: EpisodeItem, asRefresh: Boolean = false) {
        readerJob?.cancel()
        readerJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    readerLoading = it.readerImages.isEmpty() || !asRefresh,
                    readerError = null,
                    refreshing = asRefresh,
                )
            }
            try {
                val seriesWorkId = _state.value.series?.workId()
                val images = repository.loadImages(episode, seriesWorkId)
                _state.update {
                    it.copy(
                        readerImages = images,
                        imageAspectRatios = emptyMap(),
                        readerLoading = false,
                        refreshing = false,
                        readerError = null,
                    )
                }
                prefetchImageDimensions(images)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
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
        if (height <= 0 || width <= 0) return
        val ratio = width.toFloat() / height.toFloat()
        _state.update { current ->
            if (current.imageAspectRatios[index] == ratio) current
            else current.copy(imageAspectRatios = current.imageAspectRatios + (index to ratio))
        }
    }

    private fun prefetchImageDimensions(images: List<String>) {
        val context = getApplication<Application>()
        val imageLoader = coil.Coil.imageLoader(context)
        viewModelScope.launch {
            images.forEachIndexed { index, url ->
                val request = coil.request.ImageRequest.Builder(context)
                    .data(url)
                    .size(coil.size.Size.ORIGINAL)
                    .target(
                        onSuccess = { drawable ->
                            val w = drawable.intrinsicWidth
                            val h = drawable.intrinsicHeight
                            if (w > 0 && h > 0) {
                                recordImageAspectRatio(index, w, h)
                            }
                        }
                    )
                    .build()
                imageLoader.enqueue(request)
            }
        }
    }

    private fun humanError(prefix: String, e: Exception): String {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return "$prefix\n$detail"
    }

    private fun ReadHistory.toToonItem(): ToonItem = ToonItem(
        id = toonId,
        title = toonTitle,
        thumbUrl = toonThumbUrl,
        href = toonHref,
        genre = "",
        updatedAt = null,
        sourceId = sourceId,
    )
}
