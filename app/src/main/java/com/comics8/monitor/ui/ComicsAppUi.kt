package com.comics8.monitor.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ChromeReaderMode
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.comics8.monitor.data.Catalog
import com.comics8.monitor.data.EpisodeItem
import com.comics8.monitor.data.ReadDirection
import com.comics8.monitor.data.ToonItem
import com.comics8.monitor.data.ViewMode
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicsAppUi(viewModel: ListingViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val isPagingReader = state.screen == Screen.Reader && state.viewMode != ViewMode.SCROLL

    DisposableEffect(state.screen, state.viewMode) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }

        if (state.screen == Screen.Reader && state.viewMode != ViewMode.SCROLL) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
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
    } else if (state.isSearch) {
        BackHandler { viewModel.selectCatalog(state.catalog) }
    }

    ComicsThemeScaffold(isImmersive = isPagingReader) {
        if (isPagingReader) {
            ReaderPane(state, viewModel)
        } else {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { viewModel.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(Modifier.fillMaxSize()) {
                    TopBar(state = state, viewModel = viewModel)
                    when {
                        state.screen == Screen.Reader -> ReaderPane(state, viewModel)
                        state.screen == Screen.Series -> SeriesPane(state, viewModel)
                        state.loading && state.items.isEmpty() -> LoadingPane()
                        state.error != null && state.items.isEmpty() -> ErrorPane(state.error!!) {
                            viewModel.refresh(force = true)
                        }
                        state.isSearch && state.items.isEmpty() ->
                            ErrorPane(
                                message = "\"${state.searchQuery}\" 검색 결과가 없습니다.",
                                actionLabel = "목록으로",
                            ) {
                                viewModel.selectCatalog(state.catalog)
                            }
                        state.catalog == Catalog.FAVORITE && state.items.isEmpty() ->
                            ErrorPane(
                                message = "즐겨찾기한 작품이 없습니다.",
                                actionLabel = "최신으로",
                            ) {
                                viewModel.selectCatalog(Catalog.LATEST)
                            }
                        else -> ListingGrid(
                            items = state.items,
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
            Modifier
                .fillMaxSize()
                .then(if (isImmersive) Modifier else Modifier.statusBarsPadding()),
        ) {
            content()
        }
    }
}

@Composable
private fun TopBar(
    state: ListingUiState,
    viewModel: ListingViewModel,
    inOverlay: Boolean = false,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (inOverlay) {
                    Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                        .statusBarsPadding()
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.screen == Screen.Reader) {
                IconButton(onClick = viewModel::closeReader) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
            } else if (state.screen == Screen.Series) {
                IconButton(onClick = viewModel::closeSeries) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
            }
            Text(
                text = when (state.screen) {
                    Screen.Reader -> state.currentEpisode?.title.orEmpty().ifBlank { "만화 보기" }
                    Screen.Series -> state.series?.title.orEmpty()
                    Screen.Browse -> "Comics8"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.screen == Screen.Browse) {
                IconButton(onClick = viewModel::toggleSearchBar) {
                    Icon(
                        imageVector = if (state.searchExpanded) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (state.searchExpanded) "검색 닫기" else "검색",
                    )
                }
            }
            if (state.screen == Screen.Series) {
                IconButton(onClick = viewModel::toggleFavorite) {
                    Icon(
                        imageVector = if (state.seriesFavorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (state.seriesFavorited) "즐겨찾기 해제" else "즐겨찾기",
                        tint = if (state.seriesFavorited) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        },
                    )
                }
            }
            if (state.screen == Screen.Reader) {
                // View Mode (스크롤, 페이지, 양쪽보기)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(2.dp),
                ) {
                    // 1. Scroll mode (세로 스크롤)
                    IconButton(
                        onClick = { viewModel.setViewMode(ViewMode.SCROLL) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = "세로 스크롤",
                            tint = if (state.viewMode == ViewMode.SCROLL) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    // 2. Single Page mode (단면 페이지)
                    IconButton(
                        onClick = { viewModel.setViewMode(ViewMode.PAGE) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = "단면 페이지",
                            tint = if (state.viewMode == ViewMode.PAGE) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    // 3. Dual Page mode (양쪽보기)
                    IconButton(
                        onClick = { viewModel.setViewMode(ViewMode.DUAL) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ChromeReaderMode,
                            contentDescription = "양쪽보기",
                            tint = if (state.viewMode == ViewMode.DUAL) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // Direction Switch (RL / LR) - Shown for paging modes
                if (state.viewMode != ViewMode.SCROLL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(2.dp),
                    ) {
                        // RL (우➔좌)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (state.readDirection == ReadDirection.RIGHT_TO_LEFT) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        androidx.compose.ui.graphics.Color.Transparent
                                    },
                                )
                                .clickable { viewModel.setReadDirection(ReadDirection.RIGHT_TO_LEFT) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "RL",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (state.readDirection == ReadDirection.RIGHT_TO_LEFT) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        // LR (좌➔우)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (state.readDirection == ReadDirection.LEFT_TO_RIGHT) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        androidx.compose.ui.graphics.Color.Transparent
                                    },
                                )
                                .clickable { viewModel.setReadDirection(ReadDirection.LEFT_TO_RIGHT) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "LR",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (state.readDirection == ReadDirection.LEFT_TO_RIGHT) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(Icons.Default.Refresh, contentDescription = "새로고침")
            }
        }
        if (state.screen == Screen.Browse) {
            AnimatedVisibility(visible = state.searchExpanded) {
                SearchBar(state = state, viewModel = viewModel)
            }
            if (state.isSearch) {
                Text(
                    text = "검색 · ${state.items.size}건",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Catalog.entries.forEach { catalog ->
                    FilterChip(
                        selected = !state.isSearch && state.catalog == catalog,
                        onClick = { viewModel.selectCatalog(catalog) },
                        label = { Text(catalog.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }
        val showPager = when (state.screen) {
            Screen.Series -> state.episodeLastPage > 1
            Screen.Browse -> !state.isSearch && state.catalog.paginated && state.lastPage > 1
            Screen.Reader -> false
        }
        if (showPager) {
            val page = if (state.screen == Screen.Series) state.episodePage else state.page
            val last = if (state.screen == Screen.Series) state.episodeLastPage else state.lastPage
            PageBar(
                page = page,
                lastPage = last,
                onPrev = {
                    if (state.screen == Screen.Series) {
                        viewModel.goToEpisodePage(page - 1)
                    } else {
                        viewModel.prevPage()
                    }
                },
                onNext = {
                    if (state.screen == Screen.Series) {
                        viewModel.goToEpisodePage(page + 1)
                    } else {
                        viewModel.nextPage()
                    }
                },
                onJump = { viewModel.togglePageJump(true) },
            )
        }
        state.error?.takeIf { state.items.isNotEmpty() }?.let { msg ->
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun SearchBar(state: ListingUiState, viewModel: ListingViewModel) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(state.searchExpanded) {
        if (state.searchExpanded) requester.requestFocus()
    }
    OutlinedTextField(
        value = state.searchInput,
        onValueChange = viewModel::updateSearchInput,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp)
            .focusRequester(requester),
        singleLine = true,
        placeholder = { Text("제목 검색") },
        trailingIcon = {
            if (state.searchInput.isNotEmpty()) {
                IconButton(onClick = { viewModel.updateSearchInput("") }) {
                    Icon(Icons.Default.Close, contentDescription = "검색어 지우기")
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                focus.clearFocus()
                viewModel.submitSearch()
            },
        ),
    )
}

@Composable
private fun PageBar(
    page: Int,
    lastPage: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJump: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = onPrev, enabled = page > 1) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 페이지")
        }
        TextButton(onClick = onJump) {
            Text("$page / $lastPage")
        }
        IconButton(onClick = onNext, enabled = page < lastPage) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "다음 페이지")
        }
    }
}

@Composable
private fun ListingGrid(
    items: List<ToonItem>,
    onOpen: (ToonItem) -> Unit,
    onToggleFavorite: (ToonItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 118.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            ToonCard(
                item = item,
                onClick = { onOpen(item) },
                onToggleFavorite = { onToggleFavorite(item) },
            )
        }
    }
}

@Composable
private fun ToonCard(
    item: ToonItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = item.thumbUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            ) {
                if (item.isNew) Badge("NEW")
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (item.isFavorite) "즐겨찾기 해제" else "즐겨찾기",
                    tint = if (item.isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f))
                        .padding(2.dp),
                )
            }
        }
        Text(
            text = item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onPrimary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
private fun SeriesPane(state: ListingUiState, viewModel: ListingViewModel) {
    when {
        state.episodeLoading && state.episodes.isEmpty() -> LoadingPane()
        state.episodeError != null && state.episodes.isEmpty() ->
            ErrorPane(state.episodeError) { viewModel.refresh() }
        else -> {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.episodes, key = { it.wrId }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        onClick = { viewModel.openEpisode(episode) },
                    )
                }
            }
        }
    }
}

private fun formatReadDate(millis: Long): String {
    val sdf = java.text.SimpleDateFormat("yy.MM.dd", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(millis))
}

@Composable
private fun EpisodeRow(
    episode: EpisodeItem,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            Modifier
                .size(width = 72.dp, height = 54.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (episode.thumbUrl != null) {
                AsyncImage(
                    model = episode.thumbUrl,
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(
            Modifier
                .padding(horizontal = 10.dp)
                .weight(1f),
        ) {
            Text(
                text = episode.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (episode.isRead) FontWeight.Normal else FontWeight.Medium,
                color = if (episode.isRead) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (!episode.date.isNullOrBlank()) {
                Text(
                    text = episode.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (episode.isRead) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = 6.dp),
            ) {
                Text(
                    text = "읽음",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (episode.readAt != null) {
                    Text(
                        text = formatReadDate(episode.readAt),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "회차 보기",
            tint = if (episode.isRead) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
            modifier = Modifier
                .padding(end = 4.dp)
                .size(18.dp),
        )
    }
}

@Composable
private fun ReaderPane(state: ListingUiState, viewModel: ListingViewModel) {
    when {
        state.readerLoading && state.readerImages.isEmpty() -> LoadingPane()
        state.readerError != null && state.readerImages.isEmpty() ->
            ErrorPane(state.readerError) { viewModel.refresh() }
        state.readerImages.isEmpty() ->
            ErrorPane(
                message = "불러올 이미지가 없습니다.",
                actionLabel = "회차 목록으로",
            ) {
                viewModel.closeReader()
            }
        state.viewMode == ViewMode.SCROLL -> {
            ReaderScrollPane(state, viewModel)
        }
        state.viewMode == ViewMode.PAGE -> {
            ReaderSinglePagePane(state, viewModel)
        }
        else -> {
            ReaderDualPagePane(state, viewModel)
        }
    }
}

@Composable
private fun ReaderScrollPane(state: ListingUiState, viewModel: ListingViewModel) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        itemsIndexed(state.readerImages, key = { index, url -> "$index-$url" }) { index, imageUrl ->
            AsyncImage(
                model = imageUrl,
                contentDescription = "페이지 ${index + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(),
            )
        }
        item {
            ReaderFooter(
                state = state,
                onPrev = viewModel::openPrevEpisode,
                onNext = viewModel::openNextEpisode,
                onClose = viewModel::closeReader,
            )
        }
    }
}

@Composable
private fun ReaderSinglePagePane(state: ListingUiState, viewModel: ListingViewModel) {
    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { state.readerImages.size },
    )
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Horizontal Pager View (Single page)
        HorizontalPager(
            state = pagerState,
            reverseLayout = isR2L,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = state.readerImages[page],
                    contentDescription = "페이지 ${page + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Tap Zones: Left 38%, Center 24% (Controls), Right 38%
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        scope.launch {
                            if (isR2L) {
                                if (pagerState.currentPage < state.readerImages.size - 1) {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                } else if (state.hasNextEpisode) {
                                    viewModel.openNextEpisode()
                                }
                            } else {
                                if (pagerState.currentPage > 0) {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                } else if (state.hasPrevEpisode) {
                                    viewModel.openPrevEpisode()
                                }
                            }
                        }
                    },
            )
            Box(
                modifier = Modifier
                    .weight(0.24f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        controlsVisible = !controlsVisible
                    },
            )
            Box(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        scope.launch {
                            if (isR2L) {
                                if (pagerState.currentPage > 0) {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                } else if (state.hasPrevEpisode) {
                                    viewModel.openPrevEpisode()
                                }
                            } else {
                                if (pagerState.currentPage < state.readerImages.size - 1) {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                } else if (state.hasNextEpisode) {
                                    viewModel.openNextEpisode()
                                }
                            }
                        }
                    },
            )
        }

        // Floating Page Indicator
        if (!controlsVisible && state.readerImages.isNotEmpty()) {
            Text(
                text = "${pagerState.currentPage + 1} / ${state.readerImages.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // Top Controls Overlay
        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(
                state = state,
                viewModel = viewModel,
                inOverlay = true,
            )
        }

        // Bottom Controls Overlay
        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderSinglePageBottomBar(
                state = state,
                pagerState = pagerState,
                onPageChange = { target ->
                    scope.launch { pagerState.scrollToPage(target) }
                },
                onPrevEp = viewModel::openPrevEpisode,
                onNextEp = viewModel::openNextEpisode,
                onClose = viewModel::closeReader,
            )
        }
    }
}

@Composable
private fun ReaderDualPagePane(state: ListingUiState, viewModel: ListingViewModel) {
    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT
    val totalSpreads = (state.readerImages.size + 1) / 2
    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { totalSpreads },
    )
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(false) }

    val currentSpread = pagerState.currentPage
    val curImg1 = currentSpread * 2
    val curImg2 = currentSpread * 2 + 1
    val currentRangeText = if (curImg2 < state.readerImages.size) {
        "${curImg1 + 1}-${curImg2 + 1} / ${state.readerImages.size}"
    } else {
        "${curImg1 + 1} / ${state.readerImages.size}"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Horizontal Pager View (Spreads of 2 pages side-by-side)
        HorizontalPager(
            state = pagerState,
            reverseLayout = isR2L,
            modifier = Modifier.fillMaxSize(),
        ) { spread ->
            val img1Index = spread * 2
            val img2Index = spread * 2 + 1
            val firstImg = state.readerImages.getOrNull(img1Index)
            val secondImg = state.readerImages.getOrNull(img2Index)

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isR2L) {
                    // Right-to-Left: Left side is next page (img2), Right side is first page (img1)
                    if (secondImg != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                model = secondImg,
                                contentDescription = "페이지 ${img2Index + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    if (firstImg != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                model = firstImg,
                                contentDescription = "페이지 ${img1Index + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                } else {
                    // Left-to-Right: Left side is first page (img1), Right side is next page (img2)
                    if (firstImg != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                model = firstImg,
                                contentDescription = "페이지 ${img1Index + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (secondImg != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                model = secondImg,
                                contentDescription = "페이지 ${img2Index + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Tap Zones Overlay: Left 38%, Center 24% (Toggle Controls), Right 38%
        Row(modifier = Modifier.fillMaxSize()) {
            // Left Tap Zone
            Box(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        scope.launch {
                            if (isR2L) {
                                if (pagerState.currentPage < totalSpreads - 1) {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                } else if (state.hasNextEpisode) {
                                    viewModel.openNextEpisode()
                                }
                            } else {
                                if (pagerState.currentPage > 0) {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                } else if (state.hasPrevEpisode) {
                                    viewModel.openPrevEpisode()
                                }
                            }
                        }
                    },
            )
            // Center Tap Zone (Toggles Controls)
            Box(
                modifier = Modifier
                    .weight(0.24f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        controlsVisible = !controlsVisible
                    },
            )
            // Right Tap Zone
            Box(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        scope.launch {
                            if (isR2L) {
                                if (pagerState.currentPage > 0) {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                } else if (state.hasPrevEpisode) {
                                    viewModel.openPrevEpisode()
                                }
                            } else {
                                if (pagerState.currentPage < totalSpreads - 1) {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                } else if (state.hasNextEpisode) {
                                    viewModel.openNextEpisode()
                                }
                            }
                        }
                    },
            )
        }

        // Floating Page Indicator (Visible when controls are hidden)
        if (!controlsVisible && state.readerImages.isNotEmpty()) {
            Text(
                text = currentRangeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        // Top Controls Overlay
        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopBar(
                state = state,
                viewModel = viewModel,
                inOverlay = true,
            )
        }

        // Bottom Controls Overlay
        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderDualPageBottomBar(
                state = state,
                pagerState = pagerState,
                totalSpreads = totalSpreads,
                currentRangeText = currentRangeText,
                onSpreadChange = { targetSpread ->
                    scope.launch { pagerState.scrollToPage(targetSpread) }
                },
                onPrevEp = viewModel::openPrevEpisode,
                onNextEp = viewModel::openNextEpisode,
                onClose = viewModel::closeReader,
            )
        }
    }
}

@Composable
private fun ReaderSinglePageBottomBar(
    state: ListingUiState,
    pagerState: PagerState,
    onPageChange: (Int) -> Unit,
    onPrevEp: () -> Unit,
    onNextEp: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "${pagerState.currentPage + 1} / ${state.readerImages.size}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 8.dp),
            )
            if (state.readerImages.size > 1) {
                Slider(
                    value = (pagerState.currentPage + 1).toFloat(),
                    onValueChange = { onPageChange(it.toInt() - 1) },
                    valueRange = 1f..state.readerImages.size.toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        ) {
            OutlinedButton(
                onClick = onPrevEp,
                enabled = state.hasPrevEpisode,
                modifier = Modifier.weight(1f),
            ) {
                Text("이전화")
            }
            Button(
                onClick = onClose,
                modifier = Modifier.weight(1f),
            ) {
                Text("목록")
            }
            OutlinedButton(
                onClick = onNextEp,
                enabled = state.hasNextEpisode,
                modifier = Modifier.weight(1f),
            ) {
                Text("다음화")
            }
        }
    }
}

@Composable
private fun ReaderDualPageBottomBar(
    state: ListingUiState,
    pagerState: PagerState,
    totalSpreads: Int,
    currentRangeText: String,
    onSpreadChange: (Int) -> Unit,
    onPrevEp: () -> Unit,
    onNextEp: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = currentRangeText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 8.dp),
            )
            if (totalSpreads > 1) {
                Slider(
                    value = (pagerState.currentPage + 1).toFloat(),
                    onValueChange = { onSpreadChange(it.toInt() - 1) },
                    valueRange = 1f..totalSpreads.toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        ) {
            OutlinedButton(
                onClick = onPrevEp,
                enabled = state.hasPrevEpisode,
                modifier = Modifier.weight(1f),
            ) {
                Text("이전화")
            }
            Button(
                onClick = onClose,
                modifier = Modifier.weight(1f),
            ) {
                Text("목록")
            }
            OutlinedButton(
                onClick = onNextEp,
                enabled = state.hasNextEpisode,
                modifier = Modifier.weight(1f),
            ) {
                Text("다음화")
            }
        }
    }
}

@Composable
private fun ReaderFooter(
    state: ListingUiState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Text(
            text = "${state.currentEpisode?.title.orEmpty()} (총 ${state.readerImages.size}장)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = onPrev,
                enabled = state.hasPrevEpisode,
                modifier = Modifier.weight(1f),
            ) {
                Text("이전화")
            }
            Button(
                onClick = onClose,
                modifier = Modifier.weight(1f),
            ) {
                Text("목록")
            }
            OutlinedButton(
                onClick = onNext,
                enabled = state.hasNextEpisode,
                modifier = Modifier.weight(1f),
            ) {
                Text("다음화")
            }
        }
    }
}

@Composable
private fun LoadingPane() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorPane(
    message: String,
    actionLabel: String = "다시 시도",
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text(actionLabel) }
    }
}

@Composable
private fun PageJumpDialog(
    current: Int,
    last: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(current.toString()) }
    val focus = LocalFocusManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("페이지 이동") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(5) },
                label = { Text("1 ~ $last") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focus.clearFocus()
                        text.toIntOrNull()?.let(onConfirm)
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { text.toIntOrNull()?.let(onConfirm) },
                enabled = text.toIntOrNull() != null,
            ) { Text("이동") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}
