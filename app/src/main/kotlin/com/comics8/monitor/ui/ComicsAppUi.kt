package com.comics8.monitor.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comics8.core.model.BrowseTab
import com.comics8.core.source.WorkId
import com.comics8.monitor.data.local.PersistableOpenDocumentTree
import com.comics8.monitor.ui.browse.EmptyLibraryPane
import com.comics8.monitor.ui.browse.EmptyLibraryScanHint
import com.comics8.monitor.ui.browse.ListingGrid
import com.comics8.monitor.ui.components.ArtistPickDialog
import com.comics8.monitor.ui.components.EmptySourcePane
import com.comics8.monitor.ui.components.ErrorPane
import com.comics8.monitor.ui.components.LoadingPane
import com.comics8.monitor.ui.components.NetworkSourceDialog
import com.comics8.monitor.ui.components.PageBar
import com.comics8.monitor.ui.components.PageJumpDialog
import com.comics8.monitor.ui.components.SourceErrorDialog
import com.comics8.monitor.ui.components.SourceRemoveDialog
import com.comics8.monitor.ui.components.SyncDialog
import com.comics8.monitor.ui.components.TopBar
import com.comics8.monitor.ui.history.HistoryPane
import com.comics8.monitor.ui.reader.ReaderPane
import com.comics8.monitor.ui.series.SeriesPane

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.comics8.core.i18n.I18n
import com.comics8.monitor.ui.theme.LocalStrings

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicsAppUi(viewModel: ListingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val strings = remember(state.appLanguage) { I18n.strings(state.appLanguage) }

    CompositionLocalProvider(LocalStrings provides strings) {
        ComicsAppUiContent(state = state, viewModel = viewModel, strings = strings)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComicsAppUiContent(
    state: ListingUiState,
    viewModel: ListingViewModel,
    strings: com.comics8.core.i18n.AppStrings,
) {
    val isReader = state.screen == Screen.Reader
    val context = LocalContext.current
    val config = LocalConfiguration.current

    val catalogs = state.browseTabs

    LaunchedEffect(config.screenWidthDp, config.screenHeightDp) {
        viewModel.updateScreenDimensions(config.screenWidthDp, config.screenHeightDp)
    }

    DisposableEffect(isReader) {
        val activity = context.findActivity() ?: return@DisposableEffect onDispose {}
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isReader) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val pickJs = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let(viewModel::importJsFromUri)
    }
    val onImportJs: () -> Unit = {
        viewModel.closeAddSourceSheet()
        pickJs.launch("*/*")
    }
    LaunchedEffect(state.showAddSourceSheet) {
        if (state.showAddSourceSheet) onImportJs()
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val treePicker = rememberLauncherForActivityResult(PersistableOpenDocumentTree()) { uri ->
        if (uri != null) viewModel.addLibraryRoot(uri)
    }
    LaunchedEffect(state.pendingLibraryTreePick) {
        if (state.pendingLibraryTreePick) {
            viewModel.consumeLibraryTreePick()
            treePicker.launch(null)
        }
    }
    val addLibraryFolder = { treePicker.launch(null) }

    val canGoBack = state.screen != Screen.Browse || state.isSearch || state.showPageJump || state.showSyncDialog || state.showUpdateDialog || state.showDownloadOptions || state.artistPick != null || state.showAddSourceSheet || state.showRemoveSourceSheet || state.sourceError != null || state.networkDraft != null
    BackHandler(enabled = canGoBack) {
        viewModel.goBack()
    }

    ComicsThemeScaffold(isImmersive = isReader) {
        if (isReader) {
            ReaderPane(state = state, viewModel = viewModel)
        } else if (state.screen == Screen.Settings) {
            com.comics8.monitor.ui.settings.SettingsPane(state = state, viewModel = viewModel)
        } else if (state.screen == Screen.SourceManager) {
            com.comics8.monitor.ui.source.SourceManagerPane(state = state, viewModel = viewModel)
        } else {
            val browseContent: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize()) {
                    TopBar(state = state, viewModel = viewModel)
                    when {
                        state.screen == Screen.Series -> SeriesPane(state = state, viewModel = viewModel)
                        state.screen == Screen.History -> HistoryPane(
                            state = state,
                            viewModel = viewModel,
                            onAddSource = viewModel::openAddSourceSheet,
                            onImportJs = onImportJs,
                        )
                        state.screen == Screen.Downloads -> com.comics8.monitor.ui.downloads.DownloadsPane(
                            state = state,
                            viewModel = viewModel,
                            onAddSource = viewModel::openAddSourceSheet,
                            onImportJs = onImportJs,
                        )
                        state.activeSourceId == null -> EmptySourcePane(
                            onAddSource = viewModel::openAddSourceSheet,
                            onImportJs = onImportJs,
                        )
                        state.activeSourceId == WorkId.LOCAL_SOURCE &&
                            state.libraryRoots.isEmpty() &&
                            !state.loading &&
                            !state.isSearch &&
                            state.screen == Screen.Browse -> EmptyLibraryPane(
                            onAddFolder = addLibraryFolder,
                        )
                        state.isSearch -> {
                            when {
                                state.loading && state.items.isEmpty() -> LoadingPane()
                                state.error != null && state.items.isEmpty() -> ErrorPane(
                                    message = state.error!!,
                                    onRetry = { viewModel.refresh(force = true) },
                                )
                                state.items.isEmpty() -> ErrorPane(
                                    message = strings.searchResultEmpty(state.searchQuery ?: ""),
                                    actionLabel = strings.navBackToList,
                                    onRetry = { state.tab?.let(viewModel::selectTab) },
                                )
                                else -> ListingGrid(
                                    items = state.items,
                                    scrollToTopTrigger = state.scrollToTopTrigger,
                                    onOpen = viewModel::onListingOpen,
                                    onToggleFavorite = viewModel::toggleFavorite,
                                    showSourceChip = false,
                                    sourceRegistry = state.sourceRegistry,
                                    refreshEpoch = state.refreshEpoch,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        else -> {
                            key(state.activeSourceId, state.tab) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(catalogs, state.tab) {
                                            val currentTab = state.tab ?: catalogs.firstOrNull() ?: return@pointerInput
                                            val currentIndex = catalogs.indexOf(currentTab)
                                            if (currentIndex < 0 || catalogs.size <= 1) return@pointerInput

                                            var totalDragX = 0f
                                            val velocityTracker = VelocityTracker()

                                            detectHorizontalDragGestures(
                                                onDragStart = {
                                                    totalDragX = 0f
                                                    velocityTracker.resetTracking()
                                                },
                                                onHorizontalDrag = { change, dragAmount ->
                                                    change.consume()
                                                    totalDragX += dragAmount
                                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                                },
                                                onDragEnd = {
                                                    val velocityX = velocityTracker.calculateVelocity().x
                                                    val thresholdPx = 50.dp.toPx()
                                                    val velocityThresholdPx = 300.dp.toPx()

                                                    if ((totalDragX < -thresholdPx || velocityX < -velocityThresholdPx) && currentIndex < catalogs.size - 1) {
                                                        viewModel.selectTab(catalogs[currentIndex + 1])
                                                    } else if ((totalDragX > thresholdPx || velocityX > velocityThresholdPx) && currentIndex > 0) {
                                                        viewModel.selectTab(catalogs[currentIndex - 1])
                                                    }
                                                    totalDragX = 0f
                                                },
                                                onDragCancel = {
                                                    totalDragX = 0f
                                                },
                                            )
                                        },
                                ) {
                                    BrowseTabContent(
                                        catalogs = catalogs,
                                        state = state,
                                        viewModel = viewModel,
                                        onAddLibraryFolder = addLibraryFolder,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (state.activeSourceId == null) {
                browseContent()
            } else {
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = {
                        if (state.screen == Screen.History) {
                            viewModel.loadHistory()
                        } else if (state.screen == Screen.Downloads) {
                            viewModel.loadDownloads()
                        } else {
                            viewModel.refresh()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    browseContent()
                }
            }
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
        val currentSeries = state.series
        if (state.showDownloadOptions && currentSeries != null) {
            com.comics8.monitor.ui.components.DownloadOptionsDialog(
                series = currentSeries,
                episodes = state.downloadCatalog,
                catalogLoading = state.downloadCatalogLoading,
                onDismiss = viewModel::closeDownloadOptions,
                onConfirm = viewModel::startDownloadEpisodes,
            )
        }
        if (state.showUpdateDialog) {
            com.comics8.monitor.ui.components.UpdateDialog(
                updateState = state.updateState,
                onDismiss = viewModel::dismissUpdateDialog,
                onConfirmUpdate = viewModel::startUpdate,
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

@Composable
private fun BrowseTabContent(
    catalogs: List<BrowseTab>,
    state: ListingUiState,
    viewModel: ListingViewModel,
    onAddLibraryFolder: () -> Unit = {},
) {
    val pageCatalog = state.tab ?: catalogs.firstOrNull()
    if (pageCatalog == null) {
        LoadingPane()
        return
    }
    val isLocal = state.activeSourceId == WorkId.LOCAL_SOURCE
    val strings = LocalStrings.current
    when {
        state.loading && state.items.isEmpty() -> {
            Column(Modifier.fillMaxSize()) {
                LoadingPane(Modifier.weight(1f).fillMaxWidth())
                if (pageCatalog.paginated && state.lastPage > 1) {
                    PageBar(
                        page = state.page,
                        lastPage = state.lastPage,
                        onPrev = viewModel::prevPage,
                        onNext = viewModel::nextPage,
                        onJump = { viewModel.togglePageJump(true) },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        state.error != null && state.items.isEmpty() -> ErrorPane(
            message = state.error!!,
            onRetry = { viewModel.refresh(force = true) },
        )
        pageCatalog is BrowseTab.Favorite && state.items.isEmpty() -> ErrorPane(
            message = strings.emptyFavorites,
            actionLabel = strings.navToLatest,
            onRetry = {
                val dest = state.browseTabs.firstOrNull { it is BrowseTab.Remote } ?: state.tab
                dest?.let(viewModel::selectTab)
            },
        )
        else -> {
            Column(Modifier.fillMaxSize()) {
                if (isLocal && state.items.isEmpty() && !state.isSearch && pageCatalog !is BrowseTab.Favorite) {
                    EmptyLibraryScanHint(modifier = Modifier.weight(1f).fillMaxWidth())
                } else {
                    ListingGrid(
                        items = state.items,
                        scrollToTopTrigger = state.scrollToTopTrigger,
                        onOpen = viewModel::onListingOpen,
                        onToggleFavorite = viewModel::toggleFavorite,
                        showSourceChip = false,
                        sourceRegistry = state.sourceRegistry,
                        refreshEpoch = state.refreshEpoch,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                if (pageCatalog.paginated && state.lastPage > 1) {
                    PageBar(
                        page = state.page,
                        lastPage = state.lastPage,
                        onPrev = viewModel::prevPage,
                        onNext = viewModel::nextPage,
                        onJump = { viewModel.togglePageJump(true) },
                        enabled = !state.loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ComicsThemeScaffold(
    isImmersive: Boolean = false,
    content: @Composable () -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.fillMaxSize(),
    ) { _ ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (isImmersive) Modifier else Modifier.statusBarsPadding()),
        ) {
            content()
        }
    }
}
