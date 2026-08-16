package com.comics8.monitor.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.comics8.monitor.data.DualSpread
import com.comics8.monitor.data.ReadDirection
import com.comics8.monitor.data.ViewMode
import com.comics8.monitor.data.buildDualSpreads
import com.comics8.monitor.ui.ListingUiState
import com.comics8.monitor.ui.ListingViewModel
import com.comics8.monitor.ui.components.EpisodeBoundaryPrompt
import com.comics8.monitor.ui.components.ErrorPane
import com.comics8.monitor.ui.components.LoadingPane
import com.comics8.monitor.ui.components.TopBar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ReaderPane(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
) {
    when {
        state.readerLoading && state.readerImages.isEmpty() -> LoadingPane(modifier)
        state.readerError != null && state.readerImages.isEmpty() ->
            ErrorPane(
                message = state.readerError,
                modifier = modifier,
                onRetry = viewModel::refresh,
            )
        state.readerImages.isEmpty() ->
            ErrorPane(
                message = "불러올 이미지가 없습니다.",
                actionLabel = "회차 목록으로",
                modifier = modifier,
                onRetry = viewModel::closeReader,
            )
        state.viewMode == ViewMode.SCROLL -> {
            ReaderScrollPane(state, viewModel, modifier)
        }
        state.viewMode == ViewMode.PAGE -> {
            ReaderSinglePagePane(state, viewModel, modifier)
        }
        else -> {
            ReaderDualPagePane(state, viewModel, modifier)
        }
    }
}

@Composable
private fun ReaderScrollPane(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
) {
    val initialIndex = remember(state.currentEpisode?.wrId) {
        (state.currentEpisode?.lastReadPage ?: 0).coerceIn(0, (state.readerImages.size - 1).coerceAtLeast(0))
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(false) }

    // Automatically hide controls when scrolling starts
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            controlsVisible = false
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (state.readerImages.isNotEmpty()) {
            viewModel.savePage(listState.firstVisibleItemIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(state.readerImages, key = { index, url -> "$index-$url" }) { index, imageUrl ->
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "페이지 ${index + 1}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            controlsVisible = !controlsVisible
                        },
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

        // Floating Page Indicator (Visible when controls are hidden)
        if (!controlsVisible && state.readerImages.isNotEmpty()) {
            Text(
                text = "${(listState.firstVisibleItemIndex + 1).coerceAtMost(state.readerImages.size)} / ${state.readerImages.size}",
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
            ReaderBottomBar(
                currentRangeText = "${(listState.firstVisibleItemIndex + 1).coerceAtMost(state.readerImages.size)} / ${state.readerImages.size}",
                currentPage = listState.firstVisibleItemIndex + 1,
                maxPages = state.readerImages.size,
                hasPrevEpisode = state.hasPrevEpisode,
                hasNextEpisode = state.hasNextEpisode,
                onPageChange = { targetPage ->
                    scope.launch { listState.scrollToItem(targetPage - 1) }
                },
                onPrevEp = viewModel::openPrevEpisode,
                onNextEp = viewModel::openNextEpisode,
                onClose = viewModel::closeReader,
            )
        }
    }
}

@Composable
private fun ReaderSinglePagePane(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
) {
    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT
    val initialPage = remember(state.currentEpisode?.wrId) {
        (state.currentEpisode?.lastReadPage ?: 0).coerceIn(0, (state.readerImages.size - 1).coerceAtLeast(0))
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { state.readerImages.size },
    )
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(false) }
    var nextPromptVisible by remember { mutableStateOf(false) }
    var prevPromptVisible by remember { mutableStateOf(false) }
    var promptJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(state.currentEpisode?.wrId, pagerState.currentPage) {
        nextPromptVisible = false
        prevPromptVisible = false
    }

    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) {
            controlsVisible = false
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        if (state.readerImages.isNotEmpty()) {
            viewModel.savePage(pagerState.currentPage)
        }
    }

    val onAdvance = {
        controlsVisible = false
        if (pagerState.currentPage < state.readerImages.size - 1) {
            nextPromptVisible = false
            prevPromptVisible = false
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
        } else {
            if (nextPromptVisible) {
                nextPromptVisible = false
                if (state.hasNextEpisode) {
                    viewModel.openNextEpisode()
                } else {
                    viewModel.closeSeries()
                }
            } else {
                nextPromptVisible = true
                prevPromptVisible = false
                promptJob?.cancel()
                promptJob = scope.launch {
                    delay(3000)
                    nextPromptVisible = false
                }
            }
        }
    }

    val onRetreat = {
        controlsVisible = false
        if (pagerState.currentPage > 0) {
            nextPromptVisible = false
            prevPromptVisible = false
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        } else {
            if (prevPromptVisible) {
                prevPromptVisible = false
                if (state.hasPrevEpisode) {
                    viewModel.openPrevEpisode()
                } else {
                    viewModel.closeSeries()
                }
            } else {
                prevPromptVisible = true
                nextPromptVisible = false
                promptJob?.cancel()
                promptJob = scope.launch {
                    delay(3000)
                    prevPromptVisible = false
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
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
                        if (isR2L) onAdvance() else onRetreat()
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
                        nextPromptVisible = false
                        prevPromptVisible = false
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
                        if (isR2L) onRetreat() else onAdvance()
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

        // Boundary Confirmation Prompt
        EpisodeBoundaryPrompt(
            visible = nextPromptVisible,
            title = if (state.hasNextEpisode) "마지막 페이지입니다" else "마지막 회차입니다",
            subtitle = if (state.hasNextEpisode) "한 번 더 클릭하면 다음 회차로 이동" else "한 번 더 클릭하면 메인 목록으로 이동",
            onClick = {
                nextPromptVisible = false
                if (state.hasNextEpisode) {
                    viewModel.openNextEpisode()
                } else {
                    viewModel.closeSeries()
                }
            },
        )

        EpisodeBoundaryPrompt(
            visible = prevPromptVisible,
            title = if (state.hasPrevEpisode) "첫 페이지입니다" else "첫 번째 회차입니다",
            subtitle = if (state.hasPrevEpisode) "한 번 더 클릭하면 이전 회차로 이동" else "한 번 더 클릭하면 메인 목록으로 이동",
            onClick = {
                prevPromptVisible = false
                if (state.hasPrevEpisode) {
                    viewModel.openPrevEpisode()
                } else {
                    viewModel.closeSeries()
                }
            },
        )

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
            ReaderBottomBar(
                currentRangeText = "${pagerState.currentPage + 1} / ${state.readerImages.size}",
                currentPage = pagerState.currentPage + 1,
                maxPages = state.readerImages.size,
                hasPrevEpisode = state.hasPrevEpisode,
                hasNextEpisode = state.hasNextEpisode,
                onPageChange = { targetPage ->
                    scope.launch { pagerState.scrollToPage(targetPage - 1) }
                },
                onPrevEp = viewModel::openPrevEpisode,
                onNextEp = viewModel::openNextEpisode,
                onClose = viewModel::closeReader,
            )
        }
    }
}

@Composable
private fun ReaderDualPagePane(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
) {
    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT
    val spreads = remember(state.readerImages.size, state.imageAspectRatios) {
        buildDualSpreads(state.readerImages.size, state.imageAspectRatios)
    }
    val totalSpreads = spreads.size.coerceAtLeast(1)
    val initialSpread = remember(state.currentEpisode?.wrId, spreads) {
        val targetPage = state.currentEpisode?.lastReadPage ?: 0
        val idx = spreads.indexOfFirst { spread ->
            when (spread) {
                is DualSpread.Single -> spread.index >= targetPage
                is DualSpread.Dual -> spread.secondIndex >= targetPage
            }
        }
        if (idx >= 0) idx.coerceIn(0, (totalSpreads - 1).coerceAtLeast(0)) else 0
    }
    val pagerState = rememberPagerState(
        initialPage = initialSpread,
        pageCount = { totalSpreads },
    )
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(false) }
    var nextPromptVisible by remember { mutableStateOf(false) }
    var prevPromptVisible by remember { mutableStateOf(false) }
    var promptJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(state.currentEpisode?.wrId, pagerState.currentPage) {
        nextPromptVisible = false
        prevPromptVisible = false
    }

    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) {
            controlsVisible = false
        }
    }

    LaunchedEffect(pagerState.currentPage, spreads) {
        if (state.readerImages.isNotEmpty() && spreads.isNotEmpty()) {
            val spread = spreads.getOrNull(pagerState.currentPage)
            val firstPageIndex = when (spread) {
                is DualSpread.Single -> spread.index
                is DualSpread.Dual -> spread.firstIndex
                null -> 0
            }
            viewModel.savePage(firstPageIndex)
        }
    }

    val onAdvance = {
        controlsVisible = false
        if (pagerState.currentPage < totalSpreads - 1) {
            nextPromptVisible = false
            prevPromptVisible = false
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
        } else {
            if (nextPromptVisible) {
                nextPromptVisible = false
                if (state.hasNextEpisode) {
                    viewModel.openNextEpisode()
                } else {
                    viewModel.closeSeries()
                }
            } else {
                nextPromptVisible = true
                prevPromptVisible = false
                promptJob?.cancel()
                promptJob = scope.launch {
                    delay(3000)
                    nextPromptVisible = false
                }
            }
        }
    }

    val onRetreat = {
        controlsVisible = false
        if (pagerState.currentPage > 0) {
            nextPromptVisible = false
            prevPromptVisible = false
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
        } else {
            if (prevPromptVisible) {
                prevPromptVisible = false
                if (state.hasPrevEpisode) {
                    viewModel.openPrevEpisode()
                } else {
                    viewModel.closeSeries()
                }
            } else {
                prevPromptVisible = true
                nextPromptVisible = false
                promptJob?.cancel()
                promptJob = scope.launch {
                    delay(3000)
                    prevPromptVisible = false
                }
            }
        }
    }

    val currentSpread = pagerState.currentPage
    val currentSpreadObj = spreads.getOrNull(currentSpread)
    val currentRangeText = when (currentSpreadObj) {
        is DualSpread.Single -> "${currentSpreadObj.index + 1} / ${state.readerImages.size}"
        is DualSpread.Dual -> {
            val i1 = currentSpreadObj.firstIndex + 1
            val i2 = currentSpreadObj.secondIndex + 1
            if (isR2L) "$i2-$i1 / ${state.readerImages.size}" else "$i1-$i2 / ${state.readerImages.size}"
        }
        null -> ""
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        HorizontalPager(
            state = pagerState,
            reverseLayout = isR2L,
            modifier = Modifier.fillMaxSize(),
        ) { spreadIdx ->
            when (val spread = spreads.getOrNull(spreadIdx)) {
                is DualSpread.Single -> {
                    val ratio = state.imageAspectRatios[spread.index]
                    val isWide = ratio != null && ratio >= 1.0f

                    if (isWide) {
                        // Wide 2-page panoramic spread -> Display across full width in center
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                model = state.readerImages.getOrNull(spread.index),
                                contentDescription = "페이지 ${spread.index + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                                onSuccess = { result ->
                                    val w = result.result.drawable.intrinsicWidth
                                    val h = result.result.drawable.intrinsicHeight
                                    if (w > 0 && h > 0) {
                                        viewModel.recordImageAspectRatio(spread.index, w, h)
                                    }
                                },
                            )
                        }
                    } else {
                        // Isolated single portrait page -> Position in proper half-screen slot (Right for RL, Left for LR) touching center
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isR2L) {
                                Spacer(modifier = Modifier.weight(1f))
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    AsyncImage(
                                        model = state.readerImages.getOrNull(spread.index),
                                        contentDescription = "페이지 ${spread.index + 1}",
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.CenterStart,
                                        modifier = Modifier.fillMaxSize(),
                                        onSuccess = { result ->
                                            val w = result.result.drawable.intrinsicWidth
                                            val h = result.result.drawable.intrinsicHeight
                                            if (w > 0 && h > 0) {
                                                viewModel.recordImageAspectRatio(spread.index, w, h)
                                            }
                                        },
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    AsyncImage(
                                        model = state.readerImages.getOrNull(spread.index),
                                        contentDescription = "페이지 ${spread.index + 1}",
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.CenterEnd,
                                        modifier = Modifier.fillMaxSize(),
                                        onSuccess = { result ->
                                            val w = result.result.drawable.intrinsicWidth
                                            val h = result.result.drawable.intrinsicHeight
                                            if (w > 0 && h > 0) {
                                                viewModel.recordImageAspectRatio(spread.index, w, h)
                                            }
                                        },
                                    )
                                }
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                is DualSpread.Dual -> {
                    // Normal 2 portrait pages side-by-side: hug the center spine like a book
                    val leftIndex = if (isR2L) spread.secondIndex else spread.firstIndex
                    val rightIndex = if (isR2L) spread.firstIndex else spread.secondIndex

                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            AsyncImage(
                                model = state.readerImages.getOrNull(leftIndex),
                                contentDescription = "페이지 ${leftIndex + 1}",
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterEnd,
                                modifier = Modifier.fillMaxSize(),
                                onSuccess = { result ->
                                    val w = result.result.drawable.intrinsicWidth
                                    val h = result.result.drawable.intrinsicHeight
                                    if (w > 0 && h > 0) {
                                        viewModel.recordImageAspectRatio(leftIndex, w, h)
                                    }
                                },
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            AsyncImage(
                                model = state.readerImages.getOrNull(rightIndex),
                                contentDescription = "페이지 ${rightIndex + 1}",
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart,
                                modifier = Modifier.fillMaxSize(),
                                onSuccess = { result ->
                                    val w = result.result.drawable.intrinsicWidth
                                    val h = result.result.drawable.intrinsicHeight
                                    if (w > 0 && h > 0) {
                                        viewModel.recordImageAspectRatio(rightIndex, w, h)
                                    }
                                },
                            )
                        }
                    }
                }
                null -> {}
            }
        }

        // Tap Zones: Left 38%, Center 24% (Toggle Controls), Right 38%
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(0.38f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (isR2L) onAdvance() else onRetreat()
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
                        nextPromptVisible = false
                        prevPromptVisible = false
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
                        if (isR2L) onRetreat() else onAdvance()
                    },
            )
        }

        // Floating Page Indicator
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

        // Boundary Confirmation Prompt
        EpisodeBoundaryPrompt(
            visible = nextPromptVisible,
            title = if (state.hasNextEpisode) "마지막 페이지입니다" else "마지막 회차입니다",
            subtitle = if (state.hasNextEpisode) "한 번 더 클릭하면 다음 회차로 이동" else "한 번 더 클릭하면 메인 목록으로 이동",
            onClick = {
                nextPromptVisible = false
                if (state.hasNextEpisode) {
                    viewModel.openNextEpisode()
                } else {
                    viewModel.closeSeries()
                }
            },
        )

        EpisodeBoundaryPrompt(
            visible = prevPromptVisible,
            title = if (state.hasPrevEpisode) "첫 페이지입니다" else "첫 번째 회차입니다",
            subtitle = if (state.hasPrevEpisode) "한 번 더 클릭하면 이전 회차로 이동" else "한 번 더 클릭하면 메인 목록으로 이동",
            onClick = {
                prevPromptVisible = false
                if (state.hasPrevEpisode) {
                    viewModel.openPrevEpisode()
                } else {
                    viewModel.closeSeries()
                }
            },
        )

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
            ReaderBottomBar(
                currentRangeText = currentRangeText,
                currentPage = pagerState.currentPage + 1,
                maxPages = totalSpreads,
                hasPrevEpisode = state.hasPrevEpisode,
                hasNextEpisode = state.hasNextEpisode,
                onPageChange = { targetSpread ->
                    scope.launch { pagerState.scrollToPage(targetSpread - 1) }
                },
                onPrevEp = viewModel::openPrevEpisode,
                onNextEp = viewModel::openNextEpisode,
                onClose = viewModel::closeReader,
            )
        }
    }
}

@Composable
private fun ReaderBottomBar(
    currentRangeText: String,
    currentPage: Int,
    maxPages: Int,
    hasPrevEpisode: Boolean,
    hasNextEpisode: Boolean,
    onPageChange: (Int) -> Unit,
    onPrevEp: () -> Unit,
    onNextEp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (maxPages > 1) {
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
                Slider(
                    value = currentPage.coerceIn(1, maxPages).toFloat(),
                    onValueChange = { onPageChange(it.toInt()) },
                    valueRange = 1f..maxPages.toFloat(),
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
                enabled = hasPrevEpisode,
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
                enabled = hasNextEpisode,
                modifier = Modifier.weight(1f),
            ) {
                Text("다음화")
            }
        }
    }
}

@Composable
fun ReaderFooter(
    state: ListingUiState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
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
