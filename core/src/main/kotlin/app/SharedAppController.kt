package com.comics8.core.app

import com.comics8.core.data.SharedToonRepository
import com.comics8.core.i18n.AppLanguage
import com.comics8.core.i18n.AppStrings
import com.comics8.core.model.ArtistRef
import com.comics8.core.model.BrowseTab
import com.comics8.core.model.CustomProxyConfig
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodeListingTotal
import com.comics8.core.model.EpisodeSortOrder
import com.comics8.core.model.PageTapZone
import com.comics8.core.model.PairConfirmResult
import com.comics8.core.model.PairRequestResult
import com.comics8.core.model.ProgressDisplayMode
import com.comics8.core.model.ProxyType
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ReaderDomain
import com.comics8.core.model.SplitMode
import com.comics8.core.model.SyncResult
import com.comics8.core.model.ToonItem
import com.comics8.core.model.ViewMode
import com.comics8.core.network.NetworkSettingsStorage
import com.comics8.core.source.ComicSource
import com.comics8.core.source.SearchSuggestion
import com.comics8.core.source.WorkId
import com.comics8.core.source.js.JsPackStore
import com.comics8.core.source.network.NetworkProtocol
import com.comics8.core.source.network.NetworkSourceConfig
import com.comics8.core.source.network.NetworkSourceStore
import com.comics8.core.storage.ReadHistoryRecord
import com.comics8.core.sync.SyncConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun humanError(prefix: String, e: Exception): String {
    val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    return "$prefix\n$detail"
}

internal fun Map<String, Int>.withCount(workId: WorkId, count: Int): Map<String, Int> =
    this + (workId.storageKey() to count)

class SharedAppController(
    internal val scope: CoroutineScope,
    val repository: SharedToonRepository,
    internal val jsPackStore: JsPackStore,
    internal val networkSourceStore: NetworkSourceStore,
    internal val platform: PlatformHost,
) : AutoCloseable {
    internal val sourceSettings = platform.sourceSettings
    internal val prefs = platform.prefs

    var singleViewPreference: ViewMode
        get() = try {
            when (prefs.getString("single_view_mode", ViewMode.AUTO.name)) {
                ViewMode.AUTO.name -> ViewMode.AUTO
                ViewMode.PAGE.name -> ViewMode.PAGE
                ViewMode.SPLIT.name -> ViewMode.SPLIT
                ViewMode.DUAL.name -> ViewMode.DUAL
                else -> ViewMode.SCROLL
            }
        } catch (_: Exception) {
            ViewMode.AUTO
        }
        internal set(value) {
            prefs.putString("single_view_mode", value.name)
        }

    var globalReadDirection: ReadDirection
        get() = try {
            val name = prefs.getString("read_direction", ReadDirection.RIGHT_TO_LEFT.name)
            if (name == ReadDirection.LEFT_TO_RIGHT.name) ReadDirection.LEFT_TO_RIGHT else ReadDirection.RIGHT_TO_LEFT
        } catch (_: Exception) {
            ReadDirection.RIGHT_TO_LEFT
        }
        internal set(value) {
            prefs.putString("read_direction", value.name)
        }

    var pageTapZonePreference: PageTapZone
        get() = try {
            val name = prefs.getString("page_tap_zone", PageTapZone.FOLLOW_DIRECTION.name)
            PageTapZone.valueOf(name ?: PageTapZone.FOLLOW_DIRECTION.name)
        } catch (_: Exception) {
            PageTapZone.FOLLOW_DIRECTION
        }
        private set(value) {
            prefs.putString("page_tap_zone", value.name)
        }

    var volumePageTurnPreference: Boolean
        get() = try {
            prefs.getBoolean("volume_page_turn", false)
        } catch (_: Exception) {
            false
        }
        private set(value) {
            prefs.putBoolean("volume_page_turn", value)
        }

    val appLanguage: AppLanguage
        get() = try {
            AppLanguage.fromCode(prefs.getString("app_language", AppLanguage.AUTO.code))
        } catch (_: Exception) {
            AppLanguage.AUTO
        }

    private var currentScreenWidthDp: Int = platform.initialViewportWidthDp
    private var currentScreenHeightDp: Int = platform.initialViewportHeightDp

    internal fun resolveTargetViewMode(
        widthDp: Int = currentScreenWidthDp,
        heightDp: Int = currentScreenHeightDp,
    ): ViewMode {
        if (singleViewPreference == ViewMode.AUTO) return ViewMode.AUTO
        return ReaderDomain.resolveViewMode(singleViewPreference, widthDp, heightDp)
    }

    internal val _state = MutableStateFlow(
        AppUiState(
            appLanguage = appLanguage,
            viewMode = if (platform.persistGlobalReaderDefaults) singleViewPreference else platform.initialViewMode,
            viewportWidthDp = currentScreenWidthDp,
            viewportHeightDp = currentScreenHeightDp,
            readDirection = if (platform.persistGlobalReaderDefaults) globalReadDirection else platform.initialReadDirection,
            pageTapZone = pageTapZonePreference,
            volumePageTurnEnabled = volumePageTurnPreference,
            notificationEnabled = platform.initialNotificationEnabled(),
            notificationIntervalMinutes = platform.initialNotificationIntervalMinutes(),
            hasNotificationPermission = platform.checkNotificationPermission(),
            packsReady = platform.initialPacksReady,
            episodeSortOrder = sourceSettings.episodeSortOrder(sourceSettings.activeSourceId() ?: WorkId.LOCAL_SOURCE),
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    internal val sourceManagement = SourceManagementCoordinator(this)
    internal val readerSession = ReaderSessionCoordinator(this)
    internal val browseSearch = BrowseSearchCoordinator(this)
    internal val historyDownloads = HistoryDownloadsCoordinator(this)
    internal val syncBackupUpdate = SyncBackupUpdateCoordinator(this)

    init {
        repository.applyPreferences { id -> sourceSettings.language(id)?.ifBlank { null } }
        sourceManagement.ensureLocalSourceLoaded()
        val initialNet = NetworkSettingsStorage.load { k, def -> prefs.getString(k, def) }
        _state.update { it.copy(networkSettings = initialNet) }
        repository.client.applyNetworkSettings(initialNet, SyncConstants.DEFAULT_SERVER_URL)

        sourceManagement.applyActiveSource(sourceSettings.activeSourceId() ?: WorkId.LOCAL_SOURCE, loadListing = false)
        repository.syncManager?.let { sm ->
            scope.launch {
                sm.syncState.collect { s ->
                    _state.update { it.copy(syncState = s) }
                    repository.client.applyNetworkSettings(_state.value.networkSettings, s.serverUrl)
                }
            }
            scope.launch {
                val res = sm.syncOnLaunch()
                if (res.success && (res.favoritesCount > 0 || res.historyCount > 0 || res.episodesCount > 0)) {
                    syncBackupUpdate.applySyncRefresh()
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
                        historyDownloads.refreshDownloadedWrIds()
                        if (_state.value.screen == AppScreen.Downloads) {
                            historyDownloads.loadDownloads()
                        }
                    }
                }
            }
        }
        if (_state.value.activeSourceId != null) {
            if (platform.initialLoadWithForce) {
                browseSearch.refresh(force = true)
            } else {
                browseSearch.loadPage(1)
            }
        }
        syncBackupUpdate.checkForUpdate()
    }

    override fun close() {
        browseSearch.close()
        historyDownloads.close()
        readerSession.close()
    }

    fun updateState(transform: (AppUiState) -> AppUiState) {
        _state.update(transform)
    }

    fun setAppLanguage(language: AppLanguage) {
        prefs.putString("app_language", language.code)
        _state.update { it.copy(appLanguage = language) }
    }

    fun setPageTapZone(zone: PageTapZone) {
        pageTapZonePreference = zone
        _state.update { it.copy(pageTapZone = zone) }
    }

    fun setVolumePageTurnEnabled(enabled: Boolean) {
        volumePageTurnPreference = enabled
        _state.update { it.copy(volumePageTurnEnabled = enabled) }
    }

    fun openViewerOptions() {
        _state.update { it.copy(showViewerOptions = true) }
    }

    fun closeViewerOptions() {
        _state.update { it.copy(showViewerOptions = false) }
    }

    fun toggleViewerOptions(show: Boolean = !_state.value.showViewerOptions) {
        _state.update { it.copy(showViewerOptions = show) }
    }

    fun selectSettingsCategory(category: SettingsCategory?) {
        _state.update { it.copy(selectedSettingsCategory = category) }
    }

    fun openSettings(category: SettingsCategory? = null) {
        readerSession.cancelReaderJob()
        browseSearch.cancelEpisodeJob()
        val notifEnabled = platform.initialNotificationEnabled()
        val notifInterval = platform.initialNotificationIntervalMinutes()
        val notifSources = repository.installedSources().associate { it.id to sourceSettings.isNotificationEnabled(it.id) }
        val hasPerm = platform.checkNotificationPermission()
        _state.update {
            it.copy(
                screen = AppScreen.Settings,
                selectedSettingsCategory = category,
                notificationEnabled = notifEnabled,
                notificationIntervalMinutes = notifInterval,
                notificationSources = notifSources,
                hasNotificationPermission = hasPerm,
            )
        }
    }

    fun closeSettings() {
        if (_state.value.selectedSettingsCategory != null) {
            _state.update { it.copy(selectedSettingsCategory = null) }
        } else {
            _state.update { it.copy(screen = AppScreen.Browse) }
        }
    }

    fun openSourceManager() {
        readerSession.cancelReaderJob()
        browseSearch.cancelEpisodeJob()
        _state.update { it.copy(screen = AppScreen.SourceManager) }
    }

    fun closeSourceManager() {
        _state.update { it.copy(screen = AppScreen.Browse) }
    }

    fun goBack(): Boolean {
        val current = _state.value
        if (current.showViewerOptions) {
            closeViewerOptions()
            return true
        }
        if (current.artistPick != null) {
            dismissArtistPick()
            return true
        }
        if (current.showPageJump) {
            togglePageJump(false)
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
        if (current.showAddSourceSheet) {
            dismissAddSourceSheet()
            return true
        }
        if (current.networkDraft != null) {
            closeNetworkSourceDialog()
            return true
        }
        if (current.showRemoveSourceSheet) {
            closeRemoveSourceSheet()
            return true
        }
        if (current.sourceError != null) {
            dismissSourceError()
            return true
        }

        return when (current.screen) {
            AppScreen.Reader -> {
                if (!readerSession.hasExternalSession) {
                    readerSession.lastViewedEpisode = current.currentEpisode
                }
                closeReader()
                true
            }
            AppScreen.Series -> {
                browseSearch.lastViewedSeries = current.series
                browseSearch.lastViewedEpisodePage = current.episodePage
                when (browseSearch.previousScreenBeforeSeries) {
                    AppScreen.History -> openHistory()
                    AppScreen.Downloads -> openDownloads()
                    else -> closeSeries()
                }
                true
            }
            AppScreen.History -> {
                closeHistory()
                true
            }
            AppScreen.Downloads -> {
                closeDownloads()
                true
            }
            AppScreen.Settings -> {
                closeSettings()
                true
            }
            AppScreen.SourceManager -> {
                closeSourceManager()
                true
            }
            AppScreen.Browse -> {
                if (current.isSearch) {
                    current.tab?.let { selectTab(it) }
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
            AppScreen.Browse, AppScreen.History, AppScreen.Downloads -> {
                val targetSeries = browseSearch.lastViewedSeries ?: current.items.firstOrNull()
                if (targetSeries != null) {
                    openSeries(targetSeries)
                    if (browseSearch.lastViewedEpisodePage > 1) {
                        goToEpisodePage(browseSearch.lastViewedEpisodePage)
                    }
                    true
                } else {
                    false
                }
            }
            AppScreen.Series -> {
                val targetEp = readerSession.lastViewedEpisode
                    ?: current.seriesHistory?.let { h -> current.episodes.firstOrNull { it.wrId == h.lastWrId } }
                    ?: (current.rawEpisodes.ifEmpty { current.episodes }).lastOrNull()
                    ?: current.episodes.firstOrNull()
                if (targetEp != null) {
                    openEpisode(targetEp)
                    true
                } else {
                    false
                }
            }
            AppScreen.Reader -> {
                if (current.hasNextEpisode) {
                    openNextEpisode()
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    fun onImportedPacksReady() {
        repository.applyPreferences { id -> sourceSettings.language(id)?.ifBlank { null } }
        _state.update { it.copy(packsReady = true) }
        val currentActive = _state.value.activeSourceId
        val prefActive = sourceSettings.activeSourceId()
        if (currentActive != prefActive) {
            sourceManagement.applyActiveSource(prefActive, loadListing = false)
        }
        if (_state.value.activeSourceId != null && _state.value.items.isEmpty() && !browseSearch.isLoadActive) {
            loadPage(1)
        }
    }

    fun handleNotificationLaunch(targetTab: String? = null) {
        readerSession.cancelReaderJob()
        browseSearch.cancelEpisodeJob()
        val dest = BrowseTab.resolveLaunchTarget(targetTab, _state.value.browseTabs) ?: return
        _state.update {
            it.copy(
                screen = AppScreen.Browse,
                tab = dest,
                series = null,
                rawEpisodes = emptyList(),
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
        browseSearch.syncOnBrowseRefresh()
    }

    fun updateViewportSize(widthDp: Int, heightDp: Int) {
        currentScreenWidthDp = widthDp
        currentScreenHeightDp = heightDp
        _state.update {
            if (it.viewportWidthDp == widthDp && it.viewportHeightDp == heightDp) {
                it
            } else {
                it.copy(viewportWidthDp = widthDp, viewportHeightDp = heightDp)
            }
        }
    }

    internal suspend fun listingProgress(
        sourceId: String,
        lastReadOrder: Int,
        total: EpisodeListingTotal?,
        workId: WorkId,
    ): Pair<String, Int> {
        val readCount = countIfNeeded(workId)
        return repository.formatReadProgress(sourceId, lastReadOrder, total, readCount) to readCount
    }

    internal suspend fun countIfNeeded(workId: WorkId): Int {
        if (!sourceSettings.progressDisplayMode(workId.sourceId).requiresReadCount) {
            return 0
        }
        return repository.countReadEpisodes(workId)
    }

    internal suspend fun countIfNeeded(workIds: List<WorkId>, sourceId: String): Map<String, Int> {
        if (workIds.isEmpty()) return emptyMap()
        if (!sourceSettings.progressDisplayMode(sourceId).requiresReadCount) {
            return emptyMap()
        }
        return repository.countReadEpisodes(workIds)
    }

    internal fun toToonItem(record: ReadHistoryRecord): ToonItem = ToonItem(
        id = record.toonId,
        title = record.toonTitle,
        thumbUrl = record.toonThumbUrl,
        href = record.toonHref,
        genre = "",
        updatedAt = null,
        sourceId = record.sourceId,
        readProgress = repository.formatReadProgress(
            sourceId = record.sourceId,
            lastReadOrder = record.lastReadOrder,
            total = _state.value.catalogTotals[record.workId().storageKey()],
            readCount = _state.value.readCounts[record.workId().storageKey()] ?: 0,
        ),
    )

    // --- Source management facade ---
    fun setActiveSource(sourceId: String) = sourceManagement.setActiveSource(sourceId)
    fun setSourceEnabled(sourceId: String, enabled: Boolean) = sourceManagement.setSourceEnabled(sourceId, enabled)
    fun openAddSourceSheet() = sourceManagement.openAddSourceSheet()
    fun dismissAddSourceSheet() = sourceManagement.dismissAddSourceSheet()
    fun closeAddSourceSheet() = sourceManagement.dismissAddSourceSheet()
    fun openNetworkSourceDialog(protocol: NetworkProtocol) = sourceManagement.openNetworkSourceDialog(protocol)
    fun updateNetworkDraft(draft: NetworkSourceConfig) = sourceManagement.updateNetworkDraft(draft)
    fun closeNetworkSourceDialog() = sourceManagement.closeNetworkSourceDialog()
    fun testNetworkConnection() = sourceManagement.testNetworkConnection()
    fun registerNetworkConnection() = sourceManagement.registerNetworkConnection()
    fun openRemoveSourceSheet() = sourceManagement.openRemoveSourceSheet()
    fun closeRemoveSourceSheet() = sourceManagement.closeRemoveSourceSheet()
    fun dismissSourceError() = sourceManagement.dismissSourceError()
    fun setSourceError(message: String) = sourceManagement.setSourceError(message)
    fun importJsScript(script: String, fileName: String = "source.js") = sourceManagement.importJsScript(script, fileName)
    fun uninstallSource(sourceId: String) = sourceManagement.uninstallSource(sourceId)
    fun removeActiveSource() = sourceManagement.removeActiveSource()
    fun getSourceProgressDisplayMode(sourceId: String): ProgressDisplayMode =
        sourceManagement.getSourceProgressDisplayMode(sourceId)
    fun requestRemoveSource(sourceId: String) = sourceManagement.requestRemoveSource(sourceId)
    fun requestRemoveSource(source: ComicSource) = sourceManagement.requestRemoveSource(source)
    fun getNetworkSourceConfig(sourceId: String): NetworkSourceConfig? =
        sourceManagement.getNetworkSourceConfig(sourceId)
    fun updateNetworkSourceConfig(config: NetworkSourceConfig, onResult: (Boolean, String?) -> Unit) =
        sourceManagement.updateNetworkSourceConfig(config, onResult)
    fun getJsSourceScript(sourceId: String): String? = sourceManagement.getJsSourceScript(sourceId)
    fun updateJsSourceScript(sourceId: String, newScript: String, onResult: (Boolean, String?) -> Unit) =
        sourceManagement.updateJsSourceScript(sourceId, newScript, onResult)
    fun openAddSmb() = sourceManagement.openAddSmb()
    fun openAddWebDav() = sourceManagement.openAddWebDav()
    fun requestAddLocalFolder() = sourceManagement.requestAddLocalFolder()
    fun consumeLibraryTreePick() = sourceManagement.consumeLibraryTreePick()
    fun addLibraryRootPath(key: String, refreshForce: Boolean = false) =
        sourceManagement.addLibraryRootPath(key, refreshForce)
    fun addLibraryRootFromPicker() = sourceManagement.addLibraryRootFromPicker()
    fun removeLibraryRoot(key: String, refreshForce: Boolean = false) =
        sourceManagement.removeLibraryRoot(key, refreshForce)
    fun reorderStorageSources(fromIndex: Int, toIndex: Int) = sourceManagement.reorderStorageSources(fromIndex, toIndex)
    fun reorderOnlineSources(fromIndex: Int, toIndex: Int) = sourceManagement.reorderOnlineSources(fromIndex, toIndex)
    fun setEpisodeSortOrder(order: EpisodeSortOrder) = sourceManagement.setEpisodeSortOrder(order)
    fun getSourceEpisodeSortOrder(sourceId: String): EpisodeSortOrder = sourceManagement.getSourceEpisodeSortOrder(sourceId)
    fun setSourceEpisodeSortOrder(sourceId: String, order: EpisodeSortOrder) =
        sourceManagement.setSourceEpisodeSortOrder(sourceId, order)
    fun setSourceProgressDisplayMode(sourceId: String, mode: ProgressDisplayMode) =
        sourceManagement.setSourceProgressDisplayMode(sourceId, mode)
    fun refreshCurrentListingProgress() = sourceManagement.refreshCurrentListingProgress()

    // --- Browse, search & series facade ---
    fun selectTab(tab: BrowseTab) = browseSearch.selectTab(tab, force = false)
    fun onListingOpen(item: ToonItem) = browseSearch.onListingOpen(item)
    fun openArtistPicker(item: ToonItem, entryEpisodeId: String? = item.entryEpisodeId) =
        browseSearch.openArtistPicker(item, entryEpisodeId)
    fun dismissArtistPick() = browseSearch.dismissArtistPick()
    fun pickArtist(artist: ArtistRef) = browseSearch.pickArtist(artist)
    fun toggleSearchBar() = browseSearch.toggleSearchBar()
    fun updateSearchInput(value: String) = browseSearch.updateSearchInput(value)
    fun applySearchSuggestion(suggestion: SearchSuggestion) = browseSearch.applySearchSuggestion(suggestion)
    fun submitSearch(query: String = _state.value.searchInput) = browseSearch.submitSearch(query)
    fun refresh(force: Boolean = false) = browseSearch.refresh(force)
    fun goToPage(page: Int) = browseSearch.goToPage(page)
    fun nextPage() = browseSearch.nextPage()
    fun prevPage() = browseSearch.prevPage()
    fun nextEpisodePage() = browseSearch.nextEpisodePage()
    fun prevEpisodePage() = browseSearch.prevEpisodePage()
    fun togglePageJump(show: Boolean) = browseSearch.togglePageJump(show)
    fun openSeries(item: ToonItem) = browseSearch.openSeries(item)
    fun toggleFavorite() = browseSearch.toggleFavorite()
    fun toggleFavorite(item: ToonItem) = browseSearch.toggleFavorite(item)
    fun resumeSeries() = browseSearch.resumeSeries()
    fun openFirstEpisode() = browseSearch.openFirstEpisode()
    fun closeSeries() = browseSearch.closeSeries()
    fun goToEpisodePage(page: Int) = browseSearch.goToEpisodePage(page)
    fun loadPage(page: Int, replace: Boolean = false, asRefresh: Boolean = false) =
        browseSearch.loadPage(page, replace, asRefresh)
    fun loadEpisodes(item: ToonItem, page: Int, asRefresh: Boolean = false) =
        browseSearch.loadEpisodes(item, page, asRefresh)

    // --- History & downloads facade ---
    fun openHistory() = historyDownloads.openHistory()
    fun closeHistory() = historyDownloads.closeHistory()
    fun loadHistory() = historyDownloads.loadHistory()
    fun deleteHistory(workId: WorkId) = historyDownloads.deleteHistory(workId)
    fun clearAllHistory() = historyDownloads.clearAllHistory()
    fun openSeriesFromHistory(item: ReadHistoryRecord) = historyDownloads.openSeriesFromHistory(item)
    fun continueHistoryEpisode(item: ReadHistoryRecord) = historyDownloads.continueHistoryEpisode(item)
    fun openDownloadOptions() = historyDownloads.openDownloadOptions()
    fun closeDownloadOptions() = historyDownloads.closeDownloadOptions()
    fun startDownloadEpisodes(episodesToDownload: List<EpisodeItem>) = historyDownloads.startDownloadEpisodes(episodesToDownload)
    fun startDownloadEpisode(episode: EpisodeItem) = historyDownloads.startDownloadEpisode(episode)
    fun cancelDownloads() = historyDownloads.cancelDownloads()
    fun openDownloads() = historyDownloads.openDownloads()
    fun closeDownloads() = historyDownloads.closeDownloads()
    fun loadDownloads() = historyDownloads.loadDownloads()
    fun deleteToonDownloads(workId: WorkId) = historyDownloads.deleteToonDownloads(workId)

    // --- Reader session facade ---
    fun openEpisode(episode: EpisodeItem) = readerSession.openEpisode(episode)
    fun openExternalReaderSession(episode: EpisodeItem, resolveImages: suspend () -> List<String>) =
        readerSession.openExternalReaderSession(episode, resolveImages)
    fun savePage(page: Int, seenThroughPage: Int = page) = readerSession.savePage(page, seenThroughPage)
    fun flushPendingPageSave() = readerSession.flushPendingPageSave()
    fun flushAndSyncOnBackground() = readerSession.flushAndSyncOnBackground()
    fun closeReader() = readerSession.closeReader()
    fun evictReaderImage(url: String) = readerSession.evictReaderImage(url)
    fun toggleFullscreen() = readerSession.toggleFullscreen()
    fun openNextEpisode() = readerSession.openNextEpisode()
    fun openPrevEpisode() = readerSession.openPrevEpisode()
    fun refreshReaderImages() = readerSession.refreshReaderImages()
    fun setViewMode(mode: ViewMode) = readerSession.setViewMode(mode)
    fun setReadDirection(direction: ReadDirection) = readerSession.setReadDirection(direction)
    fun setSplitMode(mode: SplitMode) = readerSession.setSplitMode(mode)
    fun recordImageAspectRatio(index: Int, width: Int, height: Int) = readerSession.recordImageAspectRatio(index, width, height)

    // --- Sync, backup, proxy, notifications & updates facade ---
    fun checkForUpdate(manual: Boolean = false, onFeedback: ((String) -> Unit)? = null) =
        syncBackupUpdate.checkForUpdate(manual, onFeedback)
    fun openUpdateDialog() = syncBackupUpdate.openUpdateDialog()
    fun dismissUpdateDialog() = syncBackupUpdate.dismissUpdateDialog()
    fun startUpdate() = syncBackupUpdate.startUpdate()
    fun toggleNotificationEnabled(enabled: Boolean) = syncBackupUpdate.toggleNotificationEnabled(enabled)
    fun setNotificationIntervalMinutes(minutes: Long) = syncBackupUpdate.setNotificationIntervalMinutes(minutes)
    fun toggleSourceNotification(sourceId: String, enabled: Boolean) =
        syncBackupUpdate.toggleSourceNotification(sourceId, enabled)
    fun getSourceNotificationEnabled(sourceId: String): Boolean = syncBackupUpdate.getSourceNotificationEnabled(sourceId)
    fun checkNotificationPermission() = syncBackupUpdate.checkNotificationPermission()
    fun syncNow() = syncBackupUpdate.syncNow()
    fun restoreWithMasterKey(key: String, onResult: (SyncResult) -> Unit) = syncBackupUpdate.restoreWithMasterKey(key, onResult)
    fun updateSyncKey(key: String) = syncBackupUpdate.updateSyncKey(key)
    fun generateNewSyncKey() = syncBackupUpdate.generateNewSyncKey()
    fun updateSyncServerUrl(url: String) = syncBackupUpdate.updateSyncServerUrl(url)
    fun toggleAutoSync(enabled: Boolean) = syncBackupUpdate.toggleAutoSync(enabled)
    fun setProxyType(type: ProxyType) = syncBackupUpdate.setProxyType(type)
    fun setCustomProxy(config: CustomProxyConfig) = syncBackupUpdate.setCustomProxy(config)
    fun testProxyConnection(strings: AppStrings) = syncBackupUpdate.testProxyConnection(strings)
    fun toggleServerProxy(enabled: Boolean) = syncBackupUpdate.toggleServerProxy(enabled)
    fun requestPairingCode(onResult: (PairRequestResult) -> Unit) = syncBackupUpdate.requestPairingCode(onResult)
    fun confirmPairingCode(code: String, onResult: (PairConfirmResult) -> Unit) =
        syncBackupUpdate.confirmPairingCode(code, onResult)
    fun exportBackupWithWriter(writeJson: suspend (String) -> Unit, onResult: (Boolean, String) -> Unit) =
        syncBackupUpdate.exportBackupWithWriter(writeJson, onResult)
    fun importBackupWithReader(readJson: suspend () -> String, onResult: (Boolean, String) -> Unit) =
        syncBackupUpdate.importBackupWithReader(readJson, onResult)
}
