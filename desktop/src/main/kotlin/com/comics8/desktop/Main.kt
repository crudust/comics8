package com.comics8.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
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
    primary = Color(0xFFFF8A80),
    onPrimary = Color(0xFF3E0000),
    primaryContainer = Color(0xFF5C0002),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFFFB4AB),
    onSecondary = Color(0xFF441914),
    secondaryContainer = Color(0xFF2C2C2C),
    onSecondaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF141414),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2B2B2B),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF8E8E93),
    error = Color(0xFFFF5252),
    onError = Color(0xFF370001),
)

fun main() {
    System.setProperty("apple.awt.application.appearance", "system")
    System.setProperty("apple.laf.useScreenMenuBar", "true")

    application {
        val registry = remember {
            SourceRegistry().also {
                DesktopSourcePrefs.registry = it
                DesktopSourcePrefs.migrateIfNeeded()
            }
        }
        val locator = remember { SourceLocator { registry } }
        val database = remember {
            DesktopDatabase().apply {
                installedIds = { DesktopSourcePrefs.installedIds() }
            }
        }
        val cacheDir = File(System.getProperty("user.home"), ".comics8/http-cache")
        val toonClient = remember {
            ToonClient(ToonClient.defaultClient(cacheDir), sources = locator).also { client ->
                com.comics8.desktop.ui.util.DesktopImageCache.shareHttpClient(client.httpClient)
                com.comics8.desktop.ui.util.DesktopImageCache.registry = registry
            }
        }
        val sourceEnabled = { id: String -> DesktopSourcePrefs.isEnabled(id) }
        val installedIds = { DesktopSourcePrefs.installedIds() }
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
        val viewModel = remember { DesktopViewModel(repository) }
        val state by viewModel.state.collectAsState()

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
                    val isCmd = event.isMetaPressed
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

        val artistPick = state.artistPick
        if (artistPick != null) {
            com.comics8.desktop.ui.components.ArtistPickDialog(
                artists = artistPick.item.artistChoices,
                onPick = viewModel::pickArtist,
                onDismiss = viewModel::dismissArtistPick,
            )
        }
    }
}
