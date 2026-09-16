package com.comics8.desktop.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.model.DualSpread
import com.comics8.core.model.ImageHalf
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ReaderDomain
import com.comics8.core.model.SinglePageSlice
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ViewMode
import com.comics8.core.model.buildDualSpreads
import com.comics8.core.model.buildSinglePageSlices
import com.comics8.desktop.ui.DesktopUiState
import kotlin.math.abs
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.components.ErrorPane
import com.comics8.desktop.ui.components.LoadingPane
import com.comics8.desktop.ui.components.TopBar
import com.comics8.desktop.ui.theme.LocalStrings
import com.comics8.desktop.ui.util.DesktopAsyncImage

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize


@Composable
fun ReaderPane(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val focusRequester = remember { FocusRequester() }
    var onAdvanceAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onRetreatAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    LaunchedEffect(state.currentEpisode?.wrId) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    val key = event.key
                    val isCmd = event.isMetaPressed || event.isCtrlPressed
                    val isAlt = event.isAltPressed
                    val isCmdOrAlt = isCmd || isAlt
                    val isBack =
                        (isCmd && (key == Key.DirectionLeft || key == Key.LeftBracket || event.utf16CodePoint == '['.code)) ||
                        (isAlt && (key == Key.DirectionLeft || key == Key.LeftBracket || event.utf16CodePoint == '['.code))
                    val isForward =
                        (isCmd && (key == Key.DirectionRight || key == Key.RightBracket || event.utf16CodePoint == ']'.code)) ||
                        (isAlt && (key == Key.DirectionRight || key == Key.RightBracket || event.utf16CodePoint == ']'.code))

                    when {
                        key == Key.Escape -> {
                            viewModel.goBack()
                            true
                        }
                        isBack -> {
                            viewModel.goBack()
                            true
                        }
                        isForward -> {
                            viewModel.goForward()
                            true
                        }
                        key == Key.F && !isCmdOrAlt -> {
                            viewModel.toggleFullscreen()
                            true
                        }
                        !isCmdOrAlt && key == Key.DirectionRight -> {
                            if (isR2L) onRetreatAction?.invoke() else onAdvanceAction?.invoke()
                            true
                        }
                        !isCmdOrAlt && key == Key.DirectionLeft -> {
                            if (isR2L) onAdvanceAction?.invoke() else onRetreatAction?.invoke()
                            true
                        }
                        !isCmdOrAlt && (key == Key.Spacebar || key == Key.PageDown) -> {
                            onAdvanceAction?.invoke()
                            true
                        }
                        !isCmdOrAlt && key == Key.PageUp -> {
                            onRetreatAction?.invoke()
                            true
                        }
                        else -> false
                    }
                } else false
            },
    ) {
        when (state.viewMode) {
            ViewMode.SCROLL -> ReaderScrollView(
                state = state,
                viewModel = viewModel,
                onRegisterActions = { adv, ret ->
                    onAdvanceAction = adv
                    onRetreatAction = ret
                },
            )
            ViewMode.PAGE -> ReaderSingleView(
                state = state,
                viewModel = viewModel,
                onRegisterActions = { adv, ret ->
                    onAdvanceAction = adv
                    onRetreatAction = ret
                },
            )
            ViewMode.DUAL -> ReaderDualView(
                state = state,
                viewModel = viewModel,
                onRegisterActions = { adv, ret ->
                    onAdvanceAction = adv
                    onRetreatAction = ret
                },
            )
        }

        if (state.readerLoading && state.readerImages.isEmpty()) {
            LoadingPane()
        }

        if (state.readerError != null && state.readerImages.isEmpty()) {
            ErrorPane(
                message = state.readerError,
                actionLabel = strings.actionRetry,
                onRetry = { state.currentEpisode?.let { viewModel.openEpisode(it) } },
            )
        }
    }
}

@Composable
private fun ReaderScrollView(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    onRegisterActions: (onAdvance: () -> Unit, onRetreat: () -> Unit) -> Unit,
) {
    val strings = LocalStrings.current
    val initialIndex = remember(state.currentEpisode?.wrId) {
        (state.currentEpisode?.lastReadPage ?: 0).coerceIn(0, (state.readerImages.size - 1).coerceAtLeast(0))
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(false) }

    val onAdvance = {
        scope.launch {
            if (state.readerImages.isNotEmpty()) {
                val next = (listState.firstVisibleItemIndex + 1).coerceAtMost(state.readerImages.size - 1)
                listState.animateScrollToItem(next)
            }
        }
        Unit
    }
    val onRetreat = {
        scope.launch {
            if (state.readerImages.isNotEmpty()) {
                val prev = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                listState.animateScrollToItem(prev)
            }
        }
        Unit
    }

    LaunchedEffect(onAdvance, onRetreat) {
        onRegisterActions(onAdvance, onRetreat)
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

    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (state.readerImages.isNotEmpty()) {
            viewModel.savePage(listState.firstVisibleItemIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    controlsVisible = !controlsVisible
                },
        ) {
            itemsIndexed(state.readerImages, key = { idx, url -> "$idx-$url" }) { index, url ->
                DesktopAsyncImage(
                    cacheRole = ImageCacheRole.READER,
                    url = url,
                    contentDescription = strings.labelPageNumber(index + 1),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth(0.65f)
                        .padding(vertical = 2.dp),
                )
            }

            // Next / Prev episode footer
            item {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                ) {
                    if (state.hasPrevEpisode) {
                        FilledTonalButton(onClick = viewModel::openPrevEpisode) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(strings.actionPrevEpisode)
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    if (state.hasNextEpisode) {
                        Button(onClick = viewModel::openNextEpisode) {
                            Text(strings.actionNextEpisode)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                    }
                }
            }
        }

        // Overlay TopBar
        if (controlsVisible) {
            TopBar(
                state = state,
                viewModel = viewModel,
                inOverlay = true,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun ReaderPagedLayout(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    pagerState: PagerState,
    isR2L: Boolean,
    currentRangeText: String,
    onRegisterActions: (onAdvance: () -> Unit, onRetreat: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (page: Int) -> Unit,
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(false) }
    var nextPromptVisible by remember { mutableStateOf(false) }
    var prevPromptVisible by remember { mutableStateOf(false) }
    var promptJob by remember { mutableStateOf<Job?>(null) }
    var lastBoundaryTriggerTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.currentEpisode?.wrId, pagerState.currentPage) {
        nextPromptVisible = false
        prevPromptVisible = false
    }

    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) {
            controlsVisible = false
        }
    }

    val currentIsR2L by rememberUpdatedState(isR2L)
    val currentNextPromptVisible by rememberUpdatedState(nextPromptVisible)
    val currentPrevPromptVisible by rememberUpdatedState(prevPromptVisible)

    val onAdvance: () -> Unit = {
        if (!pagerState.isScrollInProgress) {
            controlsVisible = false
            when (val decision = ReaderDomain.resolveBoundaryDecision(
                currentPage = pagerState.currentPage,
                totalPages = pagerState.pageCount,
                direction = ReaderDomain.BoundaryDirection.ADVANCE,
                isPromptActive = currentNextPromptVisible,
            )) {
                is ReaderDomain.BoundaryDecision.PageTurn -> {
                    nextPromptVisible = false
                    prevPromptVisible = false
                    scope.launch { pagerState.animateScrollToPage(decision.targetPage) }
                }
                is ReaderDomain.BoundaryDecision.ShowPrompt -> {
                    nextPromptVisible = true
                    prevPromptVisible = false
                    promptJob?.cancel()
                    promptJob = scope.launch {
                        delay(3000)
                        nextPromptVisible = false
                    }
                }
                is ReaderDomain.BoundaryDecision.ConfirmNavigate -> {
                    nextPromptVisible = false
                    if (state.hasNextEpisode) {
                        viewModel.openNextEpisode()
                    } else {
                        viewModel.closeReader()
                    }
                }
            }
        }
    }

    val onRetreat: () -> Unit = {
        if (!pagerState.isScrollInProgress) {
            controlsVisible = false
            when (val decision = ReaderDomain.resolveBoundaryDecision(
                currentPage = pagerState.currentPage,
                totalPages = pagerState.pageCount,
                direction = ReaderDomain.BoundaryDirection.RETREAT,
                isPromptActive = currentPrevPromptVisible,
            )) {
                is ReaderDomain.BoundaryDecision.PageTurn -> {
                    nextPromptVisible = false
                    prevPromptVisible = false
                    scope.launch { pagerState.animateScrollToPage(decision.targetPage) }
                }
                is ReaderDomain.BoundaryDecision.ShowPrompt -> {
                    prevPromptVisible = true
                    nextPromptVisible = false
                    promptJob?.cancel()
                    promptJob = scope.launch {
                        delay(3000)
                        prevPromptVisible = false
                    }
                }
                is ReaderDomain.BoundaryDecision.ConfirmNavigate -> {
                    prevPromptVisible = false
                    if (state.hasPrevEpisode) {
                        viewModel.openPrevEpisode()
                    } else {
                        viewModel.closeReader()
                    }
                }
            }
        }
    }

    LaunchedEffect(onAdvance, onRetreat) {
        onRegisterActions(onAdvance, onRetreat)
    }

    // Unified Trackpad & Wheel Gesture Recognizer
    var isScrollLatched by remember { mutableStateOf(false) }
    var accumulatedScrollX by remember { mutableFloatStateOf(0f) }
    var accumulatedScrollY by remember { mutableFloatStateOf(0f) }
    var unlatchJob by remember { mutableStateOf<Job?>(null) }
    var idleResetJob by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            accumulatedScrollX = 0f
            accumulatedScrollY = 0f
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    val desktopScrollModifier = Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
        val deltaX = event.changes.sumOf { it.scrollDelta.x.toDouble() }.toFloat()
        val deltaY = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()

        if (isScrollLatched || pagerState.isScrollInProgress) {
            unlatchJob?.cancel()
            unlatchJob = scope.launch {
                delay(180)
                isScrollLatched = false
                accumulatedScrollX = 0f
                accumulatedScrollY = 0f
            }
            return@onPointerEvent
        }

        accumulatedScrollX += deltaX
        accumulatedScrollY += deltaY

        val direction = ReaderDomain.resolveScrollDirection(
            deltaX = accumulatedScrollX,
            deltaY = accumulatedScrollY,
            isR2L = currentIsR2L,
            threshold = 0.8f,
        )

        if (direction != null) {
            val now = System.currentTimeMillis()
            if (now - lastBoundaryTriggerTime >= 350L) {
                isScrollLatched = true
                lastBoundaryTriggerTime = now
                unlatchJob?.cancel()
                unlatchJob = scope.launch {
                    delay(180)
                    isScrollLatched = false
                    accumulatedScrollX = 0f
                    accumulatedScrollY = 0f
                }

                when (direction) {
                    ReaderDomain.BoundaryDirection.ADVANCE -> onAdvance()
                    ReaderDomain.BoundaryDirection.RETREAT -> onRetreat()
                }
            }
            accumulatedScrollX = 0f
            accumulatedScrollY = 0f
        } else {
            idleResetJob?.cancel()
            idleResetJob = scope.launch {
                delay(150)
                if (!isScrollLatched) {
                    accumulatedScrollX = 0f
                    accumulatedScrollY = 0f
                }
            }
        }
    }

    val handleTap: (Offset, IntSize) -> Unit = { tapOffset, size ->
        val tapX = tapOffset.x
        val width = size.width.toFloat()
        when {
            tapX < width * 0.38f -> if (currentIsR2L) onAdvance() else onRetreat()
            tapX > width * 0.62f -> if (currentIsR2L) onRetreat() else onAdvance()
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
            userScrollEnabled = false,
            reverseLayout = isR2L,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .then(desktopScrollModifier),
        ) { page ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(page, isR2L) {
                        detectTapGestures { offset ->
                            handleTap(offset, size)
                        }
                    },
            ) {
                content(page)
            }
        }

        // Floating Page Indicator (Visible when controls are hidden)
        if (!controlsVisible && state.readerImages.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            ) {
                Text(
                    text = currentRangeText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // Boundary Confirmation Prompts
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

        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                currentRangeText = currentRangeText,
                currentPage = pagerState.currentPage + 1,
                maxPages = pagerState.pageCount,
                hasPrevEpisode = state.hasPrevEpisode,
                hasNextEpisode = state.hasNextEpisode,
                onPageChange = { targetPage ->
                    val pageIdx = (targetPage - 1).coerceIn(0, pagerState.pageCount - 1)
                    scope.launch { pagerState.scrollToPage(pageIdx) }
                },
                onPrevEp = viewModel::openPrevEpisode,
                onNextEp = viewModel::openNextEpisode,
                onClose = viewModel::closeReader,
            )
        }
    }
}

@Composable
private fun ReaderSingleView(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    onRegisterActions: (onAdvance: () -> Unit, onRetreat: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT
    val slices = remember(state.readerImages.size, state.imageAspectRatios, state.splitMode, state.readDirection) {
        buildSinglePageSlices(state.readerImages.size, state.imageAspectRatios, state.splitMode, state.readDirection)
    }
    val totalSlices = slices.size.coerceAtLeast(1)
    val initialSlice = remember(state.currentEpisode?.wrId, slices) {
        val target = state.currentEpisode?.lastReadPage ?: 0
        val idx = slices.indexOfFirst { it.imageIndex >= target }
        if (idx >= 0) idx.coerceIn(0, totalSlices - 1) else 0
    }

    val pagerState = rememberPagerState(
        initialPage = initialSlice,
        pageCount = { totalSlices },
    )
    val scope = rememberCoroutineScope()

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

    var prevSlices by remember { mutableStateOf(slices) }
    if (slices != prevSlices) {
        val oldSlice = prevSlices.getOrNull(pagerState.currentPage)
        if (oldSlice != null) {
            val activeImage = oldSlice.imageIndex
            val newIdx = slices.indexOfFirst { it.imageIndex == activeImage && it.half == oldSlice.half }
                .takeIf { it >= 0 }
                ?: slices.indexOfFirst { it.imageIndex == activeImage }
            if (newIdx >= 0 && newIdx != pagerState.currentPage) {
                scope.launch { pagerState.scrollToPage(newIdx.coerceIn(0, totalSlices - 1)) }
            }
        }
        prevSlices = slices
    }

    LaunchedEffect(pagerState.currentPage, slices) {
        if (state.readerImages.isNotEmpty() && slices.isNotEmpty()) {
            val slice = slices.getOrNull(pagerState.currentPage)
            if (slice != null) {
                viewModel.savePage(slice.imageIndex)
            }
        }
    }

    LaunchedEffect(pagerState.currentPage, slices, state.readerImages) {
        if (state.readerImages.isNotEmpty() && slices.isNotEmpty()) {
            val preloadUrls = listOf(-1, 1, 2).mapNotNull { offset ->
                slices.getOrNull(pagerState.currentPage + offset)?.let { slice ->
                    state.readerImages.getOrNull(slice.imageIndex)
                }
            }
            com.comics8.desktop.ui.util.DesktopImageCache.preload(ImageCacheRole.READER, preloadUrls)
        }
    }

    ReaderPagedLayout(
        state = state,
        viewModel = viewModel,
        pagerState = pagerState,
        isR2L = isR2L,
        currentRangeText = "${pagerState.currentPage + 1} / $totalSlices",
        onRegisterActions = onRegisterActions,
        modifier = modifier,
    ) { page ->
        val currentSlice = slices.getOrNull(page)
        val currentImage = currentSlice?.let { state.readerImages.getOrNull(it.imageIndex) }
        val currentHalf = currentSlice?.half ?: ImageHalf.FULL

        if (currentImage != null) {
            DesktopAsyncImage(
                cacheRole = ImageCacheRole.READER,
                url = currentImage,
                half = currentHalf,
                contentDescription = strings.labelPageNumber(page + 1),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onLoaded = { bitmap ->
                    val w = bitmap.width
                    val h = bitmap.height
                    if (w > 0 && h > 0) {
                        viewModel.recordImageAspectRatio(currentSlice.imageIndex, w, h)
                    }
                },
            )
        }
    }
}

@Composable
private fun ReaderDualView(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    onRegisterActions: (onAdvance: () -> Unit, onRetreat: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val totalImages = state.readerImages.size
    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT
    val spreads = remember(state.readerImages.size, state.imageAspectRatios) {
        buildDualSpreads(state.readerImages.size, state.imageAspectRatios)
    }
    val totalSpreads = spreads.size.coerceAtLeast(1)
    val initialSpread = remember(state.currentEpisode?.wrId, spreads) {
        val target = state.currentEpisode?.lastReadPage ?: 0
        val found = spreads.indexOfFirst { spread ->
            when (spread) {
                is DualSpread.Single -> spread.index >= target
                is DualSpread.Dual -> spread.secondIndex >= target
            }
        }
        if (found >= 0) found.coerceIn(0, totalSpreads - 1) else 0
    }

    val pagerState = rememberPagerState(
        initialPage = initialSpread,
        pageCount = { totalSpreads },
    )
    val scope = rememberCoroutineScope()

    var restoredWrId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.currentEpisode?.wrId, state.readerImages.isNotEmpty()) {
        val wrId = state.currentEpisode?.wrId
        if (wrId != null && wrId != restoredWrId && state.readerImages.isNotEmpty()) {
            restoredWrId = wrId
            val target = state.currentEpisode?.lastReadPage ?: 0
            val found = spreads.indexOfFirst { spread ->
                when (spread) {
                    is DualSpread.Single -> spread.index >= target
                    is DualSpread.Dual -> spread.secondIndex >= target
                }
            }
            if (found in 0 until totalSpreads && found != pagerState.currentPage) {
                pagerState.scrollToPage(found)
            }
        }
    }

    LaunchedEffect(pagerState.currentPage, spreads) {
        if (state.readerImages.isNotEmpty() && spreads.isNotEmpty()) {
            val spread = spreads.getOrNull(pagerState.currentPage)
            if (spread != null) {
                val pageToSave = when (spread) {
                    is DualSpread.Single -> spread.index
                    is DualSpread.Dual -> if (isR2L) spread.secondIndex else spread.firstIndex
                }
                viewModel.savePage(pageToSave)
            }
        }
    }

    LaunchedEffect(pagerState.currentPage, spreads, state.readerImages) {
        if (state.readerImages.isNotEmpty() && spreads.isNotEmpty()) {
            val preloadIndices = listOf(-1, 1, 2).flatMap { offset ->
                when (val spread = spreads.getOrNull(pagerState.currentPage + offset)) {
                    is DualSpread.Single -> listOf(spread.index)
                    is DualSpread.Dual -> listOf(spread.firstIndex, spread.secondIndex)
                    null -> emptyList()
                }
            }
            val preloadUrls = preloadIndices.mapNotNull { state.readerImages.getOrNull(it) }
            com.comics8.desktop.ui.util.DesktopImageCache.preload(ImageCacheRole.READER, preloadUrls)
        }
    }

    val currentSpread = spreads.getOrNull(pagerState.currentPage)
    val currentRangeText = remember(currentSpread, totalImages, isR2L) {
        when (currentSpread) {
            is DualSpread.Single -> "${currentSpread.index + 1} / $totalImages"
            is DualSpread.Dual -> {
                val i1 = currentSpread.firstIndex + 1
                val i2 = currentSpread.secondIndex + 1
                if (isR2L) "$i2-$i1 / $totalImages" else "$i1-$i2 / $totalImages"
            }
            null -> ""
        }
    }

    ReaderPagedLayout(
        state = state,
        viewModel = viewModel,
        pagerState = pagerState,
        isR2L = isR2L,
        currentRangeText = currentRangeText,
        onRegisterActions = onRegisterActions,
        modifier = modifier,
    ) { page ->
        when (val spread = spreads.getOrNull(page)) {
                is DualSpread.Single -> {
                    val ratio = state.imageAspectRatios[spread.index]
                    val isWide = ratio != null && ratio >= 1.0f
                    if (isWide) {
                        val url = state.readerImages.getOrNull(spread.index)
                        if (url != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                DesktopAsyncImage(
                                    cacheRole = ImageCacheRole.READER,
                                    url = url,
                                    contentDescription = strings.labelPageNumber(spread.index + 1),
                                    contentScale = ContentScale.Fit,
                                    alignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize(),
                                    onLoaded = { bmp ->
                                        viewModel.recordImageAspectRatio(spread.index, bmp.width, bmp.height)
                                    },
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 4.dp),
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
                                    val url = state.readerImages.getOrNull(spread.index)
                                    if (url != null) {
                                        DesktopAsyncImage(
                                            cacheRole = ImageCacheRole.READER,
                                            url = url,
                                            contentDescription = strings.labelPageNumber(spread.index + 1),
                                            contentScale = ContentScale.Fit,
                                            alignment = Alignment.CenterStart,
                                            modifier = Modifier.fillMaxSize(),
                                            onLoaded = { bmp ->
                                                viewModel.recordImageAspectRatio(spread.index, bmp.width, bmp.height)
                                            },
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
                                    val url = state.readerImages.getOrNull(spread.index)
                                    if (url != null) {
                                        DesktopAsyncImage(
                                            cacheRole = ImageCacheRole.READER,
                                            url = url,
                                            contentDescription = strings.labelPageNumber(spread.index + 1),
                                            contentScale = ContentScale.Fit,
                                            alignment = Alignment.CenterEnd,
                                            modifier = Modifier.fillMaxSize(),
                                            onLoaded = { bmp ->
                                                viewModel.recordImageAspectRatio(spread.index, bmp.width, bmp.height)
                                            },
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
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            val url = state.readerImages.getOrNull(leftIndex)
                            if (url != null) {
                                DesktopAsyncImage(
                                    cacheRole = ImageCacheRole.READER,
                                    url = url,
                                    contentDescription = strings.labelPageNumber(leftIndex + 1),
                                    contentScale = ContentScale.Fit,
                                    alignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize(),
                                    onLoaded = { bmp ->
                                        viewModel.recordImageAspectRatio(leftIndex, bmp.width, bmp.height)
                                    },
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            val url = state.readerImages.getOrNull(rightIndex)
                            if (url != null) {
                                DesktopAsyncImage(
                                    cacheRole = ImageCacheRole.READER,
                                    url = url,
                                    contentDescription = strings.labelPageNumber(rightIndex + 1),
                                    contentScale = ContentScale.Fit,
                                    alignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize(),
                                    onLoaded = { bmp ->
                                        viewModel.recordImageAspectRatio(rightIndex, bmp.width, bmp.height)
                                    },
                                )
                            }
                        }
                    }
                }
                null -> {}
            }
    }
}

@Composable
private fun BoxScope.EpisodeBoundaryPrompt(
    visible: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 72.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
            shadowElevation = 6.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .clickable(onClick = onClick),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inversePrimary,
                    modifier = Modifier.padding(top = 2.dp),
                )
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
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        if (maxPages > 1) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(0.6f),
            ) {
                Text(
                    text = currentRangeText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 12.dp),
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .padding(top = 4.dp),
        ) {
            OutlinedButton(
                onClick = onPrevEp,
                enabled = hasPrevEpisode,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.actionPrevEpisode)
            }
            Button(
                onClick = onClose,
                modifier = Modifier.weight(1f),
            ) {
                Text(strings.actionEpisodeList)
            }
            OutlinedButton(
                onClick = onNextEp,
                enabled = hasNextEpisode,
                modifier = Modifier.weight(1f),
            ) {
                Text(strings.actionNextEpisode)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

