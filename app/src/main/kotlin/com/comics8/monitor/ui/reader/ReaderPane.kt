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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.comics8.core.image.ImageCacheRole
import com.comics8.monitor.data.local.imageCacheRole
import com.comics8.core.model.CropRect
import com.comics8.core.model.DualSpread
import com.comics8.core.model.ImageHalf
import com.comics8.core.model.PageTapZone
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.SinglePageSlice
import com.comics8.core.model.ViewMode
import com.comics8.core.model.buildDualSpreads
import com.comics8.core.model.buildSinglePageSlices
import com.comics8.core.model.computeContentCropRect
import com.comics8.monitor.ui.theme.LocalStrings
import com.comics8.monitor.ui.ListingUiState
import com.comics8.monitor.ui.ListingViewModel
import com.comics8.monitor.ui.components.EpisodeBoundaryPrompt
import com.comics8.monitor.ui.components.ErrorPane
import com.comics8.monitor.ui.components.LoadingPane
import com.comics8.monitor.ui.components.TopBar
import com.comics8.monitor.ui.components.ZoomableBox
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.comics8.monitor.ui.settings.ReaderSettingsPane
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPane(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
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
                message = strings.errorNoImagesToLoad,
                actionLabel = strings.navBackToEpisodeList,
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

    if (state.showViewerOptions) {
        ModalBottomSheet(
            onDismissRequest = viewModel::closeViewerOptions,
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
                ReaderSettingsPane(state = state, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun ReaderScrollPane(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val initialIndex = remember(state.currentEpisode?.wrId) {
        (state.currentEpisode?.lastReadPage ?: 0).coerceIn(0, (state.readerImages.size - 1).coerceAtLeast(0))
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.readerNavEvents.collect { advance ->
            if (advance) {
                val next = (listState.firstVisibleItemIndex + 1).coerceAtMost((state.readerImages.size - 1).coerceAtLeast(0))
                listState.animateScrollToItem(next)
            } else {
                val prev = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                listState.animateScrollToItem(prev)
            }
        }
    }

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

    var restoredWrId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.currentEpisode?.wrId, state.readerImages.isNotEmpty()) {
        val wrId = state.currentEpisode?.wrId
        if (wrId != null && wrId != restoredWrId && state.readerImages.isNotEmpty()) {
            restoredWrId = wrId
            val target = state.currentEpisode?.lastReadPage ?: 0
            if (target > 0 && target != listState.firstVisibleItemIndex) {
                listState.scrollToItem(target.coerceIn(0, state.readerImages.size - 1))
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ZoomableBox(
            modifier = Modifier.fillMaxSize(),
            resetKey = state.currentEpisode?.wrId,
            onTap = { _, _ -> controlsVisible = !controlsVisible },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(state.readerImages, key = { index, url -> "$index-$url" }) { index, imageUrl ->
                    ReaderImage(
                        model = imageUrl,
                        contentDescription = strings.labelPageNumber(index + 1),
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .wrapContentHeight(),
                        onSuccess = { result ->
                            val w = result.result.drawable.intrinsicWidth
                            val h = result.result.drawable.intrinsicHeight
                            if (w > 0 && h > 0) viewModel.recordImageAspectRatio(index, w, h)
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
private fun ReaderPagedLayout(
    state: ListingUiState,
    viewModel: ListingViewModel,
    pagerState: PagerState,
    totalPages: Int,
    isR2L: Boolean,
    currentRangeText: String,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int, handleTap: (Offset, IntSize) -> Unit) -> Unit,
) {
    val strings = LocalStrings.current
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

    val onAdvance = {
        controlsVisible = false
        if (pagerState.currentPage < totalPages - 1) {
            nextPromptVisible = false
            prevPromptVisible = false
            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
        } else {
            if (nextPromptVisible) {
                nextPromptVisible = false
                if (state.hasNextEpisode) {
                    viewModel.openNextEpisode()
                } else {
                    viewModel.closeReader()
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
                    viewModel.closeReader()
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

    LaunchedEffect(Unit) {
        viewModel.readerNavEvents.collect { advance ->
            if (advance) onAdvance() else onRetreat()
        }
    }

    val density = LocalDensity.current
    val dragThresholdPx = remember(density) { with(density) { 40.dp.toPx() } }
    val minDragForFlingPx = remember(density) { with(density) { 16.dp.toPx() } }
    val velocityThresholdPx = remember(density) { with(density) { 350.dp.toPx() } }
    var lastEdgeTriggerTime by remember { mutableLongStateOf(0L) }

    val edgeSwipeModifier = Modifier.pointerInput(isR2L, totalPages, pagerState) {
        awaitEachGesture {
            val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
            val startPage = pagerState.currentPage
            val isAtEnd = startPage >= totalPages - 1
            val isAtStart = startPage <= 0

            if (!isAtEnd && !isAtStart) {
                do {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
                return@awaitEachGesture
            }

            var lastPosition = down.position
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)

            var isMultiTouch = false

            do {
                val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                if (event.changes.size > 1) {
                    isMultiTouch = true
                }
                val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                if (change != null) {
                    lastPosition = change.position
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                }
            } while (event.changes.any { it.pressed })

            if (isMultiTouch) return@awaitEachGesture

            val netDragX = lastPosition.x - down.position.x
            val netDragY = lastPosition.y - down.position.y

            if (abs(netDragY) > abs(netDragX)) return@awaitEachGesture

            val endPage = pagerState.currentPage
            if (endPage != startPage) return@awaitEachGesture

            val velocity = velocityTracker.calculateVelocity()
            val forwardDrag = if (isR2L) netDragX else -netDragX
            val backwardDrag = if (isR2L) -netDragX else netDragX
            val forwardVelocity = if (isR2L) velocity.x else -velocity.x
            val backwardVelocity = if (isR2L) -velocity.x else velocity.x

            val now = System.currentTimeMillis()
            if (now - lastEdgeTriggerTime < 400L) return@awaitEachGesture

            val isForwardTriggered = (forwardDrag >= dragThresholdPx) ||
                (forwardDrag >= minDragForFlingPx && forwardVelocity >= velocityThresholdPx)

            val isBackwardTriggered = (backwardDrag >= dragThresholdPx) ||
                (backwardDrag >= minDragForFlingPx && backwardVelocity >= velocityThresholdPx)

            if (isAtEnd && isForwardTriggered) {
                lastEdgeTriggerTime = now
                onAdvance()
            } else if (isAtStart && isBackwardTriggered) {
                lastEdgeTriggerTime = now
                onRetreat()
            }
        }
    }

    val handleTap: (Offset, IntSize) -> Unit = { tapOffset, size ->
        val tapX = tapOffset.x
        val width = size.width.toFloat()
        val isNextOnLeft = when (state.pageTapZone) {
            PageTapZone.FOLLOW_DIRECTION -> isR2L
            PageTapZone.LEFT_NEXT -> true
            PageTapZone.RIGHT_NEXT -> false
        }
        when {
            tapX < width * 0.35f -> if (isNextOnLeft) onAdvance() else onRetreat()
            tapX > width * 0.65f -> if (isNextOnLeft) onRetreat() else onAdvance()
            else -> {
                controlsVisible = !controlsVisible
                nextPromptVisible = false
                prevPromptVisible = false
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
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .then(edgeSwipeModifier),
        ) { page ->
            content(page, handleTap)
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

        // Boundary Confirmation Prompt (Next Episode)
        EpisodeBoundaryPrompt(
            visible = nextPromptVisible,
            title = if (state.hasNextEpisode) strings.promptLastPage else strings.promptLastEpisode,
            subtitle = if (state.hasNextEpisode) strings.promptNextEpisodeHint else strings.promptCloseReaderHint,
            onClick = {
                nextPromptVisible = false
                if (state.hasNextEpisode) {
                    viewModel.openNextEpisode()
                } else {
                    viewModel.closeReader()
                }
            },
        )

        // Boundary Confirmation Prompt (Prev Episode)
        EpisodeBoundaryPrompt(
            visible = prevPromptVisible,
            title = if (state.hasPrevEpisode) strings.promptFirstPage else strings.promptFirstEpisode,
            subtitle = if (state.hasPrevEpisode) strings.promptPrevEpisodeHint else strings.promptCloseReaderHint,
            onClick = {
                prevPromptVisible = false
                if (state.hasPrevEpisode) {
                    viewModel.openPrevEpisode()
                } else {
                    viewModel.closeReader()
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
                maxPages = totalPages,
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
private fun ReaderSinglePagePane(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT
    val slices = remember(state.readerImages.size, state.imageAspectRatios, state.splitMode, state.readDirection) {
        buildSinglePageSlices(state.readerImages.size, state.imageAspectRatios, state.splitMode, state.readDirection)
    }
    val totalSlices = slices.size.coerceAtLeast(1)
    val initialPage = remember(state.currentEpisode?.wrId) {
        val targetPage = state.currentEpisode?.lastReadPage ?: 0
        val idx = slices.indexOfFirst { it.imageIndex >= targetPage }
        if (idx >= 0) idx.coerceIn(0, totalSlices - 1) else 0
    }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { totalSlices },
    )

    LaunchedEffect(pagerState.currentPage, slices) {
        if (state.readerImages.isNotEmpty() && slices.isNotEmpty()) {
            val slice = slices.getOrNull(pagerState.currentPage)
            if (slice != null) {
                viewModel.savePage(slice.imageIndex)
            }
        }
    }

    var restoredWrId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.currentEpisode?.wrId, state.readerImages.isNotEmpty()) {
        val wrId = state.currentEpisode?.wrId
        if (wrId != null && wrId != restoredWrId && state.readerImages.isNotEmpty()) {
            restoredWrId = wrId
            val target = state.currentEpisode?.lastReadPage ?: 0
            val idx = slices.indexOfFirst { it.imageIndex >= target }
            if (idx in 0 until totalSlices && idx != pagerState.currentPage) {
                pagerState.scrollToPage(idx)
            }
        }
    }

    val context = LocalContext.current
    LaunchedEffect(pagerState.currentPage, slices, state.readerImages) {
        val imageLoader = coil.Coil.imageLoader(context)
        val maxEdge = coil.size.Size(1200, 1800)
        for (offset in 1..2) {
            val nextSlice = slices.getOrNull(pagerState.currentPage + offset) ?: continue
            val targetUrl = state.readerImages.getOrNull(nextSlice.imageIndex) ?: continue
            val request = coil.request.ImageRequest.Builder(context)
                .data(targetUrl)
                .imageCacheRole(ImageCacheRole.READER, targetUrl)
                .size(maxEdge)
                .build()
            imageLoader.execute(request)
        }
    }

    ReaderPagedLayout(
        state = state,
        viewModel = viewModel,
        pagerState = pagerState,
        totalPages = totalSlices,
        isR2L = isR2L,
        currentRangeText = "${pagerState.currentPage + 1} / $totalSlices",
        modifier = modifier,
    ) { page, handleTap ->
        val slice = slices.getOrNull(page)
        val imageUrl = slice?.let { state.readerImages.getOrNull(it.imageIndex) }
        val half = slice?.half ?: ImageHalf.FULL

        ZoomableBox(
            modifier = Modifier.fillMaxSize(),
            resetKey = page,
            onTap = handleTap,
        ) { scale ->
            ReaderImage(
                model = imageUrl,
                half = half,
                contentDescription = strings.labelPageNumber(page + 1),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { result ->
                    val w = result.result.drawable.intrinsicWidth
                    val h = result.result.drawable.intrinsicHeight
                    if (w > 0 && h > 0 && slice != null) {
                        viewModel.recordImageAspectRatio(slice.imageIndex, w, h)
                    }
                },
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
    val strings = LocalStrings.current
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
            viewModel.savePage(firstPageIndex, seenThroughPage = lastVisible)
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
                if (idx in 0 until totalSpreads && idx != pagerState.currentPage) {
                    pagerState.scrollToPage(idx)
                }
            }
        }
    }

    var prevSpreads by remember { mutableStateOf(spreads) }
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
            if (newIdx >= 0 && newIdx != pagerState.currentPage) {
                LaunchedEffect(newIdx) {
                    pagerState.scrollToPage(newIdx.coerceIn(0, totalSpreads - 1))
                }
            }
        }
        prevSpreads = spreads
    }

    val context = LocalContext.current
    LaunchedEffect(pagerState.currentPage, spreads, state.readerImages) {
        val imageLoader = coil.Coil.imageLoader(context)
        val maxEdge = coil.size.Size(1200, 1800)
        for (offset in 1..1) {
            val nextSpread = spreads.getOrNull(pagerState.currentPage + offset)
            when (nextSpread) {
                is DualSpread.Single -> {
                    state.readerImages.getOrNull(nextSpread.index)?.let { url ->
                        imageLoader.execute(
                            coil.request.ImageRequest.Builder(context)
                                .data(url)
                                .imageCacheRole(ImageCacheRole.READER, url)
                                .size(maxEdge)
                                .build(),
                        )
                    }
                }
                is DualSpread.Dual -> {
                    state.readerImages.getOrNull(nextSpread.firstIndex)?.let { url ->
                        imageLoader.execute(
                            coil.request.ImageRequest.Builder(context)
                                .data(url)
                                .imageCacheRole(ImageCacheRole.READER, url)
                                .size(maxEdge)
                                .build(),
                        )
                    }
                    state.readerImages.getOrNull(nextSpread.secondIndex)?.let { url ->
                        imageLoader.execute(
                            coil.request.ImageRequest.Builder(context)
                                .data(url)
                                .imageCacheRole(ImageCacheRole.READER, url)
                                .size(maxEdge)
                                .build(),
                        )
                    }
                }
                null -> {}
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

    ReaderPagedLayout(
        state = state,
        viewModel = viewModel,
        pagerState = pagerState,
        totalPages = totalSpreads,
        isR2L = isR2L,
        currentRangeText = currentRangeText,
        modifier = modifier,
    ) { spreadIdx, handleTap ->
        ZoomableBox(
            modifier = Modifier.fillMaxSize(),
            resetKey = spreadIdx,
            onTap = handleTap,
        ) { scale ->
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
                            ReaderImage(
                                model = state.readerImages.getOrNull(spread.index),
                                contentDescription = strings.labelPageNumber(spread.index + 1),
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
                                    ReaderImage(
                                        model = state.readerImages.getOrNull(spread.index),
                                        contentDescription = strings.labelPageNumber(spread.index + 1),
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
                                    ReaderImage(
                                        model = state.readerImages.getOrNull(spread.index),
                                        contentDescription = strings.labelPageNumber(spread.index + 1),
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
                            contentAlignment = Alignment.Center,
                        ) {
                            ReaderImage(
                                model = state.readerImages.getOrNull(leftIndex),
                                contentDescription = strings.labelPageNumber(leftIndex + 1),
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.Center,
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
                            contentAlignment = Alignment.Center,
                        ) {
                            ReaderImage(
                                model = state.readerImages.getOrNull(rightIndex),
                                contentDescription = strings.labelPageNumber(rightIndex + 1),
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.Center,
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
    val strings = LocalStrings.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f))
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
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f),
            ) {
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
    val strings = LocalStrings.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
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
    }
}

@Composable
private fun ReaderImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    alignment: Alignment = Alignment.Center,
    half: ImageHalf = ImageHalf.FULL,
    onSuccess: ((AsyncImagePainter.State.Success) -> Unit)? = null,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    var retryKey by remember(model) { mutableStateOf(0) }
    val imageModel = remember(model, half, retryKey) {
        if (model is coil.request.ImageRequest) {
            model
        } else {
            coil.request.ImageRequest.Builder(context)
                .data(model)
                .imageCacheRole(ImageCacheRole.READER, model)
                .size(coil.size.Size(1200, 1800))
                .apply {
                    if (half != ImageHalf.FULL) allowHardware(false)
                    if (retryKey > 0) memoryCachePolicy(coil.request.CachePolicy.WRITE_ONLY)
                }
                .build()
        }
    }

    SubcomposeAsyncImage(
        model = imageModel,
        contentDescription = contentDescription,
        contentScale = contentScale,
        alignment = alignment,
        modifier = modifier,
        onSuccess = onSuccess,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .defaultMinSize(minHeight = 240.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        error = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .defaultMinSize(minHeight = 200.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { retryKey++ },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.BrokenImage,
                        contentDescription = strings.errorImageLoadFailed,
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = strings.errorCannotLoadImage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "(${strings.actionRetry})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        success = { successState ->
            if (half == ImageHalf.FULL) {
                SubcomposeAsyncImageContent()
            } else {
                val drawable = successState.result.drawable
                val rawBitmap = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                val bitmap = if (rawBitmap != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && rawBitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
                    rawBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                } else {
                    rawBitmap
                }

                if (bitmap != null) {
                    val cropRect = remember(bitmap, half) {
                        val w = bitmap.width
                        val h = bitmap.height
                        val regionLeft = if (half == ImageHalf.LEFT) 0 else w / 2
                        val regionRight = if (half == ImageHalf.LEFT) w / 2 else w
                        try {
                            computeContentCropRect(
                                regionLeft = regionLeft,
                                regionTop = 0,
                                regionRight = regionRight,
                                regionBottom = h,
                                maxCropFractionX = 0.45f,
                                maxCropFractionY = 0.20f,
                                whiteThreshold = 225,
                                whiteRatio = 0.95f,
                                getPixelRgb = { x, y -> bitmap.getPixel(x, y) },
                            )
                        } catch (_: Exception) {
                            CropRect(regionLeft, 0, regionRight, h)
                        }
                    }
                    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            val srcW = cropRect.width.toFloat()
                            val srcH = cropRect.height.toFloat()
                            if (srcW > 0 && srcH > 0) {
                                val pageAspectRatio = srcW / srcH
                                val containerWidth = size.width
                                val containerHeight = size.height
                                val containerAspectRatio = containerWidth / containerHeight

                                val dstWidth: Float
                                val dstHeight: Float
                                if (containerAspectRatio > pageAspectRatio) {
                                    dstHeight = containerHeight
                                    dstWidth = dstHeight * pageAspectRatio
                                } else {
                                    dstWidth = containerWidth
                                    dstHeight = dstWidth / pageAspectRatio
                                }

                                val dstLeft = (containerWidth - dstWidth) / 2f
                                val dstTop = (containerHeight - dstHeight) / 2f

                                drawImage(
                                    image = imageBitmap,
                                    srcOffset = androidx.compose.ui.unit.IntOffset(cropRect.left, cropRect.top),
                                    srcSize = androidx.compose.ui.unit.IntSize(cropRect.width, cropRect.height),
                                    dstOffset = androidx.compose.ui.unit.IntOffset(dstLeft.toInt(), dstTop.toInt()),
                                    dstSize = androidx.compose.ui.unit.IntSize(dstWidth.toInt(), dstHeight.toInt()),
                                )
                            }
                        }
                    }
                } else {
                    SubcomposeAsyncImageContent()
                }
            }
        },
    )
}
