package com.comics8.shared.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size as CoilSize
import com.comics8.core.app.AppUiState
import com.comics8.core.app.SharedAppController
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.model.DualSpread
import com.comics8.core.model.ImageHalf
import com.comics8.core.model.PageTapZone
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ReaderDomain
import com.comics8.core.model.ReaderTiles
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ViewMode
import com.comics8.core.model.buildDualSpreads
import com.comics8.core.model.buildSinglePageSlices
import com.comics8.shared.image.READER_LONG_EDGE_PX
import com.comics8.shared.image.READER_SHORT_EDGE_PX
import com.comics8.shared.image.imageCacheRole
import com.comics8.shared.image.readerImageSizing
import com.comics8.shared.ui.components.EpisodeBoundaryPrompt
import com.comics8.shared.ui.components.ErrorPane
import com.comics8.shared.ui.components.LoadingPane
import com.comics8.shared.ui.components.TopBar
import com.comics8.shared.ui.image.ReaderImage
import com.comics8.shared.ui.settings.ReaderSettingsPane
import com.comics8.shared.ui.theme.LocalStrings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class ReaderPlatformConfig(
    val scrollContentWidthFraction: Float = 1f,
    val bottomBarWidthFraction: Float = 1f,
    val compactTopBar: Boolean = false,
    val pagerUserScrollEnabled: Boolean = true,
    val respectTapZonePreference: Boolean = true,
    val tapEdgeFraction: Float = 0.35f,
    val indicatorAlignment: Alignment = Alignment.BottomEnd,
    val scrollPlaceholderMinHeight: Dp = 480.dp,
    val singlePreloadOffsets: List<Int> = listOf(2, 3),
    val dualPreloadOffsets: List<Int> = listOf(2),
    val retryReopensEpisode: Boolean = false,
    val showBottomBarIcons: Boolean = false,
    val showFooterEpisodeListButton: Boolean = true,
    val showViewerOptionsSheet: Boolean = false,
    val showReaderToast: Boolean = false,
    val applyNavigationBarsPadding: Boolean = false,
)

/** Reading position inside one page image, so tiling plan changes never move the reader. */
private data class ReaderScrollAnchor(val imageIndex: Int, val tileCount: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedReaderPane(
    state: AppUiState,
    controller: SharedAppController,
    config: ReaderPlatformConfig,
    zoomContainer: @Composable (
        resetKey: Any?,
        isActivePage: Boolean,
        onTap: (Offset, IntSize) -> Unit,
        onScaleChanged: (Float) -> Unit,
        content: @Composable (scale: Float) -> Unit,
    ) -> Unit,
    pagedModifier: @Composable (
        pagerState: PagerState,
        isR2L: Boolean,
        isCurrentPageZoomed: Boolean,
        onAdvance: () -> Unit,
        onRetreat: () -> Unit,
    ) -> Modifier = { _, _, _, _, _ -> Modifier },
    onRegisterNavActions: ((onAdvance: () -> Unit, onRetreat: () -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.readerLoading && state.readerImages.isEmpty() -> LoadingPane(Modifier.fillMaxSize())
            state.readerError != null && state.readerImages.isEmpty() ->
                ErrorPane(
                    message = state.readerError.orEmpty(),
                    actionLabel = strings.actionRetry,
                    modifier = Modifier.fillMaxSize(),
                    onRetry = {
                        if (config.retryReopensEpisode) {
                            state.currentEpisode?.let(controller::openEpisode) ?: controller.refresh()
                        } else {
                            controller.refresh()
                        }
                    },
                )
            state.readerImages.isEmpty() ->
                ErrorPane(
                    message = strings.errorNoImagesToLoad,
                    actionLabel = strings.navBackToEpisodeList,
                    modifier = Modifier.fillMaxSize(),
                    onRetry = controller::closeReader,
                )
            state.effectiveViewMode == ViewMode.SCROLL -> {
                SharedReaderScrollPane(
                    state = state,
                    controller = controller,
                    config = config,
                    zoomContainer = zoomContainer,
                    onRegisterNavActions = onRegisterNavActions,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            state.effectiveViewMode == ViewMode.PAGE ||
                state.effectiveViewMode == ViewMode.SPLIT ||
                state.effectiveViewMode == ViewMode.AUTO -> {
                SharedReaderSinglePagePane(
                    state = state,
                    controller = controller,
                    config = config,
                    splitWidePages = state.effectiveViewMode == ViewMode.SPLIT,
                    zoomContainer = zoomContainer,
                    pagedModifier = pagedModifier,
                    onRegisterNavActions = onRegisterNavActions,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                SharedReaderDualPagePane(
                    state = state,
                    controller = controller,
                    config = config,
                    zoomContainer = zoomContainer,
                    pagedModifier = pagedModifier,
                    onRegisterNavActions = onRegisterNavActions,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (config.showReaderToast) {
            val toast = state.readerToastMessage
            AnimatedVisibility(
                visible = toast != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp),
            ) {
                if (toast != null) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
                        shadowElevation = 6.dp,
                    ) {
                        Text(
                            text = toast,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }

    if (config.showViewerOptionsSheet && state.showViewerOptions) {
        ModalBottomSheet(
            onDismissRequest = controller::closeViewerOptions,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
            ) {
                ReaderSettingsPane(
                    state = state,
                    controller = controller,
                    showTapZoneAndVolume = true,
                )
            }
        }
    }
}

@Composable
private fun SharedReaderScrollPane(
    state: AppUiState,
    controller: SharedAppController,
    config: ReaderPlatformConfig,
    zoomContainer: @Composable (
        resetKey: Any?,
        isActivePage: Boolean,
        onTap: (Offset, IntSize) -> Unit,
        onScaleChanged: (Float) -> Unit,
        content: @Composable (scale: Float) -> Unit,
    ) -> Unit,
    onRegisterNavActions: ((onAdvance: () -> Unit, onRetreat: () -> Unit) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val contentWidthPx = (constraints.maxWidth * config.scrollContentWidthFraction).toInt().coerceAtLeast(1)
        val viewportHeightPx = constraints.maxHeight
        val readerMaxBitmapSize = remember(contentWidthPx) {
            CoilSize(contentWidthPx.coerceAtLeast(READER_SHORT_EDGE_PX), READER_LONG_EDGE_PX)
        }
        val tiles = remember(state.readerImages.size, state.imageAspectRatios, contentWidthPx, viewportHeightPx) {
            ReaderTiles.buildScrollItems(
                imageCount = state.readerImages.size,
                aspectRatios = state.imageAspectRatios,
                contentWidthPx = contentWidthPx,
                viewportHeightPx = viewportHeightPx,
            )
        }
        val currentTiles by rememberUpdatedState(tiles)
        val totalImages = state.readerImages.size
        val currentTotalImages by rememberUpdatedState(totalImages)
        val initialIndex = remember(state.currentEpisode?.wrId, tiles.isNotEmpty()) {
            val targetImage = (state.currentEpisode?.lastReadPage ?: 0).coerceIn(0, (totalImages - 1).coerceAtLeast(0))
            tiles.indexOfFirst { it.imageIndex >= targetImage }.coerceAtLeast(0)
        }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
        val scope = rememberCoroutineScope()
        var controlsVisible by remember { mutableStateOf(false) }

        fun firstItemOfImage(imageIndex: Int): Int =
            currentTiles.indexOfFirst { it.imageIndex >= imageIndex }.coerceAtLeast(0)

        val onAdvance: () -> Unit = remember(listState, scope) {
            {
                scope.launch {
                    val count = currentTotalImages
                    if (count > 0) {
                        val current = currentTiles.getOrNull(listState.firstVisibleItemIndex)?.imageIndex ?: 0
                        val next = (current + 1).coerceAtMost((count - 1).coerceAtLeast(0))
                        listState.animateScrollToItem(firstItemOfImage(next))
                    }
                }
            }
        }
        val onRetreat: () -> Unit = remember(listState, scope) {
            {
                scope.launch {
                    if (currentTotalImages > 0) {
                        val current = currentTiles.getOrNull(listState.firstVisibleItemIndex)?.imageIndex ?: 0
                        val prev = (current - 1).coerceAtLeast(0)
                        listState.animateScrollToItem(firstItemOfImage(prev))
                    }
                }
            }
        }

        LaunchedEffect(onAdvance, onRetreat) {
            onRegisterNavActions?.invoke(onAdvance, onRetreat)
        }

        // Preserve reading position on the same page image and relative depth when tiling grows.
        var scrollAnchor by remember { mutableStateOf<ReaderScrollAnchor?>(null) }
        LaunchedEffect(listState, currentTiles) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { itemIndex ->
                    currentTiles.getOrNull(itemIndex)?.let { tile ->
                        scrollAnchor = ReaderScrollAnchor(tile.imageIndex, tile.tileCount)
                    }
                }
        }
        LaunchedEffect(tiles) {
            val anchored = scrollAnchor ?: return@LaunchedEffect
            val firstItem = tiles.indexOfFirst { it.imageIndex >= anchored.imageIndex }
            if (firstItem < 0) return@LaunchedEffect
            val newTileCount = tiles[firstItem].tileCount
            if (newTileCount == anchored.tileCount) return@LaunchedEffect
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            val depth = if (visible != null && visible.size > 0) visible.offset.toFloat() / visible.size else 0f
            val target = firstItem + (depth * newTileCount).toInt().coerceIn(0, newTileCount - 1)
            if (target != listState.firstVisibleItemIndex) listState.scrollToItem(target)
        }

        var restoredWrId by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(state.currentEpisode?.wrId, tiles.isNotEmpty()) {
            val wrId = state.currentEpisode?.wrId
            if (wrId != null && wrId != restoredWrId && tiles.isNotEmpty()) {
                restoredWrId = wrId
                val target = state.currentEpisode?.lastReadPage ?: 0
                if (target > 0) {
                    val targetItem = currentTiles.indexOfFirst { it.imageIndex >= target }.coerceAtLeast(0)
                    if (targetItem != listState.firstVisibleItemIndex) {
                        listState.scrollToItem(targetItem)
                    }
                }
            }
        }

        LaunchedEffect(listState.firstVisibleItemIndex, tiles) {
            if (tiles.isNotEmpty()) {
                controller.savePage(currentTiles.getOrNull(listState.firstVisibleItemIndex)?.imageIndex ?: 0)
            }
        }

        val currentImageIndex = tiles.getOrNull(listState.firstVisibleItemIndex)?.imageIndex ?: 0
        val currentRangeText = remember(currentImageIndex, totalImages) {
            if (totalImages > 0) "${(currentImageIndex + 1).coerceAtMost(totalImages)} / $totalImages" else ""
        }

        LaunchedEffect(listState.isScrollInProgress) {
            if (listState.isScrollInProgress) {
                controlsVisible = false
            }
        }

        LaunchedEffect(listState, state.readerImages) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { firstItem ->
                    if (state.readerImages.isEmpty()) return@collect
                    val firstImage = currentTiles.getOrNull(firstItem)?.imageIndex ?: return@collect
                    val minKeep = (firstImage - 6).coerceAtLeast(0)
                    val maxKeep = (firstImage + 10).coerceAtMost(state.readerImages.size - 1)
                    for (i in 0 until minKeep) {
                        val url = state.readerImages.getOrNull(i) ?: continue
                        controller.evictReaderImage(url)
                    }
                    for (i in (maxKeep + 1) until state.readerImages.size) {
                        val url = state.readerImages.getOrNull(i) ?: continue
                        controller.evictReaderImage(url)
                    }
                }
        }

        zoomContainer(
            state.currentEpisode?.wrId,
            state.readerImages.isNotEmpty(),
            { _, _ -> controlsVisible = !controlsVisible },
            {},
        ) {
            LazyColumn(
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(tiles, key = { "${it.imageIndex}:${it.tileIndex}/${it.tileCount}" }) { tile ->
                    val imageUrl = state.readerImages.getOrNull(tile.imageIndex)
                    val ratio = ReaderTiles.tileAspectRatio(state.imageAspectRatios[tile.imageIndex], tile)
                    val itemModifier = if (ratio != null && ratio > 0f) {
                        Modifier
                            .fillMaxWidth(config.scrollContentWidthFraction)
                            .aspectRatio(ratio)
                    } else {
                        Modifier
                            .fillMaxWidth(config.scrollContentWidthFraction)
                            .defaultMinSize(minHeight = config.scrollPlaceholderMinHeight)
                            .wrapContentHeight()
                    }
                    ReaderImage(
                        url = imageUrl,
                        maxBitmapSize = readerMaxBitmapSize,
                        tile = tile,
                        contentDescription = strings.labelPageNumber(tile.imageIndex + 1),
                        contentScale = ContentScale.FillWidth,
                        modifier = itemModifier,
                        minPlaceholderHeight = config.scrollPlaceholderMinHeight,
                        onDimensionsLoaded = { w, h ->
                            controller.recordImageAspectRatio(tile.imageIndex, w, h * tile.tileCount)
                        },
                        onRefreshEpisode = controller::refreshReaderImages,
                    )
                }
                item {
                    SharedReaderFooter(
                        state = state,
                        config = config,
                        onPrev = controller::openPrevEpisode,
                        onNext = controller::openNextEpisode,
                        onClose = controller::closeReader,
                    )
                }
            }
        }

        SharedReaderOverlayControls(
            state = state,
            controller = controller,
            config = config,
            controlsVisible = controlsVisible,
            currentRangeText = currentRangeText,
            currentPage = (currentImageIndex + 1).coerceIn(1, totalImages.coerceAtLeast(1)),
            maxPages = totalImages,
            onPageChange = { targetPage ->
                val pageIdx = (targetPage - 1).coerceIn(0, (totalImages - 1).coerceAtLeast(0))
                scope.launch { listState.scrollToItem(firstItemOfImage(pageIdx)) }
            },
        )
    }
}

@Composable
private fun SharedReaderPagedLayout(
    state: AppUiState,
    controller: SharedAppController,
    config: ReaderPlatformConfig,
    pagerState: PagerState,
    isR2L: Boolean,
    currentRangeText: String,
    pagedModifier: @Composable (
        pagerState: PagerState,
        isR2L: Boolean,
        isCurrentPageZoomed: Boolean,
        onAdvance: () -> Unit,
        onRetreat: () -> Unit,
    ) -> Modifier,
    onRegisterNavActions: ((onAdvance: () -> Unit, onRetreat: () -> Unit) -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int, handleTap: (Offset, IntSize) -> Unit, onScaleChanged: (Float) -> Unit) -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(false) }
    var nextPromptVisible by remember { mutableStateOf(false) }
    var prevPromptVisible by remember { mutableStateOf(false) }
    var promptJob by remember { mutableStateOf<Job?>(null) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }

    LaunchedEffect(state.currentEpisode?.wrId, pagerState.currentPage) {
        nextPromptVisible = false
        prevPromptVisible = false
        isCurrentPageZoomed = false
    }

    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) {
            controlsVisible = false
        }
    }

    val currentIsR2L by rememberUpdatedState(isR2L)
    val currentNextPromptVisible by rememberUpdatedState(nextPromptVisible)
    val currentPrevPromptVisible by rememberUpdatedState(prevPromptVisible)
    val currentState by rememberUpdatedState(state)

    val navigate: (ReaderDomain.BoundaryDirection) -> Unit = remember(pagerState, scope) {
        { direction ->
            controlsVisible = false
            val isPromptActive = when (direction) {
                ReaderDomain.BoundaryDirection.ADVANCE -> currentNextPromptVisible
                ReaderDomain.BoundaryDirection.RETREAT -> currentPrevPromptVisible
            }
            when (val decision = ReaderDomain.resolveBoundaryDecision(
                currentPage = pagerState.currentPage,
                totalPages = pagerState.pageCount,
                direction = direction,
                isPromptActive = isPromptActive,
            )) {
                is ReaderDomain.BoundaryDecision.PageTurn -> {
                    if (!pagerState.isScrollInProgress) {
                        nextPromptVisible = false
                        prevPromptVisible = false
                        scope.launch { pagerState.animateScrollToPage(decision.targetPage) }
                    }
                }
                is ReaderDomain.BoundaryDecision.ShowPrompt -> {
                    val isAdvance = direction == ReaderDomain.BoundaryDirection.ADVANCE
                    nextPromptVisible = isAdvance
                    prevPromptVisible = !isAdvance
                    promptJob?.cancel()
                    promptJob = scope.launch {
                        delay(3000)
                        if (isAdvance) nextPromptVisible = false else prevPromptVisible = false
                    }
                }
                is ReaderDomain.BoundaryDecision.ConfirmNavigate -> {
                    nextPromptVisible = false
                    prevPromptVisible = false
                    when (direction) {
                        ReaderDomain.BoundaryDirection.ADVANCE -> {
                            if (currentState.hasNextEpisode) controller.openNextEpisode() else controller.closeReader()
                        }
                        ReaderDomain.BoundaryDirection.RETREAT -> {
                            if (currentState.hasPrevEpisode) controller.openPrevEpisode() else controller.closeReader()
                        }
                    }
                }
            }
        }
    }

    val onAdvance: () -> Unit = remember(navigate) { { navigate(ReaderDomain.BoundaryDirection.ADVANCE) } }
    val onRetreat: () -> Unit = remember(navigate) { { navigate(ReaderDomain.BoundaryDirection.RETREAT) } }

    LaunchedEffect(onAdvance, onRetreat) {
        onRegisterNavActions?.invoke(onAdvance, onRetreat)
    }

    val handleTap: (Offset, IntSize) -> Unit = { tapOffset, size ->
        val tapX = tapOffset.x
        val width = size.width.toFloat()
        val isNextOnLeft = if (config.respectTapZonePreference) {
            when (currentState.pageTapZone) {
                PageTapZone.FOLLOW_DIRECTION -> currentIsR2L
                PageTapZone.LEFT_NEXT -> true
                PageTapZone.RIGHT_NEXT -> false
            }
        } else {
            currentIsR2L
        }
        when {
            tapX < width * config.tapEdgeFraction -> if (isNextOnLeft) onAdvance() else onRetreat()
            tapX > width * (1f - config.tapEdgeFraction) -> if (isNextOnLeft) onRetreat() else onAdvance()
            else -> {
                controlsVisible = !controlsVisible
                nextPromptVisible = false
                prevPromptVisible = false
            }
        }
    }

    val extraModifier = pagedModifier(pagerState, isR2L, isCurrentPageZoomed, onAdvance, onRetreat)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(extraModifier),
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = config.pagerUserScrollEnabled,
            reverseLayout = isR2L,
            beyondViewportPageCount = 1,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                pagerSnapDistance = PagerSnapDistance.atMost(1),
            ),
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            content(page, handleTap) { scale ->
                if (page == pagerState.currentPage) {
                    isCurrentPageZoomed = scale > 1.05f
                }
            }
        }

        EpisodeBoundaryPrompt(
            visible = nextPromptVisible,
            title = if (state.hasNextEpisode) strings.promptLastPage else strings.promptLastEpisode,
            subtitle = if (state.hasNextEpisode) strings.promptNextEpisodeHint else strings.promptCloseReaderHint,
            onClick = {
                nextPromptVisible = false
                if (state.hasNextEpisode) {
                    controller.openNextEpisode()
                } else {
                    controller.closeReader()
                }
            },
        )

        EpisodeBoundaryPrompt(
            visible = prevPromptVisible,
            title = if (state.hasPrevEpisode) strings.promptFirstPage else strings.promptFirstEpisode,
            subtitle = if (state.hasPrevEpisode) strings.promptPrevEpisodeHint else strings.promptCloseReaderHint,
            onClick = {
                prevPromptVisible = false
                if (state.hasPrevEpisode) {
                    controller.openPrevEpisode()
                } else {
                    controller.closeReader()
                }
            },
        )

        SharedReaderOverlayControls(
            state = state,
            controller = controller,
            config = config,
            controlsVisible = controlsVisible,
            currentRangeText = currentRangeText,
            currentPage = pagerState.currentPage + 1,
            maxPages = pagerState.pageCount,
            onPageChange = { targetPage ->
                val pageIdx = (targetPage - 1).coerceIn(0, (pagerState.pageCount - 1).coerceAtLeast(0))
                scope.launch { pagerState.scrollToPage(pageIdx) }
            },
        )
    }
}

@Composable
private fun SharedReaderSinglePagePane(
    state: AppUiState,
    controller: SharedAppController,
    config: ReaderPlatformConfig,
    splitWidePages: Boolean,
    zoomContainer: @Composable (
        resetKey: Any?,
        isActivePage: Boolean,
        onTap: (Offset, IntSize) -> Unit,
        onScaleChanged: (Float) -> Unit,
        content: @Composable (scale: Float) -> Unit,
    ) -> Unit,
    pagedModifier: @Composable (
        pagerState: PagerState,
        isR2L: Boolean,
        isCurrentPageZoomed: Boolean,
        onAdvance: () -> Unit,
        onRetreat: () -> Unit,
    ) -> Modifier,
    onRegisterNavActions: ((onAdvance: () -> Unit, onRetreat: () -> Unit) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT
    val effectiveSplitMode = if (splitWidePages) SplitMode.SLICE else SplitMode.FIT
    val slices = remember(state.readerImages.size, state.imageAspectRatios, effectiveSplitMode, state.readDirection) {
        buildSinglePageSlices(state.readerImages.size, state.imageAspectRatios, effectiveSplitMode, state.readDirection)
    }
    val initialPage = remember(state.currentEpisode?.wrId) {
        val targetPage = state.currentEpisode?.lastReadPage ?: 0
        val idx = slices.indexOfFirst { it.imageIndex >= targetPage }
        if (idx >= 0) idx.coerceIn(0, (slices.size - 1).coerceAtLeast(0)) else 0
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { slices.size.coerceAtLeast(1) },
    )

    var restoredWrId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.currentEpisode?.wrId, state.readerImages.isNotEmpty()) {
        val wrId = state.currentEpisode?.wrId
        if (wrId != null && wrId != restoredWrId && state.readerImages.isNotEmpty()) {
            restoredWrId = wrId
            val target = state.currentEpisode?.lastReadPage ?: 0
            val idx = slices.indexOfFirst { it.imageIndex >= target }
            if (idx in 0 until pagerState.pageCount && idx != pagerState.currentPage) {
                pagerState.scrollToPage(idx)
            }
        }
    }

    var prevSlices by remember { mutableStateOf(slices) }
    LaunchedEffect(slices) {
        if (slices != prevSlices) {
            val oldSlice = prevSlices.getOrNull(pagerState.currentPage)
            if (oldSlice != null) {
                val activeImage = oldSlice.imageIndex
                val newIdx = slices.indexOfFirst { it.imageIndex == activeImage && it.half == oldSlice.half }
                    .takeIf { it >= 0 }
                    ?: slices.indexOfFirst { it.imageIndex == activeImage }
                if (newIdx in 0 until pagerState.pageCount && newIdx != pagerState.currentPage) {
                    pagerState.scrollToPage(newIdx)
                }
            }
            prevSlices = slices
        }
    }

    LaunchedEffect(pagerState.currentPage, slices) {
        if (state.readerImages.isNotEmpty() && slices.isNotEmpty()) {
            val slice = slices.getOrNull(pagerState.currentPage)
            if (slice != null) {
                controller.savePage(slice.imageIndex)
            }
        }
    }

    val currentSlices by rememberUpdatedState(slices)
    val platformContext = LocalPlatformContext.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val readerMaxBitmapSize = remember(constraints.maxWidth) {
            CoilSize(constraints.maxWidth.coerceAtLeast(READER_SHORT_EDGE_PX), READER_LONG_EDGE_PX)
        }
        LaunchedEffect(pagerState.currentPage, state.readerImages) {
            val imageLoader = SingletonImageLoader.get(platformContext)
            val targets = config.singlePreloadOffsets.mapNotNull { offset ->
                val nextSlice = currentSlices.getOrNull(pagerState.currentPage + offset) ?: return@mapNotNull null
                val url = state.readerImages.getOrNull(nextSlice.imageIndex) ?: return@mapNotNull null
                nextSlice.imageIndex to url
            }.distinctBy { it.second }
            for ((imageIndex, targetUrl) in targets) {
                val request = ImageRequest.Builder(platformContext)
                    .data(targetUrl)
                    .imageCacheRole(ImageCacheRole.READER, targetUrl)
                    .readerImageSizing(readerMaxBitmapSize)
                    .build()
                val result = imageLoader.execute(request)
                if (result is SuccessResult && result.image.width > 0 && result.image.height > 0) {
                    controller.recordImageAspectRatio(imageIndex, result.image.width, result.image.height)
                }
            }
        }

        SharedReaderPagedLayout(
            state = state,
            controller = controller,
            config = config,
            pagerState = pagerState,
            isR2L = isR2L,
            currentRangeText = "${pagerState.currentPage + 1} / ${pagerState.pageCount}",
            pagedModifier = pagedModifier,
            onRegisterNavActions = onRegisterNavActions,
            modifier = Modifier.fillMaxSize(),
        ) { page, handleTap, onScaleChanged ->
            val slice = slices.getOrNull(page)
            val imageUrl = slice?.let { state.readerImages.getOrNull(it.imageIndex) }
            val half = slice?.half ?: ImageHalf.FULL

            zoomContainer(
                page,
                state.readerImages.isNotEmpty() && page == pagerState.currentPage,
                handleTap,
                onScaleChanged,
            ) {
                if (imageUrl != null) {
                    ReaderImage(
                        url = imageUrl,
                        maxBitmapSize = readerMaxBitmapSize,
                        half = half,
                        contentDescription = strings.labelPageNumber(page + 1),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                        onDimensionsLoaded = { w, h ->
                            controller.recordImageAspectRatio(slice.imageIndex, w, h)
                        },
                        onRefreshEpisode = controller::refreshReaderImages,
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedReaderDualPagePane(
    state: AppUiState,
    controller: SharedAppController,
    config: ReaderPlatformConfig,
    zoomContainer: @Composable (
        resetKey: Any?,
        isActivePage: Boolean,
        onTap: (Offset, IntSize) -> Unit,
        onScaleChanged: (Float) -> Unit,
        content: @Composable (scale: Float) -> Unit,
    ) -> Unit,
    pagedModifier: @Composable (
        pagerState: PagerState,
        isR2L: Boolean,
        isCurrentPageZoomed: Boolean,
        onAdvance: () -> Unit,
        onRetreat: () -> Unit,
    ) -> Modifier,
    onRegisterNavActions: ((onAdvance: () -> Unit, onRetreat: () -> Unit) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT
    val spreads = remember(state.readerImages.size, state.imageAspectRatios) {
        buildDualSpreads(state.readerImages.size, state.imageAspectRatios)
    }
    val initialSpread = remember(state.currentEpisode?.wrId, spreads) {
        val target = state.currentEpisode?.lastReadPage ?: 0
        val found = spreads.indexOfFirst { spread ->
            when (spread) {
                is DualSpread.Single -> spread.index >= target
                is DualSpread.Dual -> spread.secondIndex >= target
            }
        }
        if (found >= 0) found.coerceIn(0, (spreads.size - 1).coerceAtLeast(0)) else 0
    }
    val pagerState = rememberPagerState(
        initialPage = initialSpread,
        pageCount = { spreads.size.coerceAtLeast(1) },
    )

    LaunchedEffect(pagerState.currentPage, spreads) {
        if (state.readerImages.isNotEmpty() && spreads.isNotEmpty()) {
            val spread = spreads.getOrNull(pagerState.currentPage)
            val firstPageIndex = when (spread) {
                is DualSpread.Single -> spread.index
                is DualSpread.Dual -> spread.firstIndex
                null -> 0
            }
            val lastVisible = when (spread) {
                is DualSpread.Single -> spread.index
                is DualSpread.Dual -> spread.secondIndex
                null -> 0
            }
            controller.savePage(firstPageIndex, seenThroughPage = lastVisible)
        }
    }

    var restoredWrId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.currentEpisode?.wrId, state.readerImages.isNotEmpty()) {
        val wrId = state.currentEpisode?.wrId
        if (wrId != null && wrId != restoredWrId && state.readerImages.isNotEmpty()) {
            restoredWrId = wrId
            val target = state.currentEpisode?.lastReadPage ?: 0
            if (target > 0) {
                val idx = spreads.indexOfFirst { spread ->
                    when (spread) {
                        is DualSpread.Single -> spread.index >= target
                        is DualSpread.Dual -> spread.secondIndex >= target
                    }
                }
                if (idx in 0 until pagerState.pageCount && idx != pagerState.currentPage) {
                    pagerState.scrollToPage(idx)
                }
            }
        }
    }

    var prevSpreads by remember { mutableStateOf(spreads) }
    LaunchedEffect(spreads) {
        if (spreads != prevSpreads) {
            val oldSpread = prevSpreads.getOrNull(pagerState.currentPage)
            if (oldSpread != null) {
                val activeImage = when (oldSpread) {
                    is DualSpread.Single -> oldSpread.index
                    is DualSpread.Dual -> oldSpread.firstIndex
                }
                val newIdx = spreads.indexOfFirst { spread ->
                    when (spread) {
                        is DualSpread.Single -> spread.index == activeImage
                        is DualSpread.Dual -> spread.firstIndex == activeImage || spread.secondIndex == activeImage
                    }
                }
                if (newIdx in 0 until pagerState.pageCount && newIdx != pagerState.currentPage) {
                    pagerState.scrollToPage(newIdx)
                }
            }
            prevSpreads = spreads
        }
    }

    val currentSpreads by rememberUpdatedState(spreads)
    val platformContext = LocalPlatformContext.current

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val readerMaxBitmapSize = remember(constraints.maxWidth) {
            CoilSize(constraints.maxWidth.coerceAtLeast(READER_SHORT_EDGE_PX), READER_LONG_EDGE_PX)
        }
        LaunchedEffect(pagerState.currentPage, state.readerImages) {
            val imageLoader = SingletonImageLoader.get(platformContext)
            val targets = config.dualPreloadOffsets.flatMap { offset ->
                when (val nextSpread = currentSpreads.getOrNull(pagerState.currentPage + offset)) {
                    is DualSpread.Single -> listOfNotNull(
                        state.readerImages.getOrNull(nextSpread.index)?.let { nextSpread.index to it },
                    )
                    is DualSpread.Dual -> listOfNotNull(
                        state.readerImages.getOrNull(nextSpread.firstIndex)?.let { nextSpread.firstIndex to it },
                        state.readerImages.getOrNull(nextSpread.secondIndex)?.let { nextSpread.secondIndex to it },
                    )
                    null -> emptyList()
                }
            }.distinctBy { it.second }
            for ((imageIndex, url) in targets) {
                val result = imageLoader.execute(
                    ImageRequest.Builder(platformContext)
                        .data(url)
                        .imageCacheRole(ImageCacheRole.READER, url)
                        .readerImageSizing(readerMaxBitmapSize)
                        .build(),
                )
                if (result is SuccessResult && result.image.width > 0 && result.image.height > 0) {
                    controller.recordImageAspectRatio(imageIndex, result.image.width, result.image.height)
                }
            }
        }

        val currentSpreadObj = spreads.getOrNull(pagerState.currentPage)
        val currentRangeText = remember(currentSpreadObj, state.readerImages.size, isR2L) {
            when (currentSpreadObj) {
                is DualSpread.Single -> "${currentSpreadObj.index + 1} / ${state.readerImages.size}"
                is DualSpread.Dual -> {
                    val i1 = currentSpreadObj.firstIndex + 1
                    val i2 = currentSpreadObj.secondIndex + 1
                    if (isR2L) "$i2-$i1 / ${state.readerImages.size}" else "$i1-$i2 / ${state.readerImages.size}"
                }
                null -> ""
            }
        }

        SharedReaderPagedLayout(
            state = state,
            controller = controller,
            config = config,
            pagerState = pagerState,
            isR2L = isR2L,
            currentRangeText = currentRangeText,
            pagedModifier = pagedModifier,
            onRegisterNavActions = onRegisterNavActions,
            modifier = Modifier.fillMaxSize(),
        ) { spreadIdx, handleTap, onScaleChanged ->
            zoomContainer(
                spreadIdx,
                state.readerImages.isNotEmpty() && spreadIdx == pagerState.currentPage,
                handleTap,
                onScaleChanged,
            ) {
                when (val spread = spreads.getOrNull(spreadIdx)) {
                    is DualSpread.Single -> {
                        val ratio = state.imageAspectRatios[spread.index]
                        val isWide = ReaderDomain.isWideSpreadRatio(ratio)
                        val url = state.readerImages.getOrNull(spread.index)
                        if (isWide) {
                            if (url != null) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    ReaderImage(
                                        url = url,
                                        maxBitmapSize = readerMaxBitmapSize,
                                        contentDescription = strings.labelPageNumber(spread.index + 1),
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.Center,
                                        modifier = Modifier.fillMaxSize(),
                                        onDimensionsLoaded = { w, h ->
                                            controller.recordImageAspectRatio(spread.index, w, h)
                                        },
                                        onRefreshEpisode = controller::refreshReaderImages,
                                    )
                                }
                            }
                        } else {
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
                                        if (url != null) {
                                            ReaderImage(
                                                url = url,
                                                maxBitmapSize = readerMaxBitmapSize,
                                                contentDescription = strings.labelPageNumber(spread.index + 1),
                                                contentScale = ContentScale.Fit,
                                                alignment = Alignment.CenterStart,
                                                modifier = Modifier.fillMaxSize(),
                                                onDimensionsLoaded = { w, h ->
                                                    controller.recordImageAspectRatio(spread.index, w, h)
                                                },
                                                onRefreshEpisode = controller::refreshReaderImages,
                                            )
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight(),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        if (url != null) {
                                            ReaderImage(
                                                url = url,
                                                maxBitmapSize = readerMaxBitmapSize,
                                                contentDescription = strings.labelPageNumber(spread.index + 1),
                                                contentScale = ContentScale.Fit,
                                                alignment = Alignment.CenterEnd,
                                                modifier = Modifier.fillMaxSize(),
                                                onDimensionsLoaded = { w, h ->
                                                    controller.recordImageAspectRatio(spread.index, w, h)
                                                },
                                                onRefreshEpisode = controller::refreshReaderImages,
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    is DualSpread.Dual -> {
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
                                ReaderImage(
                                    url = state.readerImages.getOrNull(leftIndex),
                                    maxBitmapSize = readerMaxBitmapSize,
                                    contentDescription = strings.labelPageNumber(leftIndex + 1),
                                    contentScale = ContentScale.Fit,
                                    alignment = Alignment.CenterEnd,
                                    modifier = Modifier.fillMaxSize(),
                                    onDimensionsLoaded = { w, h ->
                                        controller.recordImageAspectRatio(leftIndex, w, h)
                                    },
                                    onRefreshEpisode = controller::refreshReaderImages,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                ReaderImage(
                                    url = state.readerImages.getOrNull(rightIndex),
                                    maxBitmapSize = readerMaxBitmapSize,
                                    contentDescription = strings.labelPageNumber(rightIndex + 1),
                                    contentScale = ContentScale.Fit,
                                    alignment = Alignment.CenterStart,
                                    modifier = Modifier.fillMaxSize(),
                                    onDimensionsLoaded = { w, h ->
                                        controller.recordImageAspectRatio(rightIndex, w, h)
                                    },
                                    onRefreshEpisode = controller::refreshReaderImages,
                                )
                            }
                        }
                    }
                    null -> {}
                }
            }
        }
    }
}

@Composable
private fun BoxScope.SharedReaderOverlayControls(
    state: AppUiState,
    controller: SharedAppController,
    config: ReaderPlatformConfig,
    controlsVisible: Boolean,
    currentRangeText: String,
    currentPage: Int,
    maxPages: Int,
    onPageChange: (Int) -> Unit,
) {
    if (!controlsVisible && state.readerImages.isNotEmpty() && currentRangeText.isNotEmpty()) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            modifier = Modifier
                .align(config.indicatorAlignment)
                .then(if (config.applyNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier)
                .padding(16.dp),
        ) {
            Text(
                text = currentRangeText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }

    AnimatedVisibility(
        visible = controlsVisible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        TopBar(
            state = state,
            controller = controller,
            inOverlay = true,
            compactMode = config.compactTopBar,
        )
    }

    AnimatedVisibility(
        visible = controlsVisible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        SharedReaderBottomBar(
            config = config,
            currentRangeText = currentRangeText,
            currentPage = currentPage,
            maxPages = maxPages,
            hasPrevEpisode = state.hasPrevEpisode,
            hasNextEpisode = state.hasNextEpisode,
            onPageChange = onPageChange,
            onPrevEp = controller::openPrevEpisode,
            onNextEp = controller::openNextEpisode,
            onClose = controller::closeReader,
        )
    }
}

@Composable
private fun SharedReaderBottomBar(
    config: ReaderPlatformConfig,
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
    val strings = LocalStrings.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f))
            .then(if (config.applyNavigationBarsPadding) Modifier.navigationBarsPadding() else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (maxPages > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(config.bottomBarWidthFraction),
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
                .fillMaxWidth(config.bottomBarWidthFraction)
                .padding(top = 2.dp),
        ) {
            OutlinedButton(
                onClick = onPrevEp,
                enabled = hasPrevEpisode,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (config.showBottomBarIcons) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(strings.actionPrevEpisode)
            }
            Button(
                onClick = onClose,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(strings.actionEpisodeList)
            }
            OutlinedButton(
                onClick = onNextEp,
                enabled = hasNextEpisode,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(strings.actionNextEpisode)
                if (config.showBottomBarIcons) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SharedReaderFooter(
    state: AppUiState,
    config: ReaderPlatformConfig,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        if (config.showFooterEpisodeListButton) {
            Text(
                text = "${state.currentEpisode?.title.orEmpty()} (${strings.formatItemCount(state.readerImages.size)})",
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
                    Text(strings.actionPrevEpisode)
                }
                Button(
                    onClick = onClose,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(strings.actionEpisodeList)
                }
                OutlinedButton(
                    onClick = onNext,
                    enabled = state.hasNextEpisode,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(strings.actionNextEpisode)
                }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.hasPrevEpisode) {
                    FilledTonalButton(onClick = onPrev) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(strings.actionPrevEpisode)
                    }
                }
                Spacer(Modifier.width(16.dp))
                if (state.hasNextEpisode) {
                    Button(onClick = onNext) {
                        Text(strings.actionNextEpisode)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                }
            }
        }
    }
}
