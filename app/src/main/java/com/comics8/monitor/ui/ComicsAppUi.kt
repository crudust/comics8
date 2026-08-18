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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comics8.core.model.BrowseTab
import com.comics8.monitor.ui.browse.ListingGrid
import com.comics8.monitor.ui.components.ArtistPickDialog
import com.comics8.monitor.ui.components.ErrorPane
import com.comics8.monitor.ui.components.LoadingPane
import com.comics8.monitor.ui.components.PageBar
import com.comics8.monitor.ui.components.PageJumpDialog
import com.comics8.monitor.ui.components.SyncDialog
import com.comics8.monitor.ui.components.TopBar
import com.comics8.monitor.ui.history.HistoryPane
import com.comics8.monitor.ui.reader.ReaderPane
import com.comics8.monitor.ui.series.SeriesPane

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicsAppUi(viewModel: ListingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isReader = state.screen == Screen.Reader
    val context = LocalContext.current
    val config = LocalConfiguration.current

    val catalogs = state.browseTabs

    LaunchedEffect(config.screenWidthDp) {
        viewModel.updateScreenWidthDp(config.screenWidthDp)
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
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val canGoBack = state.screen != Screen.Browse || state.isSearch || state.showPageJump || state.showSyncDialog || state.showUpdateDialog || state.showDownloadOptions || state.artistPick != null
    BackHandler(enabled = canGoBack) {
        viewModel.goBack()
    }

    ComicsThemeScaffold(isImmersive = isReader) {
        if (isReader) {
            ReaderPane(state = state, viewModel = viewModel)
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
                Column(Modifier.fillMaxSize()) {
                    TopBar(state = state, viewModel = viewModel)
                    when {
                        state.screen == Screen.Series -> SeriesPane(state = state, viewModel = viewModel)
                        state.screen == Screen.History -> HistoryPane(state = state, viewModel = viewModel)
                        state.screen == Screen.Downloads -> com.comics8.monitor.ui.downloads.DownloadsPane(state = state, viewModel = viewModel)
                        state.isSearch -> {
                            when {
                                state.loading && state.items.isEmpty() -> LoadingPane()
                                state.error != null && state.items.isEmpty() -> ErrorPane(
                                    message = state.error!!,
                                    onRetry = { viewModel.refresh(force = true) },
                                )
                                state.items.isEmpty() -> ErrorPane(
                                    message = "\"${state.searchQuery}\" 검색 결과가 없습니다.",
                                    actionLabel = "목록으로",
                                    onRetry = { viewModel.selectTab(state.tab) },
                                )
                                else -> ListingGrid(
                                    items = state.items,
                                    scrollToTopTrigger = state.scrollToTopTrigger,
                                    onOpen = viewModel::onListingOpen,
                                    onToggleFavorite = viewModel::toggleFavorite,
                                    showSourceChip = false,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        else -> {
                            if (catalogs.isEmpty()) {
                                LoadingPane()
                            } else {
                                key(state.activeSourceId) {
                                    BrowseTabsPager(
                                        catalogs = catalogs,
                                        state = state,
                                        viewModel = viewModel,
                                    )
                                }
                            }
                        }
                    }
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
        val artistPick = state.artistPick
        if (artistPick != null) {
            ArtistPickDialog(
                artists = artistPick.item.artistChoices,
                onPick = viewModel::pickArtist,
                onDismiss = viewModel::dismissArtistPick,
            )
        }
    }
}

@Composable
private fun BrowseTabsPager(
    catalogs: List<BrowseTab>,
    state: ListingUiState,
    viewModel: ListingViewModel,
) {
    val browsePagerState = rememberPagerState(
        initialPage = catalogs.indexOf(state.tab).coerceAtLeast(0),
        pageCount = { catalogs.size },
    )

    LaunchedEffect(state.tab, catalogs) {
        val targetIndex = catalogs.indexOf(state.tab)
        if (targetIndex >= 0 && browsePagerState.currentPage != targetIndex) {
            browsePagerState.animateScrollToPage(targetIndex)
        }
    }

    LaunchedEffect(browsePagerState.currentPage, catalogs) {
        val targetCatalog = catalogs.getOrNull(browsePagerState.currentPage) ?: return@LaunchedEffect
        if (targetCatalog != state.tab && !state.isSearch && state.screen == Screen.Browse) {
            viewModel.selectTab(targetCatalog)
        }
    }

    HorizontalPager(
        state = browsePagerState,
        modifier = Modifier.fillMaxSize(),
    ) { pageIndex ->
        val pageCatalog = catalogs.getOrNull(pageIndex)
        if (pageCatalog == null) {
            LoadingPane()
            return@HorizontalPager
        }
        val isCurrent = pageCatalog == state.tab
        when {
            isCurrent && state.loading && state.items.isEmpty() -> LoadingPane()
            isCurrent && state.error != null && state.items.isEmpty() -> ErrorPane(
                message = state.error!!,
                onRetry = { viewModel.refresh(force = true) },
            )
            isCurrent && pageCatalog is BrowseTab.Favorite && state.items.isEmpty() -> ErrorPane(
                message = "즐겨찾기한 작품이 없습니다.",
                actionLabel = "최신으로",
                onRetry = {
                    viewModel.selectTab(
                        state.browseTabs.firstOrNull { it is BrowseTab.Remote } ?: state.tab,
                    )
                },
            )
            isCurrent -> {
                Column(Modifier.fillMaxSize()) {
                    ListingGrid(
                        items = state.items,
                        scrollToTopTrigger = state.scrollToTopTrigger,
                        onOpen = viewModel::onListingOpen,
                        onToggleFavorite = viewModel::toggleFavorite,
                        showSourceChip = pageCatalog is BrowseTab.Favorite,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    if (pageCatalog.paginated && state.lastPage > 1) {
                        PageBar(
                            page = state.page,
                            lastPage = state.lastPage,
                            onPrev = viewModel::prevPage,
                            onNext = viewModel::nextPage,
                            onJump = { viewModel.togglePageJump(true) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            else -> LoadingPane()
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
