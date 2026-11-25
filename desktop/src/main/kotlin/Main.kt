package com.comics8.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import com.comics8.core.network.ImageFallbacks
import com.comics8.core.network.ToonClient
import com.comics8.core.source.SourceLocator
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.js.JsPackStore
import com.comics8.core.source.network.NetworkSourceStore
import com.comics8.core.source.network.NetworkSourceConfig
import com.comics8.core.source.network.NetworkSourceRuntime
import com.comics8.core.source.network.loadSources
import com.comics8.shared.db.openDesktopDatabase
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
import com.comics8.desktop.ui.reader.LocalReaderKeyDispatcher
import com.comics8.desktop.ui.reader.ReaderKeyDispatcher
import com.comics8.desktop.ui.reader.ReaderPane
import com.comics8.desktop.ui.reader.isReaderKeyDispatchActive
import com.comics8.desktop.ui.util.DesktopCoil
import com.comics8.shared.ui.LocalImageFallbacksProvider
import com.comics8.shared.ui.LocalPlatformUiBridge
import com.comics8.shared.ui.PlatformUiBridge
import com.comics8.shared.ui.SharedAppShell
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
        val database = remember { openDesktopDatabase() }
        val cacheDir = File(System.getProperty("user.home"), ".comics8/http-cache")
        val toonClient = remember {
            ToonClient(ToonClient.defaultClient(cacheDir), sources = locator).also { client ->
                val imageClient = ToonClient.newImageHttpClient(client.httpClient, registry)
                DesktopCoil.configure(imageClient, registry)
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
                DesktopCoil.close()
            }
        }
        val state by viewModel.state.collectAsState()
        LaunchedEffect(Unit) {
            viewModel.onImportedPacksReady()
        }

        val readerKeyDispatcher = remember { ReaderKeyDispatcher() }

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
                        viewModel.state.value.isReaderKeyDispatchActive() -> readerKeyDispatcher.dispatch(event)
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
                        DesktopCoil.trimMemory()
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

            CompositionLocalProvider(
                LocalTrackpadPinchDispatcher provides trackpadPinchDispatcher,
                LocalReaderKeyDispatcher provides readerKeyDispatcher,
            ) {
                ComicsTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        ComicsDesktopApp(
                            state = state,
                            viewModel = viewModel,
                            fallbacksProvider = { url -> ImageFallbacks.forUrl(url, registry) },
                        )
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
    fallbacksProvider: (String) -> List<String> = { emptyList() },
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

    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalPlatformUiBridge provides platformBridge,
        LocalImageFallbacksProvider provides fallbacksProvider,
    ) {
        ComicsDesktopAppContent(state = state, viewModel = viewModel)
    }
}

@Composable
private fun ComicsDesktopAppContent(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
) {
    val strings = LocalStrings.current
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
        SharedAppShell(
            state = state,
            controller = viewModel.controller,
            appVersion = DesktopVersion.VERSION_NAME,
            compactTopBar = false,
            showSplitModeInSettings = true,
            updateConfirmActionLabel = strings.actionUpdateAndRestart,
            onConfirmUpdate = viewModel::startUpdate,
            onImportJs = { pickJsFile()?.let(viewModel::importJsFile) },
            onPickLocalFolder = viewModel::addLibraryRoot,
            onRemoveLocalFolder = viewModel::removeLibraryRoot,
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
            readerContent = {
                ReaderPane(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            browsePaneWrapper = { gridState, paneContent ->
                val canSwipePage = state.tab?.paginated == true && !state.loading && !state.isSearch && state.lastPage > 1
                val canPullRefresh = state.packsReady && state.activeSourceId != null && !state.loading
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .desktopDiscreteScrollPaging(
                            enabled = canSwipePage,
                            onAdvance = { if (state.page < state.lastPage) viewModel.nextPage() },
                            onRetreat = { if (state.page > 1) viewModel.prevPage() },
                            onPullRefresh = if (canPullRefresh) { { viewModel.refresh() } } else null,
                            isAtTop = { !gridState.canScrollBackward },
                        ),
                ) {
                    paneContent(Modifier.fillMaxSize())
                    DesktopPullToRefreshIndicator(
                        refreshing = state.refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            },
            seriesPaneWrapper = { listState, paneContent ->
                val canSwipePage = !state.episodeLoading
                val canPullRefresh = !state.episodeLoading && !state.refreshing
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
                    paneContent(Modifier.fillMaxSize())
                    DesktopPullToRefreshIndicator(
                        refreshing = state.refreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
            },
        )
    }
}
