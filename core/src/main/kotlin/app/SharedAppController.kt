package com.comics8.core.app

import com.comics8.core.data.SharedToonRepository
import com.comics8.core.i18n.AppLanguage
import com.comics8.core.i18n.AppStrings
import com.comics8.core.i18n.I18n
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.model.ArtistRef
import com.comics8.core.model.BrowseTab
import com.comics8.core.model.CustomProxyConfig
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodeListingTotal
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.EpisodeSortOrder
import com.comics8.core.model.PageTapZone
import com.comics8.core.model.PairConfirmResult
import com.comics8.core.model.PairRequestResult
import com.comics8.core.model.ProgressDisplayMode
import com.comics8.core.model.ProxyType
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ReaderDomain
import com.comics8.core.model.ReaderProgress
import com.comics8.core.model.RepositoryTransforms
import com.comics8.core.model.SplitMode
import com.comics8.core.model.SyncResult
import com.comics8.core.model.ToonItem
import com.comics8.core.model.ViewMode
import com.comics8.core.model.sortedWithOrder
import com.comics8.core.network.NetworkSettingsStorage
import com.comics8.core.source.ComicSource
import com.comics8.core.source.SearchSuggestion
import com.comics8.core.source.WorkId
import com.comics8.core.source.js.JsComicSource
import com.comics8.core.source.js.JsPackStore
import com.comics8.core.source.local.LibraryScanIndex
import com.comics8.core.source.local.LocalSource
import com.comics8.core.source.network.NetworkLibrarySource
import com.comics8.core.source.network.NetworkProtocol
import com.comics8.core.source.network.NetworkSourceConfig
import com.comics8.core.source.network.NetworkSourceRuntime
import com.comics8.core.source.network.NetworkSourceStore
import com.comics8.core.source.network.createNetworkFileSystem
import com.comics8.core.source.resolveSourceType
import com.comics8.core.storage.ReadHistoryRecord
import com.comics8.core.sync.SyncActionCoordinator
import com.comics8.core.sync.SyncConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

private data class ExternalReaderSession(
    val returnScreen: AppScreen,
    val viewMode: ViewMode,
    val readDirection: ReadDirection,
    val splitMode: SplitMode,
)

class SharedAppController(
    private val scope: CoroutineScope,
    val repository: SharedToonRepository,
    private val jsPackStore: JsPackStore,
    private val networkSourceStore: NetworkSourceStore,
    private val platform: PlatformHost,
) : AutoCloseable {
    private val sourceSettings = platform.sourceSettings
    private val prefs = platform.prefs

    private val syncActions by lazy {
        SyncActionCoordinator(scope, { repository.syncManager }, ::applySyncRefresh)
    }

    var singleViewPreference: ViewMode
        get() = try {
            when (prefs.getString("single_view_mode", ViewMode.AUTO.name)) {
                ViewMode.AUTO.name -> ViewMode.AUTO
                ViewMode.PAGE.name -> ViewMode.PAGE
                else -> ViewMode.SCROLL
            }
        } catch (_: Exception) {
            ViewMode.AUTO
        }
        private set(value) {
            prefs.putString("single_view_mode", value.name)
        }

    var globalReadDirection: ReadDirection
        get() = try {
            val name = prefs.getString("read_direction", ReadDirection.RIGHT_TO_LEFT.name)
            if (name == ReadDirection.LEFT_TO_RIGHT.name) ReadDirection.LEFT_TO_RIGHT else ReadDirection.RIGHT_TO_LEFT
        } catch (_: Exception) {
            ReadDirection.RIGHT_TO_LEFT
        }
        private set(value) {
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

    private fun resolveTargetViewMode(
        widthDp: Int = currentScreenWidthDp,
        heightDp: Int = currentScreenHeightDp,
    ): ViewMode {
        if (singleViewPreference == ViewMode.AUTO) return ViewMode.AUTO
        return ReaderDomain.resolveViewMode(singleViewPreference, widthDp, heightDp)
    }

    private val _state = MutableStateFlow(
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

    private var loadJob: Job? = null
    private var episodeJob: Job? = null
    private var readerJob: Job? = null
    private var downloadCatalogJob: Job? = null
    private var suggestJob: Job? = null
    private var pageSaveJob: Job? = null
    private var readerSettingSaveJob: Job? = null
    private var pendingPageSave: Triple<WorkId, String, Int>? = null
    private var externalReaderSession: ExternalReaderSession? = null
    private var autoSwitchedEpisodeId: String? = null
    private var preAutoSwitchViewMode: ViewMode? = null
    private var readerToastJob: Job? = null

    private val catalogCache = mutableMapOf<BrowseTab, List<ToonItem>>()
    private val catalogPages = mutableMapOf<BrowseTab, Pair<Int, Int>>()

    private var lastViewedSeries: ToonItem? = null
    private var lastViewedEpisodePage: Int = 1
    private var lastViewedEpisode: EpisodeItem? = null
    private var previousScreenBeforeSeries: AppScreen = AppScreen.Browse

    init {
        repository.applyPreferences { id -> sourceSettings.language(id)?.ifBlank { null } }
        platform.onSourceWriteAccessChanged()
        ensureLocalSourceLoaded()
        val initialNet = NetworkSettingsStorage.load { k, def -> prefs.getString(k, def) }
        _state.update { it.copy(networkSettings = initialNet) }
        repository.client.applyNetworkSettings(initialNet, SyncConstants.DEFAULT_SERVER_URL)

        applyActiveSource(sourceSettings.activeSourceId() ?: WorkId.LOCAL_SOURCE, loadListing = false)
        repository.syncManager?.let { sm ->
            scope.launch {
                sm.syncState.collect { s ->
                    _state.update { it.copy(syncState = s) }
                    repository.client.applyNetworkSettings(_state.value.networkSettings, s.serverUrl)
                }
            }
            scope.launch {
                val res = sm.syncPull()
                if (res.success && (res.favoritesCount > 0 || res.historyCount > 0 || res.episodesCount > 0)) {
                    applySyncRefresh()
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
                        if (_state.value.screen == AppScreen.Downloads) {
                            loadDownloads()
                        }
                    }
                }
            }
        }
        if (_state.value.activeSourceId != null) {
            if (platform.initialLoadWithForce) {
                refresh(force = true)
            } else {
                loadPage(1)
            }
        }
        checkForUpdate()
    }

    override fun close() {
        pageSaveJob?.cancel()
        val pending = pendingPageSave
        pendingPageSave = null
        if (pending != null) {
            runBlocking(Dispatchers.IO) {
                repository.saveEpisodePage(pending.first, pending.second, pending.third)
            }
        }
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

    fun setEpisodeSortOrder(order: EpisodeSortOrder) {
        val targetSourceId = _state.value.series?.sourceId
            ?: _state.value.activeSourceId
            ?: sourceSettings.activeSourceId()
            ?: WorkId.LOCAL_SOURCE
        setSourceEpisodeSortOrder(targetSourceId, order)
    }

    fun getSourceEpisodeSortOrder(sourceId: String): EpisodeSortOrder =
        sourceSettings.episodeSortOrder(sourceId)

    fun setSourceEpisodeSortOrder(sourceId: String, order: EpisodeSortOrder) {
        sourceSettings.setEpisodeSortOrder(sourceId, order)
        _state.update { current ->
            val isCurrentSeriesSource = current.series?.sourceId == sourceId
            val isCurrentActiveSource = current.series == null &&
                (current.activeSourceId ?: sourceSettings.activeSourceId() ?: WorkId.LOCAL_SOURCE) == sourceId
            if (isCurrentSeriesSource || isCurrentActiveSource) {
                current.copy(
                    episodeSortOrder = order,
                    episodes = if (isCurrentSeriesSource) (current.rawEpisodes.ifEmpty { current.episodes }).sortedWithOrder(order) else current.episodes,
                )
            } else {
                current
            }
        }
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
                if (externalReaderSession == null) {
                    lastViewedEpisode = current.currentEpisode
                }
                closeReader()
                true
            }
            AppScreen.Series -> {
                lastViewedSeries = current.series
                lastViewedEpisodePage = current.episodePage
                val prev = previousScreenBeforeSeries
                if (prev == AppScreen.History) {
                    openHistory()
                } else if (prev == AppScreen.Downloads) {
                    openDownloads()
                } else {
                    closeSeries()
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
                val targetSeries = lastViewedSeries ?: current.items.firstOrNull()
                if (targetSeries != null) {
                    openSeries(targetSeries)
                    if (lastViewedEpisodePage > 1) {
                        goToEpisodePage(lastViewedEpisodePage)
                    }
                    true
                } else false
            }
            AppScreen.Series -> {
                val targetEp = lastViewedEpisode
                    ?: current.seriesHistory?.let { h -> current.episodes.firstOrNull { it.wrId == h.lastWrId } }
                    ?: (current.rawEpisodes.ifEmpty { current.episodes }).lastOrNull()
                    ?: current.episodes.firstOrNull()
                if (targetEp != null) {
                    openEpisode(targetEp)
                    true
                } else false
            }
            AppScreen.Reader -> {
                if (current.hasNextEpisode) {
                    openNextEpisode()
                    true
                } else false
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
            applyActiveSource(prefActive, loadListing = false)
        }
        if (_state.value.activeSourceId != null && _state.value.items.isEmpty() && loadJob?.isActive != true) {
            loadPage(1)
        }
    }

    private fun applyActiveSource(sourceId: String?, loadListing: Boolean) {
        val source = sourceId?.let { repository.activeSource(it) }
        if (source == null) {
            readerJob?.cancel()
            episodeJob?.cancel()
            lastViewedSeries = null
            lastViewedEpisode = null
        }
        val tabs = source?.let { BrowseTab.forSource(it) }.orEmpty()
        val nextTab = BrowseTab.afterSourceChange(_state.value.tab, tabs)
        _state.update {
            it.copy(
                activeSourceId = source?.id,
                installedSources = repository.installedSources(),
                browseTabs = tabs,
                tab = nextTab,
                items = if (source == null) emptyList() else it.items,
                loading = source != null && loadListing,
                error = if (source == null) null else it.error,
                searchSuggestions = emptyList(),
                sourceRegistry = repository.sourceRegistry(),
                libraryRoots = sourceSettings.libraryRoots(),
                screen = if (source == null) AppScreen.Browse else it.screen,
                series = if (source == null) null else it.series,
                episodeSortOrder = if (source != null) sourceSettings.episodeSortOrder(source.id) else it.episodeSortOrder,
            )
        }
        if (source == null || nextTab == null) return
        if (loadListing) {
            selectTab(nextTab, force = true)
        }
    }

    fun checkForUpdate(manual: Boolean = false, onFeedback: ((String) -> Unit)? = null) {
        if (_state.value.updateState.isChecking) return
        scope.launch {
            _state.update { it.copy(updateState = it.updateState.copy(isChecking = true)) }
            val currentVersionName = platform.appVersionName
            val strings = I18n.strings(_state.value.appLanguage)
            val serverUrl = _state.value.syncState.serverUrl.ifBlank { SyncConstants.DEFAULT_SERVER_URL }

            val updateState = platform.checkForAppUpdate(serverUrl, strings)
            val newServerUrl = updateState.newServerUrl
            if (newServerUrl != null) {
                repository.syncManager?.updateServerUrlIfChanged(newServerUrl)
            }
            _state.update {
                it.copy(
                    updateState = updateState.copy(isChecking = false),
                    showUpdateDialog = if (manual && updateState.hasUpdate) true else it.showUpdateDialog,
                )
            }
            if (manual) {
                if (updateState.error != null && !platform.isPlayStoreBuild) {
                    onFeedback?.invoke(updateState.error ?: strings.msgUpdateCheckFailed)
                } else if (!updateState.hasUpdate) {
                    onFeedback?.invoke(strings.msgLatestVersionInUse(currentVersionName))
                }
            }
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
        scope.launch {
            _state.update {
                it.copy(
                    updateState = it.updateState.copy(
                        isDownloading = true,
                        downloadProgress = 0f,
                        error = null,
                    ),
                )
            }
            val result = platform.downloadAndInstallUpdate(updateUrl) { progress ->
                _state.update { it.copy(updateState = it.updateState.copy(downloadProgress = progress)) }
            }
            result.fold(
                onSuccess = {
                    _state.update { it.copy(updateState = it.updateState.copy(isDownloading = false)) }
                },
                onFailure = { err ->
                    val strings = I18n.strings(_state.value.appLanguage)
                    val detail = err.localizedMessage ?: err.message
                    val errorMsg = if (detail.isNullOrBlank()) strings.msgUpdateDownloadFailed else "${strings.msgUpdateDownloadFailed}: $detail"
                    _state.update {
                        it.copy(
                            updateState = it.updateState.copy(
                                isDownloading = false,
                                error = errorMsg,
                            ),
                        )
                    }
                },
            )
        }
    }

    fun handleNotificationLaunch(targetTab: String? = null) {
        readerJob?.cancel()
        episodeJob?.cancel()
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
        syncOnBrowseRefresh()
    }

    fun selectTab(tab: BrowseTab) = selectTab(tab, force = false)

    private fun selectTab(tab: BrowseTab, force: Boolean) {
        if (
            !force &&
            tab == _state.value.tab &&
            _state.value.screen == AppScreen.Browse &&
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
                screen = AppScreen.Browse,
                series = null,
                rawEpisodes = emptyList(),
                episodes = emptyList(),
                error = null,
                searchQuery = null,
                searchInput = if (platform.collapseSearchClearsActiveSearch) it.searchInput else "",
                searchExpanded = false,
                currentEpisode = null,
                readerImages = emptyList(),
                readerError = null,
                scrollToTopTrigger = it.scrollToTopTrigger + 1,
            )
        }
        if (cached == null) {
            loadPage(cachedPage, replace = true)
        } else {
            scope.launch {
                val fresh = repository.refreshProgress(cached)
                catalogCache[tab] = fresh
                if (_state.value.tab == tab && _state.value.screen == AppScreen.Browse && !_state.value.isSearch) {
                    _state.update { it.copy(items = fresh) }
                }
                triggerEpisodeCountSync(fresh)
            }
        }
    }

    fun setActiveSource(sourceId: String) {
        val installed = sourceSettings.installedIds()
        if (sourceId !in installed) return
        sourceSettings.setActiveSourceId(sourceId)
        platform.onSourceWriteAccessChanged()
        applyActiveSource(sourceId, loadListing = true)
        if (_state.value.screen == AppScreen.History) {
            loadHistory()
        }
        if (_state.value.screen == AppScreen.Downloads) {
            loadDownloads()
        }
    }

    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        val id = sourceId.trim()
        if (id.isEmpty()) return
        if (!enabled) {
            uninstallSource(id)
            return
        }
        if (id == WorkId.LOCAL_SOURCE) {
            ensureLocalSourceLoaded()
        }
        sourceSettings.setEnabled(id, true)
        platform.onSourceWriteAccessChanged()
        _state.update {
            it.copy(
                installedSources = repository.installedSources(),
                libraryRoots = sourceSettings.libraryRoots(),
            )
        }
    }

    fun openAddSourceSheet() {
        _state.update {
            it.copy(
                showAddSourceSheet = true,
                showRemoveSourceSheet = false,
            )
        }
    }

    fun dismissAddSourceSheet() {
        _state.update { it.copy(showAddSourceSheet = false) }
    }

    fun closeAddSourceSheet() = dismissAddSourceSheet()

    fun openNetworkSourceDialog(protocol: NetworkProtocol) {
        val defaultName = if (protocol == NetworkProtocol.SMB) "SMB 저장소" else "WebDAV 저장소"
        _state.update {
            it.copy(
                networkDraft = NetworkSourceConfig(protocol = protocol, name = defaultName),
                networkTesting = false,
                networkTestMessage = null,
                networkTestSucceeded = false,
                showAddSourceSheet = false,
            )
        }
    }

    fun updateNetworkDraft(draft: NetworkSourceConfig) {
        _state.update {
            it.copy(
                networkDraft = draft,
                networkTestMessage = null,
                networkTestSucceeded = false,
            )
        }
    }

    fun closeNetworkSourceDialog() {
        _state.update {
            it.copy(
                networkDraft = null,
                networkTesting = false,
                networkTestMessage = null,
                networkTestSucceeded = false,
            )
        }
    }

    fun testNetworkConnection() {
        val draft = _state.value.networkDraft ?: return
        scope.launch {
            _state.update { it.copy(networkTesting = true, networkTestMessage = null, networkTestSucceeded = false) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val valid = draft.validated()
                    createNetworkFileSystem(valid).use { it.test() }
                    valid
                }
            }
            _state.update {
                if (it.networkDraft?.id != draft.id) it else it.copy(
                    networkTesting = false,
                    networkTestMessage = result.fold(
                        onSuccess = { "연결에 성공했습니다" },
                        onFailure = { error -> error.message?.ifBlank { null } ?: "연결에 실패했습니다" },
                    ),
                    networkTestSucceeded = result.isSuccess,
                    networkDraft = result.getOrNull() ?: it.networkDraft,
                )
            }
        }
    }

    fun registerNetworkConnection() {
        val current = _state.value
        val draft = current.networkDraft ?: return
        if (!current.networkTestSucceeded) return
        scope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    val valid = draft.validated()
                    networkSourceStore.save(valid)
                    networkSourceStore.createSource(valid)
                }
                val registry = repository.sourceRegistry()
                registry.remove(source.id)
                check(registry.add(source)) { "연결을 등록할 수 없습니다" }
                sourceSettings.setInstalledIds(sourceSettings.installedIds() + source.id)
                platform.onSourceWriteAccessChanged()
                clearCatalogCache(source.id)
                closeNetworkSourceDialog()
                setActiveSource(source.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(networkTesting = false, networkTestMessage = e.message ?: "등록에 실패했습니다")
                }
            }
        }
    }

    fun openRemoveSourceSheet() {
        if (_state.value.installedSources.none { it.id != WorkId.LOCAL_SOURCE }) return
        _state.update { it.copy(showRemoveSourceSheet = true, showAddSourceSheet = false) }
    }

    fun closeRemoveSourceSheet() {
        _state.update { it.copy(showRemoveSourceSheet = false) }
    }

    fun dismissSourceError() {
        _state.update { it.copy(sourceError = null) }
    }

    fun setSourceError(message: String) {
        _state.update { it.copy(sourceError = message) }
    }

    fun importJsScript(script: String, fileName: String = "source.js") {
        scope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    jsPackStore.ingest(script, fileName)
                }
                val registry = repository.sourceRegistry()
                registry.remove(source.id)
                check(registry.add(source)) { "failed to register ${source.id}" }
                sourceSettings.setInstalledIds(sourceSettings.installedIds() + source.id)
                platform.onSourceWriteAccessChanged()
                clearCatalogCache(source.id)
                closeAddSourceSheet()
                setActiveSource(source.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(sourceError = e.message?.ifBlank { null } ?: "가져오기에 실패했습니다")
                }
            }
        }
    }

    fun uninstallSource(sourceId: String) {
        val id = sourceId.trim()
        if (id.isEmpty() || id == WorkId.LOCAL_SOURCE) return
        jsPackStore.delete(id)
        networkSourceStore.delete(id)
        repository.sourceRegistry().remove(id)
        sourceSettings.setEnabled(id, false)
        platform.onSourceWriteAccessChanged()
        clearCatalogCache(id)
        closeRemoveSourceSheet()
        if (_state.value.activeSourceId == id) {
            applyActiveSource(WorkId.LOCAL_SOURCE, loadListing = true)
        } else {
            _state.update {
                it.copy(
                    installedSources = repository.installedSources(),
                    sourceRegistry = repository.sourceRegistry(),
                    libraryRoots = sourceSettings.libraryRoots(),
                )
            }
        }
    }

    fun removeActiveSource() {
        val id = _state.value.activeSourceId ?: return
        if (id == WorkId.LOCAL_SOURCE) return
        uninstallSource(id)
    }

    fun getSourceProgressDisplayMode(sourceId: String): ProgressDisplayMode =
        sourceSettings.progressDisplayMode(sourceId)

    fun requestRemoveSource(sourceId: String) {
        if (sourceId == WorkId.LOCAL_SOURCE) return
        uninstallSource(sourceId)
    }

    fun requestRemoveSource(source: ComicSource) {
        uninstallSource(source.id)
    }

    fun getNetworkSourceConfig(sourceId: String): NetworkSourceConfig? {
        val direct = (repository.sourceRegistry().getOrNull(sourceId) as? NetworkLibrarySource)?.config
        if (direct != null) return direct
        return networkSourceStore.all().firstOrNull { it.id == sourceId }
    }

    fun updateNetworkSourceConfig(
        config: NetworkSourceConfig,
        onResult: (Boolean, String?) -> Unit,
    ) {
        scope.launch {
            try {
                val valid = withContext(Dispatchers.IO) {
                    val v = config.validated()
                    NetworkSourceRuntime.remove(v.id)
                    networkSourceStore.save(v)
                    v
                }
                val newSource = networkSourceStore.createSource(valid)
                val registry = repository.sourceRegistry()
                registry.remove(valid.id)
                registry.add(newSource)
                clearCatalogCache(valid.id)
                _state.update {
                    it.copy(
                        installedSources = repository.installedSources(),
                        sourceRegistry = repository.sourceRegistry(),
                    )
                }
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message ?: "네트워크 설정 저장에 실패했습니다")
            }
        }
    }

    fun getJsSourceScript(sourceId: String): String? {
        if (sourceId == WorkId.LOCAL_SOURCE || sourceId.startsWith("network-")) return null
        val file = runCatching { jsPackStore.fileFor(sourceId) }.getOrNull() ?: return null
        return if (file.isFile) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        } else null
    }

    fun updateJsSourceScript(
        sourceId: String,
        newScript: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        scope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    val bytes = newScript.toByteArray(Charsets.UTF_8)
                    JsPackStore.checkSize(bytes.size)
                    val s = JsComicSource.fromScript(newScript, "$sourceId.js")
                    jsPackStore.copy(sourceId, s.hostApiLevel, bytes)
                    s
                }
                val registry = repository.sourceRegistry()
                registry.remove(sourceId)
                registry.add(source)
                clearCatalogCache(sourceId)
                _state.update {
                    it.copy(
                        installedSources = repository.installedSources(),
                        sourceRegistry = repository.sourceRegistry(),
                    )
                }
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message ?: "JS 스크립트 저장에 실패했습니다")
            }
        }
    }

    fun openAddSmb() {
        openNetworkSourceDialog(NetworkProtocol.SMB)
    }

    fun openAddWebDav() {
        openNetworkSourceDialog(NetworkProtocol.WEBDAV)
    }

    fun requestAddLocalFolder() {
        _state.update { it.copy(pendingLibraryTreePick = true) }
    }

    fun consumeLibraryTreePick() {
        _state.update { it.copy(pendingLibraryTreePick = false) }
    }

    fun addLibraryRootPath(key: String, refreshForce: Boolean = false) {
        if (_state.value.activeSourceId != WorkId.LOCAL_SOURCE) return
        platform.onLocalFolderAdded(key)
        val current = sourceSettings.libraryRoots()
        if (key !in current) {
            sourceSettings.setLibraryRoots(current + key)
        }
        _state.update {
            it.copy(
                libraryRoots = sourceSettings.libraryRoots(),
                pendingLibraryTreePick = false,
            )
        }
        catalogCache.clear()
        catalogPages.clear()
        refresh(force = refreshForce)
    }

    fun addLibraryRootFromPicker() {
        if (_state.value.activeSourceId != WorkId.LOCAL_SOURCE) return
        val dir = platform.pickLibraryFolder() ?: return
        val path = try {
            dir.canonicalPath
        } catch (_: Exception) {
            dir.absolutePath
        }
        if (!File(path).isDirectory) return
        addLibraryRootPath(path, refreshForce = false)
    }

    fun removeLibraryRoot(key: String, refreshForce: Boolean = false) {
        if (_state.value.activeSourceId != WorkId.LOCAL_SOURCE) return
        platform.onLocalFolderRemoved(key)
        sourceSettings.setLibraryRoots(sourceSettings.libraryRoots().filterNot { it == key })
        _state.update { it.copy(libraryRoots = sourceSettings.libraryRoots()) }
        catalogCache.clear()
        catalogPages.clear()
        refresh(force = refreshForce)
    }

    private fun ensureLocalSourceLoaded() {
        val registry = repository.sourceRegistry()
        if (registry.contains(WorkId.LOCAL_SOURCE)) return
        registry.add(
            LocalSource(
                roots = { platform.materializeLocalRoots() },
                index = LibraryScanIndex(platform.localLibraryIndexFile),
            ),
        )
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
        val parent = repository.sourceOrNull(request.item.sourceId)
            ?.resolveParent(request.item, artist, request.entryEpisodeId)
        if (parent != null) {
            openSeries(parent)
        }
    }

    fun toggleSearchBar() {
        val current = _state.value
        if (current.searchExpanded) {
            if (platform.collapseSearchClearsActiveSearch && current.isSearch) {
                current.tab?.let { selectTab(it) }
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

    fun applySearchSuggestion(suggestion: SearchSuggestion) {
        updateSearchInput(suggestion.applyTo(_state.value.searchInput))
    }

    private fun requestSuggestions(value: String) {
        suggestJob?.cancel()
        val sourceId = _state.value.activeSourceId
        if (value.isBlank() || sourceId == null) {
            _state.update { it.copy(searchSuggestions = emptyList()) }
            return
        }
        suggestJob = scope.launch {
            delay(200)
            try {
                val items = repository.suggest(value, sourceId)
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

    fun submitSearch(query: String = _state.value.searchInput) {
        if (_state.value.activeSourceId == null) return
        val q = query.trim()
        if (q.isEmpty()) return
        readerJob?.cancel()
        episodeJob?.cancel()
        _state.update {
            it.copy(
                searchQuery = q,
                searchInput = q,
                searchSuggestions = emptyList(),
                screen = AppScreen.Browse,
                series = null,
                rawEpisodes = emptyList(),
                episodes = emptyList(),
                items = emptyList(),
                page = 1,
                lastPage = 1,
                error = null,
                loading = true,
                currentEpisode = null,
                readerImages = emptyList(),
                readerError = null,
                scrollToTopTrigger = it.scrollToTopTrigger + 1,
            )
        }
        runSearch(q, asRefresh = false)
    }

    fun refresh(force: Boolean = false) {
        checkForUpdate()
        platform.clearImageFailures()
        if (!force && _state.value.screen == AppScreen.Reader && !platform.refreshReaderUsesAsRefresh) {
            refreshReaderImages()
            return
        }
        _state.update { it.copy(refreshEpoch = it.refreshEpoch + 1) }
        if (_state.value.activeSourceId == null) return
        if (force) {
            catalogCache.clear()
            catalogPages.clear()
        }
        val current = _state.value
        if (current.screen == AppScreen.Reader) {
            val episode = current.currentEpisode ?: return
            loadReaderImages(episode, asRefresh = true)
            return
        }
        if (current.screen == AppScreen.Series) {
            val series = current.series ?: return
            loadEpisodes(series, current.episodePage, asRefresh = platform.refreshReaderUsesAsRefresh)
            return
        }
        if (current.screen == AppScreen.Browse) {
            syncOnBrowseRefresh()
        }
        if (current.isSearch) {
            val q = current.searchQuery.orEmpty()
            if (q.isNotEmpty()) runSearch(q, asRefresh = platform.refreshReaderUsesAsRefresh && current.items.isNotEmpty())
            return
        }
        if (!force && !platform.refreshReaderUsesAsRefresh) {
            current.tab?.let { catalogCache.remove(it) }
        }
        val targetPage = if (force) 1 else current.page
        val asRefresh = if (platform.refreshReaderUsesAsRefresh) (!force && current.items.isNotEmpty()) else true
        loadPage(targetPage, replace = true, asRefresh = asRefresh)
    }

    fun refreshReaderImages() {
        platform.clearImageFailures()
        platform.clearImageMemory(ImageCacheRole.READER)
        _state.update { it.copy(refreshEpoch = it.refreshEpoch + 1) }
        if (externalReaderSession != null) return
        val episode = _state.value.currentEpisode ?: return
        loadReaderImages(episode, asRefresh = false)
    }

    private fun syncOnBrowseRefresh() {
        val sm = repository.syncManager ?: return
        scope.launch {
            val res = sm.syncIfIdle() ?: return@launch
            if (res.success && (res.favoritesCount > 0 || res.historyCount > 0 || res.episodesCount > 0)) {
                val now = _state.value
                if (now.screen == AppScreen.Browse && !now.isSearch) {
                    loadPage(page = now.page, replace = true)
                }
            }
        }
    }

    fun goToPage(page: Int) {
        val current = _state.value
        if (current.tab?.paginated != true) return
        val target = ReaderDomain.clampPage(page, current.lastPage)
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

    fun nextEpisodePage() {
        val current = _state.value
        if (current.episodePage < current.episodeLastPage) goToEpisodePage(current.episodePage + 1)
    }

    fun prevEpisodePage() {
        val current = _state.value
        if (current.episodePage > 1) goToEpisodePage(current.episodePage - 1)
    }

    fun togglePageJump(show: Boolean) {
        _state.update { it.copy(showPageJump = show) }
    }

    fun updateViewportSize(widthDp: Int, heightDp: Int) {
        currentScreenWidthDp = widthDp
        currentScreenHeightDp = heightDp
        if (platform.persistGlobalReaderDefaults) {
            val targetMode = resolveTargetViewMode(widthDp, heightDp)
            _state.update {
                it.copy(
                    viewportWidthDp = widthDp,
                    viewportHeightDp = heightDp,
                    viewMode = targetMode,
                    readDirection = globalReadDirection,
                )
            }
        } else {
            _state.update {
                if (it.viewportWidthDp == widthDp && it.viewportHeightDp == heightDp) {
                    it
                } else {
                    it.copy(viewportWidthDp = widthDp, viewportHeightDp = heightDp)
                }
            }
        }
    }

    fun openSeries(item: ToonItem) {
        val currentScreen = _state.value.screen
        if (currentScreen != AppScreen.Series && currentScreen != AppScreen.Reader) {
            previousScreenBeforeSeries = currentScreen
        }
        lastViewedSeries = item
        platform.cancelPendingImagePreviews()
        platform.clearImageMemory(ImageCacheRole.EPISODE)
        episodeJob?.cancel()
        val sortOrder = sourceSettings.episodeSortOrder(item.sourceId)
        _state.update {
            it.copy(
                screen = AppScreen.Series,
                series = item,
                seriesHistory = null,
                rawEpisodes = emptyList(),
                episodes = emptyList(),
                episodeSortOrder = sortOrder,
                episodePage = 1,
                episodeLastPage = 1,
                episodeError = null,
                seriesFavorited = false,
                highlightedEpisodeId = item.entryEpisodeId,
                viewMode = if (platform.persistGlobalReaderDefaults) resolveTargetViewMode() else it.viewMode,
                readDirection = if (platform.persistGlobalReaderDefaults) globalReadDirection else it.readDirection,
                downloadedWrIds = emptySet(),
            )
        }
        loadEpisodes(item, 1)
        scope.launch {
            val favorited = repository.isFavorite(item.workId())
            val history = repository.getHistoryRecord(item.workId())
            val readCount = countIfNeeded(item.workId())
            val catalogTotal = repository.getCatalogTotal(item.workId())
            val itemKey = item.workId().storageKey()
            _state.update { current ->
                val nextTotals = if (catalogTotal != null && current.catalogTotals[itemKey] == null) {
                    current.catalogTotals + (itemKey to catalogTotal)
                } else {
                    current.catalogTotals
                }
                if (current.series?.workId() == item.workId()) {
                    current.copy(
                        seriesFavorited = favorited,
                        seriesHistory = history,
                        readCounts = current.readCounts.withCount(item.workId(), readCount),
                        catalogTotals = nextTotals,
                    )
                } else {
                    current.copy(catalogTotals = nextTotals)
                }
            }
        }
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
                    current.screen == AppScreen.Browse &&
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
                    current.tab?.let { catalogCache[it] = visible }
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
        continueHistoryEpisode(hist)
    }

    fun openFirstEpisode() {
        val current = _state.value
        val series = current.series ?: return
        val lastPage = current.episodeLastPage
        val raw = current.rawEpisodes.ifEmpty { current.episodes }
        val seriesKey = series.workId().storageKey()
        if (lastPage <= 1) {
            val firstEp = raw.lastOrNull()
            if (firstEp != null) {
                openEpisode(firstEp)
                return
            }
        }
        scope.launch {
            try {
                val metadata = repository.fetchEpisodeMetadata(series)
                repository.saveCatalogTotal(series.workId(), metadata.total)

                val result = repository.loadEpisodes(series, lastPage.coerceAtLeast(1))
                _state.update {
                    it.copy(
                        rawEpisodes = result.items,
                        episodes = result.items.sortedWithOrder(it.episodeSortOrder),
                        episodePage = result.currentPage,
                        episodeLastPage = result.lastPage,
                        catalogTotals = metadata.total?.let { t -> it.catalogTotals + (seriesKey to t) } ?: it.catalogTotals,
                    )
                }
                result.items.lastOrNull()?.let(::openEpisode)
            } catch (_: Exception) {
            }
        }
    }

    fun closeSeries() {
        platform.cancelPendingImagePreviews()
        platform.clearImageMemory(ImageCacheRole.EPISODE)
        readerJob?.cancel()
        episodeJob?.cancel()
        val current = _state.value
        current.series?.sourceId?.let { NetworkSourceRuntime.disconnect(it) }
        lastViewedSeries = current.series
        lastViewedEpisodePage = current.episodePage
        val tab = current.tab
        val searching = current.isSearch
        _state.update {
            it.copy(
                screen = AppScreen.Browse,
                series = null,
                seriesHistory = null,
                rawEpisodes = emptyList(),
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
        if (tab != null && !searching) {
            val cached = catalogCache[tab]
            if (cached != null) {
                scope.launch {
                    val fresh = repository.refreshProgress(cached)
                    catalogCache[tab] = fresh
                    if (_state.value.tab == tab && _state.value.screen == AppScreen.Browse && !_state.value.isSearch) {
                        _state.update { it.copy(items = fresh) }
                    }
                }
            } else if (tab is BrowseTab.Favorite) {
                loadPage(1, replace = true)
            }
        }
    }

    fun openHistory() {
        if (_state.value.screen == AppScreen.Series) {
            platform.clearImageMemory(ImageCacheRole.EPISODE)
        }
        readerJob?.cancel()
        episodeJob?.cancel()
        _state.update {
            it.copy(
                screen = AppScreen.History,
                historyLoading = true,
            )
        }
        loadHistory()
    }

    fun closeHistory() {
        _state.update { it.copy(screen = AppScreen.Browse) }
    }

    fun loadHistory() {
        val sourceId = _state.value.activeSourceId
        if (sourceId == null) {
            _state.update { it.copy(historyLoading = false, historyItems = emptyList()) }
            return
        }
        scope.launch {
            _state.update { it.copy(historyLoading = it.historyItems.isEmpty()) }
            val items = repository.getHistoryRecords(sourceId)
            val workIds = items.map { item -> item.workId() }
            val counts = countIfNeeded(workIds, sourceId)
            val totals = repository.getCatalogTotals(workIds)
            _state.update {
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

    fun openDownloadOptions() {
        if (!_state.value.writesDownloads) return
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
                        downloadCatalog = current.rawEpisodes.ifEmpty { current.episodes },
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
        if (!_state.value.writesDownloads) return
        val series = _state.value.series ?: return
        closeDownloadOptions()
        repository.downloadManager?.enqueueEpisodes(series, episodesToDownload)
    }

    fun startDownloadEpisode(episode: EpisodeItem) {
        if (!_state.value.writesDownloads) return
        val series = _state.value.series ?: return
        repository.downloadManager?.enqueueEpisodes(series, listOf(episode))
    }

    private fun refreshDownloadedWrIds() {
        val series = _state.value.series ?: return
        scope.launch {
            val ids = repository.downloadManager
                ?.getDownloadedEpisodeRecords(series.workId())
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
        if (!_state.value.writesDownloads) return
        if (_state.value.screen == AppScreen.Series) {
            platform.clearImageMemory(ImageCacheRole.EPISODE)
        }
        readerJob?.cancel()
        episodeJob?.cancel()
        _state.update {
            it.copy(
                screen = AppScreen.Downloads,
                downloadLoading = true,
            )
        }
        loadDownloads()
    }

    fun closeDownloads() {
        _state.update { it.copy(screen = AppScreen.Browse) }
    }

    fun openSettings(category: SettingsCategory? = null) {
        readerJob?.cancel()
        episodeJob?.cancel()
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

    fun toggleNotificationEnabled(enabled: Boolean) {
        platform.setNotificationEnabled(enabled)
        platform.scheduleBackgroundRefresh(enabled, _state.value.notificationIntervalMinutes)
        _state.update { it.copy(notificationEnabled = enabled) }
    }

    fun setNotificationIntervalMinutes(minutes: Long) {
        platform.setNotificationIntervalMinutes(minutes)
        if (_state.value.notificationEnabled) {
            platform.scheduleBackgroundRefresh(true, minutes)
        }
        _state.update { it.copy(notificationIntervalMinutes = minutes) }
    }

    fun toggleSourceNotification(sourceId: String, enabled: Boolean) {
        sourceSettings.setNotificationEnabled(sourceId, enabled)
        _state.update {
            it.copy(notificationSources = it.notificationSources + (sourceId to enabled))
        }
    }

    fun getSourceNotificationEnabled(sourceId: String): Boolean =
        sourceSettings.isNotificationEnabled(sourceId)

    fun checkNotificationPermission() {
        val granted = platform.checkNotificationPermission()
        _state.update { it.copy(hasNotificationPermission = granted) }
    }

    fun closeSettings() {
        if (_state.value.selectedSettingsCategory != null) {
            _state.update { it.copy(selectedSettingsCategory = null) }
        } else {
            _state.update { it.copy(screen = AppScreen.Browse) }
        }
    }

    fun openSourceManager() {
        readerJob?.cancel()
        episodeJob?.cancel()
        _state.update { it.copy(screen = AppScreen.SourceManager) }
    }

    fun closeSourceManager() {
        _state.update { it.copy(screen = AppScreen.Browse) }
    }

    fun reorderStorageSources(fromIndex: Int, toIndex: Int) {
        val current = _state.value.installedSources
        val networkStorage = current.filter { it.resolveSourceType().isStorage && it.id != WorkId.LOCAL_SOURCE }
        if (fromIndex !in networkStorage.indices || toIndex !in networkStorage.indices) return
        val mutable = networkStorage.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)

        val online = current.filterNot { it.resolveSourceType().isStorage }
        val newIds = linkedSetOf<String>().apply {
            add(WorkId.LOCAL_SOURCE)
            mutable.forEach { add(it.id) }
            online.forEach { add(it.id) }
        }
        sourceSettings.setInstalledIds(newIds)
        _state.update { it.copy(installedSources = repository.installedSources()) }
    }

    fun reorderOnlineSources(fromIndex: Int, toIndex: Int) {
        val current = _state.value.installedSources
        val online = current.filterNot { it.resolveSourceType().isStorage }
        if (fromIndex !in online.indices || toIndex !in online.indices) return
        val mutable = online.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)

        val storage = current.filter { it.resolveSourceType().isStorage && it.id != WorkId.LOCAL_SOURCE }
        val newIds = linkedSetOf<String>().apply {
            add(WorkId.LOCAL_SOURCE)
            storage.forEach { add(it.id) }
            mutable.forEach { add(it.id) }
        }
        sourceSettings.setInstalledIds(newIds)
        _state.update { it.copy(installedSources = repository.installedSources()) }
    }

    fun setSourceProgressDisplayMode(sourceId: String, mode: ProgressDisplayMode) {
        sourceSettings.setProgressDisplayMode(sourceId, mode)
        val current = _state.value
        scope.launch {
            val updated = repository.refreshProgress(current.items)
            current.tab?.let { tab ->
                catalogCache[tab] = updated
            }
            val historyCounts = if (current.historyItems.isNotEmpty()) {
                countIfNeeded(current.historyItems.map { it.workId() }, sourceId)
            } else emptyMap()
            val targetSeries = current.series?.takeIf { it.sourceId == sourceId }
            val seriesCount = if (targetSeries != null) countIfNeeded(targetSeries.workId()) else null
            _state.update { state ->
                val nextCounts = if (targetSeries != null && seriesCount != null) {
                    (state.readCounts + historyCounts).withCount(targetSeries.workId(), seriesCount)
                } else {
                    state.readCounts + historyCounts
                }
                state.copy(items = updated, readCounts = nextCounts)
            }
        }
    }

    fun refreshCurrentListingProgress() {
        val current = _state.value
        if (current.activeSourceId == null) return
        scope.launch {
            val updated = repository.refreshProgress(current.items)
            current.tab?.let { tab ->
                catalogCache[tab] = updated
            }
            _state.update { it.copy(items = updated) }
        }
    }

    fun loadDownloads() {
        val sourceId = _state.value.activeSourceId
        if (sourceId == null) {
            _state.update { it.copy(downloadLoading = false, downloadSummaries = emptyList()) }
            return
        }
        scope.launch {
            _state.update { it.copy(downloadLoading = it.downloadSummaries.isEmpty()) }
            val items = repository.downloadManager
                ?.getDownloadedToonSummaries(sourceId)
                .orEmpty()
            _state.update {
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
                    readCounts = current.readCounts - workId.storageKey(),
                )
            }
        }
    }

    fun clearAllHistory() {
        val sourceId = _state.value.activeSourceId ?: return
        scope.launch {
            repository.clearHistory(sourceId)
            _state.update { current ->
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

    private suspend fun applySyncRefresh() {
        catalogCache.clear()
        catalogPages.clear()
        if (_state.value.activeSourceId != null) {
            loadPage(_state.value.page, replace = true)
        }
        val sourceId = _state.value.activeSourceId ?: return
        val updatedHistory = repository.getHistoryRecords(sourceId)
        val counts = countIfNeeded(updatedHistory.map { item -> item.workId() }, sourceId)
        val seriesItem = _state.value.series
        val seriesFav = seriesItem?.let { repository.isFavorite(it.workId()) } ?: _state.value.seriesFavorited
        _state.update {
            it.copy(
                historyItems = updatedHistory,
                readCounts = it.readCounts + counts,
                seriesFavorited = seriesFav,
            )
        }
        if (_state.value.screen == AppScreen.History) {
            loadHistory()
        }
    }

    fun syncNow() {
        syncActions.syncNow()
    }

    fun restoreWithMasterKey(key: String, onResult: (SyncResult) -> Unit) {
        syncActions.restore(key, onResult)
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

    fun setProxyType(type: ProxyType) {
        val next = _state.value.networkSettings.copy(proxyType = type)
        NetworkSettingsStorage.save(next) { k, v -> prefs.putString(k, v) }
        _state.update { it.copy(networkSettings = next, proxyTestResult = null, proxyTestSuccess = null) }
        val serverUrl = repository.syncManager?.syncState?.value?.serverUrl ?: SyncConstants.DEFAULT_SERVER_URL
        repository.client.applyNetworkSettings(next, serverUrl)
    }

    fun setCustomProxy(config: CustomProxyConfig) {
        val next = _state.value.networkSettings.copy(customProxy = config)
        NetworkSettingsStorage.save(next) { k, v -> prefs.putString(k, v) }
        _state.update { it.copy(networkSettings = next, proxyTestResult = null, proxyTestSuccess = null) }
        val serverUrl = repository.syncManager?.syncState?.value?.serverUrl ?: SyncConstants.DEFAULT_SERVER_URL
        repository.client.applyNetworkSettings(next, serverUrl)
    }

    fun testProxyConnection(strings: AppStrings) {
        val current = _state.value.networkSettings
        _state.update { it.copy(proxyTesting = true, proxyTestResult = strings.statusTestingProxy, proxyTestSuccess = null) }
        scope.launch {
            val result = repository.client.testProxyConnection(current.proxyType, current.customProxy)
            result.onSuccess { latency ->
                _state.update { it.copy(proxyTesting = false, proxyTestResult = strings.statusProxySuccess(latency), proxyTestSuccess = true) }
            }.onFailure { err ->
                _state.update { it.copy(proxyTesting = false, proxyTestResult = strings.statusProxyFailed(err.message ?: "Error"), proxyTestSuccess = false) }
            }
        }
    }

    fun toggleServerProxy(enabled: Boolean) {
        repository.syncManager?.setUseServerProxy(enabled)
    }

    fun requestPairingCode(onResult: (PairRequestResult) -> Unit) {
        syncActions.requestPairingCode(onResult)
    }

    fun confirmPairingCode(code: String, onResult: (PairConfirmResult) -> Unit) {
        syncActions.confirmPairingCode(code, onResult)
    }

    fun exportBackupWithWriter(
        writeJson: suspend (String) -> Unit,
        onResult: (Boolean, String) -> Unit,
    ) {
        scope.launch {
            try {
                val json = repository.exportBackupJson()
                withContext(Dispatchers.IO) {
                    writeJson(json)
                }
                val stats = repository.getBackupStats()
                onResult(true, "백업 파일이 저장되었습니다. (즐겨찾기 ${stats.favoriteCount}개, 기록 ${stats.historyCount}개)")
            } catch (e: Exception) {
                onResult(false, "백업 저장 실패: ${e.localizedMessage ?: "알 수 없는 오류"}")
            }
        }
    }

    fun importBackupWithReader(
        readJson: suspend () -> String,
        onResult: (Boolean, String) -> Unit,
    ) {
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    readJson()
                }
                val result = repository.importBackupJson(json)
                if (result.success) {
                    if (_state.value.activeSourceId != null) {
                        loadPage(page = _state.value.page, replace = true)
                    }
                    val sourceId = _state.value.activeSourceId
                    if (sourceId != null) {
                        val updatedHistory = repository.getHistoryRecords(sourceId)
                        _state.update { it.copy(historyItems = updatedHistory) }
                    }
                    onResult(true, "복원 완료: 즐겨찾기 ${result.favoriteCount}개, 기록 ${result.historyCount}개")
                } else {
                    onResult(false, result.message)
                }
            } catch (e: Exception) {
                onResult(false, "복원 실패: ${e.localizedMessage ?: "파일 읽기 오류"}")
            }
        }
    }

    fun openSeriesFromHistory(item: ReadHistoryRecord) {
        openSeries(item.toToonItem())
    }

    fun continueHistoryEpisode(item: ReadHistoryRecord) {
        val toon = item.toToonItem()
        if (_state.value.screen == AppScreen.History) {
            previousScreenBeforeSeries = AppScreen.History
        }
        if (platform.persistGlobalReaderDefaults) {
            platform.clearImageMemory(ImageCacheRole.EPISODE)
        }
        episodeJob?.cancel()
        readerJob?.cancel()
        val sortOrder = sourceSettings.episodeSortOrder(toon.sourceId)
        _state.update {
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
                viewMode = if (platform.persistGlobalReaderDefaults) resolveTargetViewMode() else it.viewMode,
                readDirection = if (platform.persistGlobalReaderDefaults) globalReadDirection else it.readDirection,
            )
        }
        refreshSeriesFavorited(toon)
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

                _state.update {
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
                    openEpisode(targetEp.copy(lastReadPage = resumePage))
                } else {
                    val nextEp = when {
                        lastIdx > 0 -> epPage.items[lastIdx - 1]
                        lastIdx == 0 && actualPage > 1 -> {
                            try {
                                val prevEpPage = repository.loadEpisodes(toon, actualPage - 1)
                                _state.update {
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
                        openEpisode(nextEp.copy(lastReadPage = 0))
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
        episodeJob?.cancel()
        platform.cancelPendingImagePreviews()
        platform.clearImageMemory(ImageCacheRole.READER)
        val current = _state.value
        val series = current.series
        if (series != null) {
            lastViewedSeries = series
            lastViewedEpisodePage = current.episodePage
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
        _state.update { curr ->
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
            refreshSeriesFavorited(series)
            scope.launch {
                val savedSetting = repository.getReaderSetting(series.workId())
                if (savedSetting != null) {
                    _state.update {
                        it.copy(
                            viewMode = savedSetting.viewMode,
                            readDirection = savedSetting.readDirection,
                            splitMode = savedSetting.splitMode,
                        )
                    }
                }
                repository.markEpisodeRead(series.workId(), episode.wrId, episode.lastReadPage)
                val existingHistory = repository.getHistoryRecord(series.workId())

                val counted = _state.value.catalogTotals[seriesKey]
                val progress = RepositoryTransforms.historyProgress(
                    position.readOrder ?: existingHistory?.lastReadOrder ?: 1,
                    counted,
                )
                val finalOrder = progress.lastReadOrder
                val finalTotal = counted?.count ?: 0

                val (progressText, readCount) = listingProgress(
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
                _state.update { curr ->
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
        val current = _state.value
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
        _state.update {
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
        val current = _state.value
        if (externalReaderSession != null) {
            _state.update { state ->
                state.copy(currentEpisode = state.currentEpisode?.copy(lastReadPage = page))
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

        _state.update { state ->
            state.copy(currentEpisode = state.currentEpisode?.copy(lastReadPage = page))
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
            _state.value.series?.sourceId?.let { NetworkSourceRuntime.disconnect(it) }
            platform.clearImageMemory(ImageCacheRole.READER)
            restoreAutoSwitchedViewMode()
            _state.update {
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
        _state.value.series?.sourceId?.let { NetworkSourceRuntime.disconnect(it) }
        platform.clearImageMemory(ImageCacheRole.READER)
        val current = _state.value
        lastViewedEpisode = current.currentEpisode
        val restoredMode = if (platform.autoSwitchWebtoonInReader) restoreAutoSwitchedViewMode() else current.viewMode
        _state.update {
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
        _state.update { it.copy(isFullscreen = !it.isFullscreen) }
    }

    fun openNextEpisode() {
        val current = _state.value
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
        when (val navigation = ReaderDomain.nextEpisode(
            currentIndex = current.currentEpisodeIndex,
            itemCount = epList.size,
            currentPage = current.episodePage,
            sortOrder = current.episodeSortOrder,
        )) {
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
        val current = _state.value
        val series = current.series
        val epList = current.episodes.ifEmpty { current.rawEpisodes }
        when (val navigation = ReaderDomain.prevEpisode(
            currentIndex = current.currentEpisodeIndex,
            itemCount = epList.size,
            currentPage = current.episodePage,
            lastPage = current.episodeLastPage,
            sortOrder = current.episodeSortOrder,
        )) {
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
            _state.update {
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
        val current = _state.value
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
            singleViewPreference = mode
        }
        preAutoSwitchViewMode = null
        if (mode == ViewMode.AUTO) autoSwitchedEpisodeId = null
        _state.update { it.copy(viewMode = mode) }
        persistCurrentReaderSetting()
    }

    fun setReadDirection(direction: ReadDirection) {
        if (platform.persistGlobalReaderDefaults) {
            globalReadDirection = direction
        }
        _state.update { it.copy(readDirection = direction) }
        persistCurrentReaderSetting()
    }

    fun setSplitMode(mode: SplitMode) {
        _state.update { it.copy(splitMode = mode) }
        persistCurrentReaderSetting()
    }

    fun goToEpisodePage(page: Int) {
        val current = _state.value
        val series = current.series ?: return
        val target = ReaderDomain.clampPage(page, current.episodeLastPage)
        loadEpisodes(series, target)
    }

    fun loadPage(page: Int, replace: Boolean = false, asRefresh: Boolean = false) {
        val tab = _state.value.tab ?: return
        val targetPage = page.coerceAtLeast(1)
        if (!replace && loadJob?.isActive == true && _state.value.page == targetPage && _state.value.loading) {
            return
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.update {
                it.copy(
                    loading = if (asRefresh) (it.items.isEmpty() || !asRefresh) else (if (replace) it.items.isEmpty() else true),
                    refreshing = asRefresh || (replace && it.items.isNotEmpty() && !platform.refreshReaderUsesAsRefresh),
                    error = null,
                    page = targetPage,
                    showPageJump = false,
                )
            }
            try {
                val result = repository.loadListing(tab, targetPage)
                if (result.items.isNotEmpty() && (platform.markSeenOnFavoriteTab || tab !is BrowseTab.Favorite)) {
                    repository.markSeen(result.items)
                }
                catalogCache[tab] = result.items
                catalogPages[tab] = Pair(result.currentPage, result.lastPage)
                _state.update {
                    it.copy(
                        items = result.items,
                        page = result.currentPage,
                        lastPage = result.lastPage,
                        loading = false,
                        refreshing = false,
                        error = null,
                        scrollToTopTrigger = it.scrollToTopTrigger + 1,
                    )
                }
                triggerEpisodeCountSync(result.items)
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

    fun loadEpisodes(item: ToonItem, page: Int, asRefresh: Boolean = false) {
        episodeJob?.cancel()
        episodeJob = scope.launch {
            val local = if (page <= 1) {
                repository.loadLocalEpisodes(item)
            } else {
                EpisodePage(emptyList(), page, 1)
            }
            val hasLocal = local.items.isNotEmpty()
            val sortOrder = sourceSettings.episodeSortOrder(item.sourceId)
            _state.update {
                it.copy(
                    episodeSortOrder = sortOrder,
                    episodeLoading = !hasLocal && (it.episodes.isEmpty() || !asRefresh),
                    episodeError = null,
                    episodePage = if (hasLocal) 1 else page,
                    episodeLastPage = if (hasLocal) 1 else it.episodeLastPage,
                    rawEpisodes = if (hasLocal) local.items else it.rawEpisodes,
                    episodes = if (hasLocal) local.items.sortedWithOrder(sortOrder) else it.episodes,
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
                        rawEpisodes = result.items,
                        episodes = result.items.sortedWithOrder(it.episodeSortOrder),
                        episodePage = result.currentPage,
                        episodeLastPage = result.lastPage,
                        episodeLoading = false,
                        refreshing = false,
                        episodeError = null,
                        scrollToTopTrigger = it.scrollToTopTrigger + 1,
                    )
                }
                refreshDownloadedWrIds()
                if (result.items.isNotEmpty()) {
                    val firstThumb = result.items.firstOrNull()?.thumbUrl
                    if (!firstThumb.isNullOrBlank() && item.thumbUrl.isBlank()) {
                        updateThumbnailIfBlank(item.workId(), firstThumb)
                    }
                    val metadata = repository.fetchEpisodeMetadata(item, knownPage = result)
                    metadata.total?.let { t ->
                        _state.update { it.copy(catalogTotals = it.catalogTotals + (item.workId().storageKey() to t)) }
                    }
                    updateHistoryAndProgress(item, metadata.total)
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

    private fun runSearch(query: String, asRefresh: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            _state.update {
                it.copy(
                    loading = it.items.isEmpty() || !asRefresh,
                    refreshing = asRefresh,
                    error = null,
                    showPageJump = false,
                )
            }
            try {
                val sourceId = _state.value.activeSourceId ?: return@launch
                val items = repository.search(query, sourceId)
                repository.markSeen(items)
                _state.update {
                    it.copy(
                        items = items,
                        page = 1,
                        lastPage = 1,
                        loading = false,
                        refreshing = false,
                        error = null,
                        scrollToTopTrigger = it.scrollToTopTrigger + 1,
                    )
                }
                triggerEpisodeCountSync(items)
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

    private suspend fun updateHistoryAndProgress(item: ToonItem, counted: EpisodeListingTotal?) {
        val total = counted?.takeIf { it.isValid } ?: return
        repository.saveCatalogTotal(item.workId(), total)
        val rawEpisodes = _state.value.rawEpisodes.ifEmpty { _state.value.episodes }
        val latestRead = rawEpisodes.firstOrNull { it.isRead }
        val seriesKey = item.workId().storageKey()
        val derivedPosition = if (latestRead != null) {
            ReaderDomain.episodePosition(
                episodeIds = rawEpisodes.map(EpisodeItem::wrId),
                currentEpisodeId = latestRead.wrId,
                currentPage = _state.value.episodePage,
                lastPage = _state.value.episodeLastPage,
                knownTotal = _state.value.catalogTotals[seriesKey] ?: total,
                pageSize = repository.sourceOrNull(item.sourceId)?.episodePageSize ?: 100,
            )
        } else null
        val derivedOrder = derivedPosition?.readOrder

        val existing = repository.getHistoryRecord(item.workId())
        if (existing == null) {
            if (latestRead != null && derivedOrder != null) {
                val nextEp = derivedPosition.nextEpisodeIndex?.let { rawEpisodes.getOrNull(it) }
                val progress = RepositoryTransforms.historyProgress(derivedOrder, total)
                val created = ReadHistoryRecord(
                    sourceId = item.sourceId,
                    toonId = item.id,
                    toonTitle = item.title,
                    toonThumbUrl = item.thumbUrl,
                    toonHref = item.href,
                    lastWrId = latestRead.wrId,
                    lastEpisodeTitle = latestRead.title,
                    lastEpisodeHref = latestRead.href,
                    lastReadOrder = progress.lastReadOrder,
                    totalEpisodes = total.count,
                    lastReadAt = latestRead.readAt ?: System.currentTimeMillis(),
                    nextWrId = nextEp?.wrId,
                    nextEpisodeTitle = nextEp?.title,
                    nextEpisodeHref = nextEp?.href,
                    hasNew = progress.hasNew,
                )
                repository.saveHistoryRecord(created)
                val (progressText, readCount) = listingProgress(
                    item.sourceId,
                    created.lastReadOrder,
                    total,
                    item.workId(),
                )
                _state.update { curr ->
                    val updatedItems = curr.items.map { row ->
                        if (row.workId() == item.workId()) row.copy(readProgress = progressText) else row
                    }
                    curr.copy(
                        items = updatedItems,
                        seriesHistory = if (curr.series?.workId() == item.workId()) created else curr.seriesHistory,
                        readCounts = curr.readCounts.withCount(item.workId(), readCount),
                    )
                }
            } else {
                val (progressText, _) = listingProgress(
                    item.sourceId,
                    0,
                    total,
                    item.workId(),
                )
                if (progressText.isNotBlank()) {
                    _state.update { curr ->
                        val updatedItems = curr.items.map { row ->
                            if (row.workId() == item.workId()) row.copy(readProgress = progressText) else row
                        }
                        curr.copy(items = updatedItems)
                    }
                }
            }
            return
        }

        val mergedProgress = RepositoryTransforms.historyProgress(existing.lastReadOrder, total)
        val candidateOrder = mergedProgress.lastReadOrder
        val shouldElevate = derivedOrder != null && derivedOrder > candidateOrder
        val safeOrder = if (shouldElevate) derivedOrder!! else candidateOrder
        val hasNew = if (shouldElevate) total.needsRefresh(safeOrder) else mergedProgress.hasNew

        val updatedWrId = if (shouldElevate && latestRead != null) latestRead.wrId else existing.lastWrId
        val updatedTitle = if (shouldElevate && latestRead != null) latestRead.title else existing.lastEpisodeTitle
        val updatedHref = if (shouldElevate && latestRead != null) latestRead.href else existing.lastEpisodeHref
        val updatedReadAt = if (shouldElevate && latestRead != null) {
            maxOf(existing.lastReadAt, latestRead.readAt ?: 0L)
        } else existing.lastReadAt
        val updatedNextWrId = if (shouldElevate && derivedPosition.nextEpisodeIndex != null) {
            rawEpisodes.getOrNull(derivedPosition.nextEpisodeIndex!!)?.wrId ?: existing.nextWrId
        } else existing.nextWrId
        val updatedNextTitle = if (shouldElevate && derivedPosition.nextEpisodeIndex != null) {
            rawEpisodes.getOrNull(derivedPosition.nextEpisodeIndex!!)?.title ?: existing.nextEpisodeTitle
        } else existing.nextEpisodeTitle
        val updatedNextHref = if (shouldElevate && derivedPosition.nextEpisodeIndex != null) {
            rawEpisodes.getOrNull(derivedPosition.nextEpisodeIndex!!)?.href ?: existing.nextEpisodeHref
        } else existing.nextEpisodeHref

        if (existing.totalEpisodes != total.count ||
            existing.lastReadOrder != safeOrder ||
            existing.hasNew != hasNew ||
            existing.lastWrId != updatedWrId ||
            existing.lastReadAt != updatedReadAt
        ) {
            val updated = existing.copy(
                totalEpisodes = total.count,
                lastReadOrder = safeOrder,
                hasNew = hasNew,
                lastWrId = updatedWrId,
                lastEpisodeTitle = updatedTitle,
                lastEpisodeHref = updatedHref,
                lastReadAt = updatedReadAt,
                nextWrId = updatedNextWrId,
                nextEpisodeTitle = updatedNextTitle,
                nextEpisodeHref = updatedNextHref,
            )
            repository.saveHistoryRecord(updated)
            val (progressText, readCount) = listingProgress(
                item.sourceId,
                updated.lastReadOrder,
                total,
                item.workId(),
            )
            _state.update { curr ->
                val updatedItems = curr.items.map { row ->
                    if (row.workId() == item.workId()) row.copy(readProgress = progressText) else row
                }
                curr.copy(
                    items = updatedItems,
                    seriesHistory = if (curr.series?.workId() == item.workId()) updated else curr.seriesHistory,
                    readCounts = curr.readCounts.withCount(item.workId(), readCount),
                )
            }
        }
    }

    private fun loadReaderImages(episode: EpisodeItem, asRefresh: Boolean = false) {
        val workId = _state.value.series?.workId()
        loadReaderImages(episode, asRefresh) { repository.loadImages(episode, workId) }
    }

    private fun loadReaderImages(
        episode: EpisodeItem,
        asRefresh: Boolean,
        resolveImages: suspend () -> List<String>,
    ) {
        readerJob?.cancel()
        readerJob = scope.launch {
            _state.update {
                it.copy(
                    readerLoading = it.readerImages.isEmpty() || !asRefresh,
                    readerError = null,
                    refreshing = asRefresh,
                )
            }
            try {
                val images = resolveImages()
                if (images.isEmpty()) {
                    _state.update {
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
                _state.update {
                    it.copy(
                        readerImages = images,
                        imageAspectRatios = emptyMap(),
                        readerLoading = false,
                        refreshing = false,
                        readerError = null,
                    )
                }
                val effectivePage = _state.value.currentEpisode?.lastReadPage ?: episode.lastReadPage
                val targetPage = ReaderDomain.initialImagePage(effectivePage, images.size) ?: 0
                val preloadUrls = images.subList(
                    (targetPage - 1).coerceAtLeast(0),
                    (targetPage + 3).coerceAtMost(images.size),
                )
                platform.preloadReaderImages(preloadUrls)
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
        val ratio = ReaderDomain.imageAspectRatio(width, height) ?: return
        _state.update { current ->
            if (current.imageAspectRatios[index] == ratio) current
            else current.copy(imageAspectRatios = current.imageAspectRatios + (index to ratio))
        }
        if (platform.autoSwitchWebtoonInReader && ReaderDomain.isAutoScrollCandidate(index)) {
            checkAndAutoSwitchToScroll(width, height)
        }
    }

    private fun checkAndAutoSwitchToScroll(width: Int, height: Int) {
        val currentEp = _state.value.currentEpisode ?: return
        if (autoSwitchedEpisodeId == currentEp.wrId) return
        val currentMode = _state.value.viewMode
        if (currentMode != ViewMode.AUTO) return

        if (ReaderDomain.isWebtoonStrip(width, height)) {
            autoSwitchedEpisodeId = currentEp.wrId
            preAutoSwitchViewMode = currentMode
            val toast = I18n.strings(_state.value.appLanguage).toastAutoSwitchedToScroll
            _state.update {
                it.copy(
                    viewMode = ViewMode.SCROLL,
                    readerToastMessage = toast,
                )
            }
            readerToastJob?.cancel()
            readerToastJob = scope.launch {
                delay(1000)
                _state.update { it.copy(readerToastMessage = null) }
            }
        }
    }

    private fun restoreAutoSwitchedViewMode(): ViewMode {
        val mode = preAutoSwitchViewMode ?: _state.value.viewMode
        preAutoSwitchViewMode = null
        autoSwitchedEpisodeId = null
        readerToastJob?.cancel()
        return mode
    }

    private fun updateThumbnailIfBlank(workId: WorkId, thumbUrl: String) {
        if (thumbUrl.isBlank()) return
        _state.update { curr ->
            val updatedItems = curr.items.map { row ->
                if (row.workId() == workId && row.thumbUrl.isBlank()) {
                    row.copy(thumbUrl = thumbUrl)
                } else row
            }
            if (!curr.isSearch) {
                curr.tab?.let { tab ->
                    catalogCache[tab]?.let { cached ->
                        catalogCache[tab] = cached.map { row ->
                            if (row.workId() == workId && row.thumbUrl.isBlank()) {
                                row.copy(thumbUrl = thumbUrl)
                            } else row
                        }
                    }
                }
            }
            val updatedSeries = if (curr.series?.workId() == workId && curr.series.thumbUrl.isBlank()) {
                curr.series.copy(thumbUrl = thumbUrl)
            } else curr.series
            curr.copy(items = updatedItems, series = updatedSeries)
        }
    }

    private fun triggerEpisodeCountSync(items: List<ToonItem>) {
        repository.syncEpisodeCounts(
            items,
            onThumbnailResolved = ::updateThumbnailIfBlank,
            onUpdated = { workId, total, progressText ->
                _state.update { curr ->
                    val updatedItems = curr.items.map { row ->
                        if (row.workId() == workId) row.copy(readProgress = progressText) else row
                    }
                    if (!curr.isSearch) {
                        curr.tab?.let { tab ->
                            catalogCache[tab]?.let { cached ->
                                catalogCache[tab] = cached.map { row ->
                                    if (row.workId() == workId) row.copy(readProgress = progressText) else row
                                }
                            }
                        }
                    }
                    curr.copy(
                        items = updatedItems,
                        catalogTotals = curr.catalogTotals + (workId.storageKey() to total),
                        seriesHistory = if (curr.series?.workId() == workId) {
                            curr.seriesHistory?.let { currHist ->
                                val merged = RepositoryTransforms.historyProgress(currHist.lastReadOrder, total)
                                currHist.copy(
                                    totalEpisodes = total.count,
                                    lastReadOrder = merged.lastReadOrder,
                                    hasNew = merged.hasNew,
                                )
                            }
                        } else curr.seriesHistory,
                    )
                }
            },
        )
    }

    private fun refreshSeriesFavorited(series: ToonItem) {
        scope.launch {
            val favorited = repository.isFavorite(series.workId())
            _state.update { current ->
                if (current.series?.workId() == series.workId()) current.copy(seriesFavorited = favorited) else current
            }
        }
    }

    private fun humanError(prefix: String, e: Exception): String {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return "$prefix\n$detail"
    }

    private suspend fun listingProgress(
        sourceId: String,
        lastReadOrder: Int,
        total: EpisodeListingTotal?,
        workId: WorkId,
    ): Pair<String, Int> {
        val readCount = countIfNeeded(workId)
        return repository.formatReadProgress(sourceId, lastReadOrder, total, readCount) to readCount
    }

    private suspend fun countIfNeeded(workId: WorkId): Int {
        if (!sourceSettings.progressDisplayMode(workId.sourceId).requiresReadCount) {
            return 0
        }
        return repository.countReadEpisodes(workId)
    }

    private suspend fun countIfNeeded(workIds: List<WorkId>, sourceId: String): Map<String, Int> {
        if (workIds.isEmpty()) return emptyMap()
        if (!sourceSettings.progressDisplayMode(sourceId).requiresReadCount) {
            return emptyMap()
        }
        return repository.countReadEpisodes(workIds)
    }

    private fun Map<String, Int>.withCount(workId: WorkId, count: Int): Map<String, Int> =
        this + (workId.storageKey() to count)

    private fun ReadHistoryRecord.toToonItem(): ToonItem = ToonItem(
        id = toonId,
        title = toonTitle,
        thumbUrl = toonThumbUrl,
        href = toonHref,
        genre = "",
        updatedAt = null,
        sourceId = sourceId,
        readProgress = repository.formatReadProgress(
            sourceId = sourceId,
            lastReadOrder = lastReadOrder,
            total = _state.value.catalogTotals[workId().storageKey()],
            readCount = _state.value.readCounts[workId().storageKey()] ?: 0,
        ),
    )

    private fun clearCatalogCache(sourceId: String) {
        val stale = catalogCache.keys.filter { browseSourceId(it) == sourceId }
        stale.forEach {
            catalogCache.remove(it)
            catalogPages.remove(it)
        }
    }

    private fun browseSourceId(tab: BrowseTab): String = when (tab) {
        is BrowseTab.Favorite -> tab.sourceId
        is BrowseTab.Remote -> tab.sourceId
    }
}
