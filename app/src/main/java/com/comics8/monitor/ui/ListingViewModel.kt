package com.comics8.monitor.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.comics8.core.model.AppUpdateState
import com.comics8.core.sync.AppUpdateChecker
import com.comics8.core.sync.SyncConstants
import com.comics8.monitor.BuildConfig
import com.comics8.monitor.ComicsApp
import com.comics8.monitor.data.AndroidUpdateManager
import com.comics8.monitor.data.BackupStats
import com.comics8.monitor.data.Catalog
import com.comics8.monitor.data.EpisodeItem
import com.comics8.monitor.data.ReadDirection
import com.comics8.monitor.data.SyncState
import com.comics8.monitor.data.ToonItem
import com.comics8.monitor.data.ViewMode
import com.comics8.monitor.data.local.ReadHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListingUiState(
    val catalog: Catalog = Catalog.LATEST,
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

sealed interface AndroidNavEntry {
    data class Browse(
        val catalog: Catalog,
        val page: Int = 1,
        val searchQuery: String? = null,
    ) : AndroidNavEntry

    data class Series(
        val series: ToonItem,
        val episodePage: Int = 1,
    ) : AndroidNavEntry

    data class Reader(
        val series: ToonItem,
        val episode: EpisodeItem,
        val episodePage: Int = 1,
    ) : AndroidNavEntry

    data object History : AndroidNavEntry
    data object Downloads : AndroidNavEntry
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

    private val catalogCache = mutableMapOf<Catalog, List<ToonItem>>()
    private val catalogPages = mutableMapOf<Catalog, Pair<Int, Int>>()

    private val toonTotalCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val toonLastPageCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private val backStack = mutableListOf<AndroidNavEntry>()
    private val forwardStack = mutableListOf<AndroidNavEntry>()
    private var isNavigatingHistory = false

    private fun currentNavEntry(): AndroidNavEntry {
        val s = _state.value
        return when (s.screen) {
            Screen.Reader -> {
                val series = s.series
                val ep = s.currentEpisode
                if (series != null && ep != null) {
                    AndroidNavEntry.Reader(series, ep, s.episodePage)
                } else {
                    AndroidNavEntry.Browse(s.catalog, s.page, s.searchQuery)
                }
            }
            Screen.Series -> {
                val series = s.series
                if (series != null) {
                    AndroidNavEntry.Series(series, s.episodePage)
                } else {
                    AndroidNavEntry.Browse(s.catalog, s.page, s.searchQuery)
                }
            }
            Screen.History -> AndroidNavEntry.History
            Screen.Downloads -> AndroidNavEntry.Downloads
            Screen.Browse -> AndroidNavEntry.Browse(s.catalog, s.page, s.searchQuery)
        }
    }

    private fun pushHistory(newEntry: AndroidNavEntry) {
        if (isNavigatingHistory) return
        val current = currentNavEntry()
        if (current != newEntry) {
            backStack.add(current)
            forwardStack.clear()
            if (backStack.size > 50) backStack.removeAt(0)
        }
    }

    private fun restoreNavEntry(entry: AndroidNavEntry) {
        when (entry) {
            is AndroidNavEntry.Browse -> {
                if (entry.searchQuery != null) {
                    _state.update {
                        it.copy(
                            searchQuery = entry.searchQuery,
                            searchInput = entry.searchQuery,
                            screen = Screen.Browse,
                            series = null,
                            episodes = emptyList(),
                            currentEpisode = null,
                        )
                    }
                    runSearch(entry.searchQuery, asRefresh = false)
                } else {
                    if (_state.value.catalog != entry.catalog) {
                        selectCatalog(entry.catalog)
                    } else if (_state.value.page != entry.page) {
                        loadPage(entry.page, replace = true)
                    } else {
                        _state.update {
                            it.copy(
                                screen = Screen.Browse,
                                searchQuery = null,
                                searchInput = "",
                                series = null,
                                currentEpisode = null,
                            )
                        }
                    }
                }
            }
            is AndroidNavEntry.Series -> {
                _state.update {
                    it.copy(
                        screen = Screen.Series,
                        series = entry.series,
                        currentEpisode = null,
                        readerImages = emptyList(),
                    )
                }
                loadEpisodes(entry.series, entry.episodePage)
            }
            is AndroidNavEntry.Reader -> {
                _state.update {
                    it.copy(
                        series = entry.series,
                        episodePage = entry.episodePage,
                    )
                }
                openEpisode(entry.episode)
            }
            is AndroidNavEntry.History -> {
                openHistory()
            }
            is AndroidNavEntry.Downloads -> {
                openDownloads()
            }
        }
    }

    fun goBack(): Boolean {
        val current = _state.value
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

        if (backStack.isNotEmpty()) {
            val currentEntry = currentNavEntry()
            val target = backStack.removeAt(backStack.lastIndex)
            forwardStack.add(currentEntry)
            if (forwardStack.size > 50) forwardStack.removeAt(0)

            isNavigatingHistory = true
            try {
                restoreNavEntry(target)
            } finally {
                isNavigatingHistory = false
            }
            return true
        }

        return when (current.screen) {
            Screen.Reader -> {
                val currentEntry = currentNavEntry()
                forwardStack.add(currentEntry)
                closeReader()
                true
            }
            Screen.Series -> {
                val currentEntry = currentNavEntry()
                forwardStack.add(currentEntry)
                closeSeries()
                true
            }
            Screen.History -> {
                val currentEntry = currentNavEntry()
                forwardStack.add(currentEntry)
                closeHistory()
                true
            }
            Screen.Downloads -> {
                val currentEntry = currentNavEntry()
                forwardStack.add(currentEntry)
                closeDownloads()
                true
            }
            Screen.Browse -> {
                if (current.isSearch) {
                    selectCatalog(current.catalog)
                    true
                } else {
                    false
                }
            }
        }
    }

    fun goForward(): Boolean {
        if (forwardStack.isEmpty()) return false
        val currentEntry = currentNavEntry()
        val target = forwardStack.removeAt(forwardStack.lastIndex)
        backStack.add(currentEntry)
        if (backStack.size > 50) backStack.removeAt(0)

        isNavigatingHistory = true
        try {
            restoreNavEntry(target)
        } finally {
            isNavigatingHistory = false
        }
        return true
    }

    init {
        _state.update {
            it.copy(
                viewMode = singleViewPreference,
                readDirection = globalReadDirection,
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
                    _state.update { it.copy(downloadProgress = p) }
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

    fun handleNotificationLaunch(targetCatalog: Catalog? = null) {
        readerJob?.cancel()
        episodeJob?.cancel()
        val destCatalog = targetCatalog ?: Catalog.FAVORITE
        _state.update {
            it.copy(
                screen = Screen.Browse,
                catalog = destCatalog,
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

    fun selectCatalog(catalog: Catalog) {
        if (
            catalog == _state.value.catalog &&
            _state.value.screen == Screen.Browse &&
            !_state.value.isSearch
        ) return
        pushHistory(AndroidNavEntry.Browse(catalog, 1))
        readerJob?.cancel()
        episodeJob?.cancel()
        val cached = catalogCache[catalog]
        val (cachedPage, cachedLastPage) = catalogPages[catalog] ?: Pair(1, 1)
        _state.update {
            it.copy(
                catalog = catalog,
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
        if (cached == null || catalog == Catalog.FAVORITE) {
            loadPage(cachedPage, replace = true)
        }
    }

    fun toggleSearchBar() {
        val current = _state.value
        if (current.searchExpanded) {
            if (current.isSearch) {
                selectCatalog(current.catalog)
            } else {
                _state.update { it.copy(searchExpanded = false, searchInput = "") }
            }
        } else {
            _state.update { it.copy(searchExpanded = true) }
        }
    }

    fun updateSearchInput(value: String) {
        _state.update { it.copy(searchInput = value) }
    }

    fun submitSearch() {
        val query = _state.value.searchInput.trim()
        if (query.isEmpty()) return
        pushHistory(AndroidNavEntry.Browse(_state.value.catalog, 1, query))
        readerJob?.cancel()
        episodeJob?.cancel()
        _state.update {
            it.copy(
                searchQuery = query,
                searchInput = query,
                screen = Screen.Browse,
                series = null,
                episodes = emptyList(),
                items = emptyList(),
                error = null,
                page = 1,
                lastPage = 1,
                currentEpisode = null,
                readerImages = emptyList(),
                readerError = null,
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
        if (!current.catalog.paginated) return
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
        pushHistory(AndroidNavEntry.Series(item, 1))
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
                viewMode = targetMode,
                readDirection = globalReadDirection,
            )
        }
        loadEpisodes(item, 1)
        viewModelScope.launch {
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

    fun toggleFavorite() {
        val series = _state.value.series ?: return
        toggleFavorite(series)
    }

    fun toggleFavorite(item: ToonItem) {
        viewModelScope.launch {
            val favorited = repository.toggleFavorite(item)
            catalogCache.remove(Catalog.FAVORITE)
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
        viewModelScope.launch {
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
        pushHistory(AndroidNavEntry.History)
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
            val items = repository.getHistory()
            _state.update { it.copy(historyItems = items, historyLoading = false) }
        }
    }

    fun openDownloadOptions() {
        _state.update { it.copy(showDownloadOptions = true) }
    }

    fun closeDownloadOptions() {
        _state.update { it.copy(showDownloadOptions = false) }
    }

    fun startDownloadEpisodes(episodesToDownload: List<EpisodeItem>) {
        val series = _state.value.series ?: return
        closeDownloadOptions()
        repository.downloadManager?.enqueueEpisodes(series, episodesToDownload)
    }

    fun cancelDownloads() {
        repository.downloadManager?.cancelAll()
    }

    fun openDownloads() {
        pushHistory(AndroidNavEntry.Downloads)
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
            val items = repository.downloadManager?.getDownloadedToonSummaries().orEmpty()
            _state.update { it.copy(downloadSummaries = items, downloadLoading = false) }
        }
    }

    fun deleteToonDownloads(toonId: String) {
        viewModelScope.launch {
            repository.downloadManager?.deleteToonDownloads(toonId)
            loadDownloads()
        }
    }

    fun deleteHistory(toonId: String) {
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            val res = repository.syncManager?.syncFull()
            if (res?.success == true) {
                loadPage(page = _state.value.page, replace = true)
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

    fun reopenHistoryEpisode(item: ReadHistory, startPage: Int = 0) {
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
        val targetMode = if (currentScreenWidthDp >= 600) ViewMode.DUAL else singleViewPreference
        _state.update {
            it.copy(
                screen = Screen.Reader,
                series = toon,
                episodes = emptyList(),
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

    fun openSeriesFromHistory(item: ReadHistory) {
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

    fun continueHistoryEpisode(item: ReadHistory) {
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
        val targetMode = if (currentScreenWidthDp >= 600) ViewMode.DUAL else singleViewPreference
        _state.update {
            it.copy(
                screen = Screen.Reader,
                series = toon,
                episodes = emptyList(),
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
            pushHistory(AndroidNavEntry.Reader(series, episode, current.episodePage))
        }
        val nowMs = System.currentTimeMillis()
        val episodes = current.episodes
        val idx = episodes.indexOfFirst { it.wrId == episode.wrId }
        val lastPage = current.episodeLastPage.coerceAtLeast(1)
        val currentPage = current.episodePage.coerceIn(1, lastPage)
        val pageSize = 100

        val seriesId = series?.id.orEmpty()
        val knownLastPageCount = toonLastPageCounts[seriesId]
        val knownTotalCount = toonTotalCounts[seriesId]

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
                repository.markEpisodeRead(series.id, episode.wrId, episode.lastReadPage)
                val existingHistory = repository.getHistory().firstOrNull { it.toonId == series.id }

                val finalOrder = calculatedOrder
                    ?: existingHistory?.lastReadOrder
                    ?: 1

                val candidateTotal = if (totalEpCount > 1) totalEpCount else (existingHistory?.totalEpisodes ?: 1)
                val finalTotal = maxOf(candidateTotal, finalOrder, existingHistory?.totalEpisodes ?: 1)

                val progressText = "$finalOrder/$finalTotal"
                val savedHistory = ReadHistory(
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
        viewModelScope.launch {
            repository.saveEpisodePage(series.id, episode.wrId, targetPage)
        }
    }

    fun closeReader() {
        readerJob?.cancel()
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
        repository.syncManager?.triggerDebouncedPush()
    }

    fun openNextEpisode() {
        val current = _state.value
        val series = current.series
        val currentEp = current.currentEpisode
        if (series != null && currentEp != null) {
            viewModelScope.launch {
                repository.saveEpisodePage(series.id, currentEp.wrId, 0)
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

                    toonTotalCounts[target.id] = exactTotal
                    val existing = repository.getHistory().firstOrNull { it.toonId == target.id }
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
                                if (row.id == target.id) row.copy(readProgress = progressText) else row
                            }
                            if (!curr.isSearch) {
                                catalogCache[curr.catalog] = updatedItems
                            }
                            curr.copy(
                                items = updatedItems,
                                seriesHistory = if (curr.series?.id == target.id) updated else curr.seriesHistory,
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
                val items = repository.search(query)
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
                val currentCat = _state.value.catalog
                val result = repository.loadListing(currentCat, page)
                repository.markSeen(result.items)
                catalogCache[currentCat] = result.items
                catalogPages[currentCat] = Pair(result.currentPage, result.lastPage)
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
            _state.update {
                it.copy(
                    episodeLoading = it.episodes.isEmpty() || !asRefresh,
                    episodeError = null,
                    episodePage = page,
                    refreshing = asRefresh,
                    showPageJump = false,
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
                        refreshing = false,
                        episodeError = null,
                    )
                }
                if (result.items.isNotEmpty()) {
                    val lastP = result.lastPage.coerceAtLeast(1)
                    val currP = result.currentPage.coerceIn(1, lastP)
                    val pageSize = 100

                    if (lastP <= 1) {
                        toonTotalCounts[item.id] = result.items.size
                        toonLastPageCounts[item.id] = result.items.size
                    } else if (currP == lastP) {
                        toonLastPageCounts[item.id] = result.items.size
                        val total = (lastP - 1) * pageSize + result.items.size
                        toonTotalCounts[item.id] = total
                    } else if (!toonLastPageCounts.containsKey(item.id)) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val lastRes = repository.loadEpisodes(item, lastP)
                                val lastCount = lastRes.items.size
                                toonLastPageCounts[item.id] = lastCount
                                val exactTotal = (lastP - 1) * pageSize + lastCount
                                toonTotalCounts[item.id] = exactTotal

                                val existing = repository.getHistory().firstOrNull { it.toonId == item.id }
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
                                            if (row.id == item.id) row.copy(readProgress = progressText) else row
                                        }
                                        curr.copy(
                                            items = updatedItems,
                                            seriesHistory = if (curr.series?.id == item.id) updated else curr.seriesHistory,
                                        )
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    val knownLast = toonLastPageCounts[item.id]
                    val totalCalculated = when {
                        lastP <= 1 -> result.items.size
                        currP == lastP -> (lastP - 1) * pageSize + result.items.size
                        knownLast != null -> (lastP - 1) * pageSize + knownLast
                        else -> (lastP - 1) * pageSize + result.items.size
                    }

                    val existing = repository.getHistory().firstOrNull { it.toonId == item.id }
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
                                    if (row.id == item.id) row.copy(readProgress = progressText) else row
                                }
                                curr.copy(
                                    items = updatedItems,
                                    seriesHistory = if (curr.series?.id == item.id) updated else curr.seriesHistory,
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        episodeLoading = false,
                        refreshing = false,
                        episodeError = humanError("회차를 불러오지 못했습니다.", e),
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
                val seriesId = _state.value.series?.id
                val images = repository.loadImages(episode, seriesId)
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
}
