package com.comics8.monitor.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comics8.monitor.data.Catalog
import com.comics8.monitor.data.ViewMode
import com.comics8.monitor.ui.browse.ListingGrid
import com.comics8.monitor.ui.components.BackupDialog
import com.comics8.monitor.ui.components.ErrorPane
import com.comics8.monitor.ui.components.LoadingPane
import com.comics8.monitor.ui.components.PageJumpDialog
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
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val isReader = state.screen == Screen.Reader

    LaunchedEffect(screenWidthDp) {
        viewModel.updateScreenWidthDp(screenWidthDp)
    }

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val window = activity?.window
    DisposableEffect(isReader, window) {
        if (window == null) return@DisposableEffect onDispose { }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    if (state.screen == Screen.Reader) {
        BackHandler { viewModel.closeReader() }
    } else if (state.screen == Screen.Series) {
        BackHandler { viewModel.closeSeries() }
    } else if (state.screen == Screen.History) {
        BackHandler { viewModel.closeHistory() }
    } else if (state.isSearch) {
        BackHandler { viewModel.selectCatalog(state.catalog) }
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
                        state.loading && state.items.isEmpty() -> LoadingPane()
                        state.error != null && state.items.isEmpty() -> ErrorPane(
                            message = state.error!!,
                            onRetry = { viewModel.refresh(force = true) },
                        )
                        state.isSearch && state.items.isEmpty() -> ErrorPane(
                            message = "\"${state.searchQuery}\" 검색 결과가 없습니다.",
                            actionLabel = "목록으로",
                            onRetry = { viewModel.selectCatalog(state.catalog) },
                        )
                        state.catalog == Catalog.FAVORITE && state.items.isEmpty() -> ErrorPane(
                            message = "즐겨찾기한 작품이 없습니다.",
                            actionLabel = "최신으로",
                            onRetry = { viewModel.selectCatalog(Catalog.LATEST) },
                        )
                        else -> ListingGrid(
                            items = state.items,
                            scrollToTopTrigger = state.scrollToTopTrigger,
                            onOpen = viewModel::openSeries,
                            onToggleFavorite = viewModel::toggleFavorite,
                        )
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
        if (state.showBackupDialog) {
            BackupDialog(
                state = state,
                viewModel = viewModel,
                onDismiss = viewModel::closeBackupDialog,
            )
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
