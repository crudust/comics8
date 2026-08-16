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
import com.comics8.monitor.data.ReadDirection
import com.comics8.monitor.data.ViewMode
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

    LaunchedEffect(pagerState.currentPage) {
        if (state.readerImages.isNotEmpty()) {
            viewModel.savePage(pagerState.currentPage)
        }
    }

    val onAdvance = {
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
    val totalSpreads = (state.readerImages.size + 1) / 2
    val initialSpread = remember(state.currentEpisode?.wrId) {
        ((state.currentEpisode?.lastReadPage ?: 0) / 2).coerceIn(0, (totalSpreads - 1).coerceAtLeast(0))
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

    LaunchedEffect(pagerState.currentPage) {
        if (state.readerImages.isNotEmpty()) {
            viewModel.savePage(pagerState.currentPage * 2)
        }
    }

    val onAdvance = {
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
    val curImg1 = currentSpread * 2
    val curImg2 = currentSpread * 2 + 1
    val currentRangeText = if (curImg2 < state.readerImages.size) {
        "${curImg1 + 1}-${curImg2 + 1} / ${state.readerImages.size}"
    } else {
        "${curImg1 + 1} / ${state.readerImages.size}"
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
