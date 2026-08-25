package com.comics8.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.res.painterResource
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
import com.comics8.core.source.network.loadSources
import com.comics8.desktop.data.DesktopDatabase
import java.io.File
import com.comics8.desktop.data.DesktopSourcePrefs
import com.comics8.desktop.data.DesktopSyncManager
import com.comics8.desktop.data.DesktopToonRepository
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.Screen
import com.comics8.desktop.ui.browse.BrowsePane
import com.comics8.desktop.ui.components.PageJumpDialog
import com.comics8.desktop.ui.components.SyncDialog
import com.comics8.desktop.ui.components.TopBar
import com.comics8.desktop.ui.history.HistoryPane
import com.comics8.desktop.ui.reader.ReaderPane
import com.comics8.desktop.ui.series.SeriesPane

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCBA6F7), // Mauve
    onPrimary = Color(0xFF11111B), // Crust
    primaryContainer = Color(0xFF45475A), // Surface1
    onPrimaryContainer = Color(0xFFCBA6F7), // Mauve
    secondary = Color(0xFF89B4FA), // Blue
    onSecondary = Color(0xFF11111B), // Crust
    secondaryContainer = Color(0xFF313244), // Surface0
    onSecondaryContainer = Color(0xFFCDD6F4), // Text
    tertiary = Color(0xFFB4BEFE), // Lavender
    onTertiary = Color(0xFF11111B), // Crust
    background = Color(0xFF1E1E2E), // Base
    onBackground = Color(0xFFCDD6F4), // Text
    surface = Color(0xFF313244), // Surface0
    onSurface = Color(0xFFCDD6F4), // Text
    surfaceVariant = Color(0xFF45475A), // Surface1
    onSurfaceVariant = Color(0xFFA6ADC8), // Subtext0
    surfaceContainer = Color(0xFF181825), // Mantle
    surfaceContainerHigh = Color(0xFF313244), // Surface0
    surfaceContainerHighest = Color(0xFF45475A), // Surface1
    outline = Color(0xFF6C7086), // Overlay0
    outlineVariant = Color(0xFF585B70), // Surface2
    error = Color(0xFFF38BA8), // Red
    onError = Color(0xFF11111B), // Crust
)

fun main() {
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("apple.laf.useScreenMenuBar", "true")

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
            icon = painterResource("icon.png"),
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
            MaterialTheme(colorScheme = DarkColors) {
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

@Composable
fun ComicsDesktopApp(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
) {
    LaunchedEffect(state.showAddSourceSheet) {
        if (state.showAddSourceSheet) {
            viewModel.closeAddSourceSheet()
            com.comics8.desktop.ui.components.pickJsFile()?.let(viewModel::importJsFile)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.screen == Screen.Reader) {
            ReaderPane(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(
                    state = state,
                    viewModel = viewModel,
                )
                when (state.screen) {
                    Screen.Browse -> BrowsePane(state = state, viewModel = viewModel, modifier = Modifier.weight(1f))
                    Screen.Series -> SeriesPane(state = state, viewModel = viewModel, modifier = Modifier.weight(1f))
                    Screen.History -> HistoryPane(state = state, viewModel = viewModel, modifier = Modifier.weight(1f))
                    Screen.Downloads -> com.comics8.desktop.ui.downloads.DesktopDownloadsPane(state = state, viewModel = viewModel, modifier = Modifier.weight(1f))
                    Screen.Reader -> {}
                }
            }
        }

        val currentSeries = state.series
        if (state.showDownloadOptions && currentSeries != null) {
            com.comics8.desktop.ui.components.DesktopDownloadDialog(
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

        if (state.showSyncDialog) {
            SyncDialog(
                state = state,
                viewModel = viewModel,
                onDismiss = viewModel::closeSyncDialog,
            )
        }

        if (state.showUpdateDialog) {
            com.comics8.desktop.ui.components.DesktopUpdateDialog(
                updateState = state.updateState,
                onDismiss = viewModel::closeUpdateDialog,
                onConfirmUpdate = viewModel::startUpdate,
            )
        }

        state.networkDraft?.let { draft ->
            com.comics8.desktop.ui.components.NetworkSourceDialog(
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
            com.comics8.desktop.ui.components.ArtistPickDialog(
                artists = artistPick.item.artistChoices,
                onPick = viewModel::pickArtist,
                onDismiss = viewModel::dismissArtistPick,
            )
        }
        if (state.showRemoveSourceSheet) {
            com.comics8.desktop.ui.components.SourceRemoveDialog(
                sources = state.installedSources,
                onRemove = viewModel::uninstallSource,
                onDismiss = viewModel::closeRemoveSourceSheet,
            )
        }
        val sourceError = state.sourceError
        if (sourceError != null) {
            com.comics8.desktop.ui.components.SourceErrorDialog(
                message = sourceError,
                onDismiss = viewModel::dismissSourceError,
            )
        }
    }
}
