package com.comics8.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import com.comics8.core.i18n.I18n
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.comics8.core.network.ToonClient
import com.comics8.core.source.SourceLocator
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.js.JsPackStore
import com.comics8.core.source.network.NetworkSourceStore
import com.comics8.core.source.network.NetworkSourceConfig
import com.comics8.core.source.network.NetworkSourceRuntime
import com.comics8.core.source.network.loadSources
import com.comics8.desktop.data.DesktopDatabase
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.comics8.desktop.data.DesktopSourcePrefs
import com.comics8.desktop.data.DesktopSyncManager
import com.comics8.desktop.data.DesktopToonRepository
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.Screen
import com.comics8.desktop.ui.components.DesktopPullToRefreshIndicator
import com.comics8.desktop.ui.components.LocalTrackpadPinchDispatcher
import com.comics8.desktop.ui.components.MacTrackpadPinchBridge
import com.comics8.desktop.ui.components.TrackpadPinchDispatcher
import com.comics8.desktop.ui.components.desktopDiscreteScrollPaging
import com.comics8.desktop.ui.components.pickJsFile
import com.comics8.desktop.ui.reader.ReaderPane
import com.comics8.desktop.ui.util.DesktopAsyncImage
import com.comics8.shared.ui.ComicImageRenderer
import com.comics8.shared.ui.LocalComicImageRenderer
import com.comics8.shared.ui.LocalPlatformUiBridge
import com.comics8.shared.ui.PlatformUiBridge
import com.comics8.shared.ui.browse.BrowsePane
import com.comics8.shared.ui.components.ArtistPickDialog
import com.comics8.shared.ui.components.DownloadOptionsDialog
import com.comics8.shared.ui.components.NetworkSourceDialog
import com.comics8.shared.ui.components.PageJumpDialog
import com.comics8.shared.ui.components.SourceErrorDialog
import com.comics8.shared.ui.components.SourceRemoveDialog
import com.comics8.shared.ui.components.TopBar
import com.comics8.shared.ui.components.UpdateDialog
import com.comics8.shared.ui.downloads.DownloadsPane
import com.comics8.shared.ui.history.HistoryPane
import com.comics8.shared.ui.series.SeriesPane
import com.comics8.shared.ui.settings.SettingsPane
import com.comics8.shared.ui.source.SourceManagerPane
import com.comics8.shared.ui.theme.ComicsTheme
import com.comics8.shared.ui.theme.LocalStrings
import org.jetbrains.skia.Image

fun main(args: Array<String>) {
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("apple.laf.useScreenMenuBar", "true")
    DesktopOpenFileEvents.install()
    DesktopOpenFileEvents.acceptArgs(args.toList())

    application {
        val jsPackStore = remember { JsPackStore.desktopDefault() }
        val networkSourceStore = remember {
            NetworkSourceStore(
                File(System.getProperty("user.home"), ".comics8/network-connections.json"),
                File(System.getProperty("user.home"), ".comics8/cache/library-index/network"),
            )
        }
        val registry = remember {
            SourceRegistry().also {
                DesktopSourcePrefs.registry = it
                DesktopSourcePrefs.migrateIfNeeded()
                val loaded = jsPackStore.loadInto(it)
                val networkSources = networkSourceStore.loadSources()
                networkSources.forEach(it::add)
                val migratedInstalled = DesktopSourcePrefs.installedIds()
                    .map(NetworkSourceConfig::normalizeId)
                    .toSet()
                DesktopSourcePrefs.storedActiveRaw()?.let { active ->
                    val migrated = NetworkSourceConfig.normalizeId(active)
                    if (migrated != active) DesktopSourcePrefs.setActiveSourceId(migrated)
                }
                val loadedIds = loaded.map { source -> source.id } + networkSources.map { source -> source.id }
                if (loadedIds.isNotEmpty()) {
                    DesktopSourcePrefs.setInstalledIds(
                        migratedInstalled + loadedIds,
                    )
                }
            }
        }
        val locator = remember { SourceLocator { registry } }
        val sourceEnabled = { id: String -> DesktopSourcePrefs.isEnabled(id) }
        val installedIds = { DesktopSourcePrefs.installedIds() }
        val database = remember {
            DesktopDatabase().apply {
                isSourceEnabled = sourceEnabled
                this.installedIds = installedIds
            }
        }
        val cacheDir = File(System.getProperty("user.home"), ".comics8/http-cache")
        val toonClient = remember {
            ToonClient(ToonClient.defaultClient(cacheDir), sources = locator).also { client ->
                val imageClient = ToonClient.newImageHttpClient(client.httpClient, registry)
                com.comics8.desktop.ui.util.DesktopImageCache.shareHttpClient(imageClient)
                com.comics8.desktop.ui.util.DesktopImageCache.registry = registry
            }
        }
        val syncManager = remember { DesktopSyncManager(database, toonClient) }
        val downloadManager = remember {
            com.comics8.desktop.data.DesktopDownloadManager(
                database,
                toonClient,
                isSourceEnabled = sourceEnabled,
                sources = registry,
                installedIds = installedIds,
            )
        }
        val repository = remember {
            DesktopToonRepository(
                toonClient,
                database,
                syncManager,
                downloadManager,
                sources = registry,
                isSourceEnabled = sourceEnabled,
                installedIds = installedIds,
            )
        }
        val viewModel = remember { DesktopViewModel(repository, jsPackStore, networkSourceStore) }
        DisposableEffect(viewModel) {
            val openFileRegistration = DesktopOpenFileEvents.listen(viewModel::openExternalArchive)
            onDispose {
                openFileRegistration.close()
                viewModel.close()
                repository.close()
                downloadManager.close()
                syncManager.close()
                database.close()
                NetworkSourceRuntime.disconnectAll()
                com.comics8.desktop.ui.util.DesktopImageCache.close()
            }
        }
        val state by viewModel.state.collectAsState()
        LaunchedEffect(Unit) {
            viewModel.onImportedPacksReady()
        }

        val windowState = rememberWindowState(
            width = 1280.dp,
            height = 900.dp,
            placement = if (state.isFullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating,
        )

        Window(
            onCloseRequest = ::exitApplication,
            title = "Comics8",
            icon = rememberApplicationIcon(),
            state = windowState,
            onKeyEvent = { event ->
                if (event.type == KeyEventType.KeyDown) {
                    val key = event.key
                    val isCmd = event.isMetaPressed || event.isCtrlPressed
                    val isAlt = event.isAltPressed
                    val isBack =
                        (isCmd && (key == Key.DirectionLeft || key == Key.LeftBracket || event.utf16CodePoint == '['.code)) ||
                        (isAlt && (key == Key.DirectionLeft || key == Key.LeftBracket || event.utf16CodePoint == '['.code))
                    val isForward =
                        (isCmd && (key == Key.DirectionRight || key == Key.RightBracket || event.utf16CodePoint == ']'.code)) ||
                        (isAlt && (key == Key.DirectionRight || key == Key.RightBracket || event.utf16CodePoint == ']'.code))

                    when {
                        isBack -> {
                            viewModel.goBack()
                            true
                        }
                        isForward -> {
                            viewModel.goForward()
                            true
                        }
                        key == Key.Escape -> {
                            viewModel.goBack()
                            true
                        }
                        else -> false
                    }
                } else false
            },
        ) {
            DisposableEffect(window) {
                DesktopOpenFileEvents.onBringToFront = {
                    windowState.isMinimized = false
                    if ((window.extendedState and java.awt.Frame.ICONIFIED) != 0) {
                        window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                    }
                    window.toFront()
                    window.requestFocus()
                }
                val listener = object : java.awt.event.WindowAdapter() {
                    override fun windowIconified(e: java.awt.event.WindowEvent?) {
                        com.comics8.desktop.ui.util.DesktopImageCache.trimMemory()
                    }
                }
                window.addWindowListener(listener)
                onDispose {
                    DesktopOpenFileEvents.onBringToFront = null
                    window.removeWindowListener(listener)
                }
            }

            val trackpadPinchDispatcher = remember { TrackpadPinchDispatcher() }
            val isViewerPinchActive = state.screen == Screen.Reader &&
                !state.showDownloadOptions &&
                !state.showPageJump &&
                !state.showUpdateDialog

            DisposableEffect(window, isViewerPinchActive) {
                if (!isViewerPinchActive) {
                    onDispose {}
                } else {
                    val handle = MacTrackpadPinchBridge.install(window.rootPane) { magnificationDelta ->
                        trackpadPinchDispatcher.dispatch(magnificationDelta)
                    }
                    onDispose { handle.close() }
                }
            }

            CompositionLocalProvider(LocalTrackpadPinchDispatcher provides trackpadPinchDispatcher) {
                ComicsTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        ComicsDesktopApp(state = state, viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberApplicationIcon(): BitmapPainter = remember {
    val bytes = checkNotNull(object {}.javaClass.getResourceAsStream("/icon.png")) {
        "Missing desktop application icon"
    }.use { it.readBytes() }
    Image.makeFromEncoded(bytes).use { BitmapPainter(it.toComposeImageBitmap()) }
}

@Composable
fun ComicsDesktopApp(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
) {
    val strings = remember(state.appLanguage) { I18n.strings(state.appLanguage) }
    val platformBridge = remember {
        object : PlatformUiBridge {
            override fun copyToClipboard(label: String, text: String) {
                try {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                } catch (_: Exception) {
                }
            }

            override fun showToast(message: String) = Unit

            override fun openUrl(url: String) {
                try {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI(url))
                    }
                } catch (_: Exception) {
                }
            }
        }
    }
    val imageRenderer = remember {
        ComicImageRenderer { url, cacheRole, contentDescription, modifier, contentScale, refreshEpoch ->
            DesktopAsyncImage(
                url = url,
                cacheRole = cacheRole,
                contentDescription = contentDescription,
                contentScale = contentScale,
                refreshEpoch = refreshEpoch,
                modifier = modifier,
            )
        }
    }

    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalPlatformUiBridge provides platformBridge,
        LocalComicImageRenderer provides imageRenderer,
    ) {
        ComicsDesktopAppContent(state = state, viewModel = viewModel)
    }
}

@Composable
private fun ComicsDesktopAppContent(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
) {
    LaunchedEffect(state.showAddSourceSheet) {
        if (state.showAddSourceSheet) {
            viewModel.closeAddSourceSheet()
            pickJsFile()?.let(viewModel::importJsFile)
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportWidthDp = maxWidth.value.toInt()
        val viewportHeightDp = maxHeight.value.toInt()
        LaunchedEffect(viewportWidthDp, viewportHeightDp) {
            viewModel.updateViewportSize(viewportWidthDp, viewportHeightDp)
        }
        if (state.screen == Screen.Reader) {
            ReaderPane(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (state.screen == Screen.Settings) {
            val strings = LocalStrings.current
            SettingsPane(
                state = state,
                controller = viewModel.controller,
                appVersion = DesktopVersion.VERSION_NAME,
                showSplitMode = true,
                onExportBackup = { onResult ->
                    try {
                        val timeTag = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                        val dialog = FileDialog(null as Frame?, strings.titleSaveBackupFile, FileDialog.SAVE)
                        dialog.file = "comics8_backup_$timeTag.json"
                        dialog.isVisible = true
                        val dir = dialog.directory
                        val file = dialog.file
                        if (dir != null && file != null) {
                            viewModel.exportBackup(File(dir, file), onResult)
                        }
                    } catch (e: Exception) {
                        onResult(false, "${strings.msgBackupExportFailed}: ${e.message}")
                    }
                },
                onImportBackup = { onResult ->
                    try {
                        val dialog = FileDialog(null as Frame?, strings.titleOpenBackupFile, FileDialog.LOAD)
                        dialog.setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
                        dialog.isVisible = true
                        val dir = dialog.directory
                        val file = dialog.file
                        if (dir != null && file != null) {
                            viewModel.importBackup(File(dir, file), onResult)
                        }
                    } catch (e: Exception) {
                        onResult(false, "${strings.msgBackupRestoreFailed}: ${e.message}")
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (state.screen == Screen.SourceManager) {
            SourceManagerPane(
                state = state,
                controller = viewModel.controller,
                onPickLocalFolder = viewModel::addLibraryRoot,
                onRemoveLocalFolder = viewModel::removeLibraryRoot,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    state = state,
                    controller = viewModel.controller,
                )
                when (state.screen) {
                    Screen.Browse -> {
                        val gridState = rememberLazyGridState()
                        val canSwipePage = state.tab?.paginated == true && !state.loading && !state.isSearch && state.lastPage > 1
                        val canPullRefresh = state.packsReady && state.activeSourceId != null && !state.loading
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .desktopDiscreteScrollPaging(
                                    enabled = canSwipePage,
                                    onAdvance = { if (state.page < state.lastPage) viewModel.nextPage() },
                                    onRetreat = { if (state.page > 1) viewModel.prevPage() },
                                    onPullRefresh = if (canPullRefresh) { { viewModel.refresh() } } else null,
                                    isAtTop = { !gridState.canScrollBackward },
                                ),
                        ) {
                            BrowsePane(
                                state = state,
                                controller = viewModel.controller,
                                gridState = gridState,
                                onImportJs = { pickJsFile()?.let(viewModel::importJsFile) },
                                onAddLibraryFolder = viewModel::addLibraryRoot,
                                modifier = Modifier.fillMaxSize(),
                            )
                            DesktopPullToRefreshIndicator(
                                refreshing = state.refreshing,
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                        }
                    }
                    Screen.Series -> {
                        val listState = rememberLazyListState()
                        val canSwipePage = !state.episodeLoading
                        val canPullRefresh = !state.episodeLoading && !state.refreshing
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .desktopDiscreteScrollPaging(
                                    enabled = canSwipePage,
                                    onAdvance = {
                                        if (state.episodePage < state.episodeLastPage) {
                                            viewModel.nextEpisodePage()
                                        }
                                    },
                                    onRetreat = {
                                        if (state.episodePage > 1) {
                                            viewModel.prevEpisodePage()
                                        } else {
                                            viewModel.closeSeries()
                                        }
                                    },
                                    onPullRefresh = if (canPullRefresh) { { viewModel.refresh() } } else null,
                                    isAtTop = { !listState.canScrollBackward },
                                ),
                        ) {
                            SeriesPane(
                                state = state,
                                controller = viewModel.controller,
                                listState = listState,
                                modifier = Modifier.fillMaxSize(),
                            )
                            DesktopPullToRefreshIndicator(
                                refreshing = state.refreshing,
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                        }
                    }
                    Screen.History -> HistoryPane(
                        state = state,
                        controller = viewModel.controller,
                        onImportJs = { pickJsFile()?.let(viewModel::importJsFile) },
                        modifier = Modifier.weight(1f),
                    )
                    Screen.Downloads -> DownloadsPane(
                        state = state,
                        controller = viewModel.controller,
                        onImportJs = { pickJsFile()?.let(viewModel::importJsFile) },
                        modifier = Modifier.weight(1f),
                    )
                    else -> {}
                }
            }
        }

        val currentSeries = state.series
        if (state.showDownloadOptions && currentSeries != null) {
            DownloadOptionsDialog(
                series = currentSeries,
                episodes = state.downloadCatalog,
                catalogLoading = state.downloadCatalogLoading,
                onDismiss = viewModel::closeDownloadOptions,
                onConfirm = viewModel::startDownloadEpisodes,
            )
        }

        if (state.showPageJump) {
            PageJumpDialog(
                current = if (state.screen == Screen.Series) state.episodePage else state.page,
                last = if (state.screen == Screen.Series) state.episodeLastPage else state.lastPage,
                onDismiss = { viewModel.togglePageJump(false) },
                onConfirm = { page ->
                    if (state.screen == Screen.Series) {
                        viewModel.goToEpisodePage(page)
                    } else {
                        viewModel.goToPage(page)
                    }
                },
            )
        }

        if (state.showUpdateDialog) {
            UpdateDialog(
                updateState = state.updateState,
                onDismiss = viewModel::closeUpdateDialog,
                onConfirmUpdate = viewModel::startUpdate,
                confirmActionLabel = LocalStrings.current.actionUpdateAndRestart,
            )
        }

        state.networkDraft?.let { draft ->
            NetworkSourceDialog(
                draft = draft,
                testing = state.networkTesting,
                testMessage = state.networkTestMessage,
                testSucceeded = state.networkTestSucceeded,
                onChange = viewModel::updateNetworkDraft,
                onTest = viewModel::testNetworkConnection,
                onRegister = viewModel::registerNetworkConnection,
                onDismiss = viewModel::closeNetworkSourceDialog,
            )
        }

        val artistPick = state.artistPick
        if (artistPick != null) {
            ArtistPickDialog(
                artists = artistPick.item.artistChoices,
                onPick = viewModel::pickArtist,
                onDismiss = viewModel::dismissArtistPick,
            )
        }
        if (state.showRemoveSourceSheet) {
            SourceRemoveDialog(
                sources = state.installedSources,
                onRemove = viewModel::uninstallSource,
                onDismiss = viewModel::closeRemoveSourceSheet,
            )
        }
        val sourceError = state.sourceError
        if (sourceError != null) {
            SourceErrorDialog(
                message = sourceError,
                onDismiss = viewModel::dismissSourceError,
            )
        }
    }
}
