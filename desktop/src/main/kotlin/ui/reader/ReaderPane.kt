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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.comics8.core.model.SinglePageSlice
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ViewMode
import com.comics8.core.model.buildDualSpreads
import com.comics8.core.model.buildSinglePageSlices
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.components.ErrorPane
import com.comics8.desktop.ui.components.LoadingPane
import com.comics8.desktop.ui.components.TopBar
import com.comics8.desktop.ui.theme.LocalStrings
import com.comics8.desktop.ui.util.DesktopAsyncImage

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun rememberWheelPageScroller(
    onNextPage: () -> Unit,
    onPrevPage: () -> Unit,
    isR2L: Boolean = false,
    threshold: Float = 0.5f,
    cooldownMs: Long = 200L,
): Modifier {
    var accumulatedDeltaY by remember { mutableStateOf(0f) }
    var accumulatedDeltaX by remember { mutableStateOf(0f) }
    var lastTriggerTime by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    var resetJob by remember { mutableStateOf<Job?>(null) }

    return Modifier.onPointerEvent(PointerEventType.Scroll) { event ->
        val now = System.currentTimeMillis()
        var deltaY = 0f
        var deltaX = 0f
        event.changes.forEach { change ->
            deltaY += change.scrollDelta.y
            deltaX += change.scrollDelta.x
            change.consume()
        }

        accumulatedDeltaY += deltaY
        accumulatedDeltaX += deltaX

        // Reset accumulation after 200ms of inactivity
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(200L)
            accumulatedDeltaY = 0f
            accumulatedDeltaX = 0f
        }

        if (now - lastTriggerTime >= cooldownMs) {
            // Vertical Wheel / Trackpad Scroll (Down = Next, Up = Prev)
            if (accumulatedDeltaY >= threshold) {
                lastTriggerTime = now
                accumulatedDeltaY = 0f
                accumulatedDeltaX = 0f
                onNextPage()
            } else if (accumulatedDeltaY <= -threshold) {
                lastTriggerTime = now
                accumulatedDeltaY = 0f
                accumulatedDeltaX = 0f
                onPrevPage()
            }
            // Horizontal Trackpad 2-finger swipe
            else if (accumulatedDeltaX >= threshold) {
                lastTriggerTime = now
                accumulatedDeltaY = 0f
                accumulatedDeltaX = 0f
                if (isR2L) onPrevPage() else onNextPage()
            } else if (accumulatedDeltaX <= -threshold) {
                lastTriggerTime = now
                accumulatedDeltaY = 0f
                accumulatedDeltaX = 0f
                if (isR2L) onNextPage() else onPrevPage()
            }
        }
    }
}

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
private fun ReaderSingleView(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    onRegisterActions: (onAdvance: () -> Unit, onRetreat: () -> Unit) -> Unit,
) {
    val strings = LocalStrings.current
    val slices = remember(state.readerImages.size, state.imageAspectRatios, state.splitMode, state.readDirection) {
        buildSinglePageSlices(state.readerImages.size, state.imageAspectRatios, state.splitMode, state.readDirection)
    }
    val totalPages = slices.size.coerceAtLeast(1)
    var currentPage by remember(state.currentEpisode?.wrId) {
        val target = state.currentEpisode?.lastReadPage ?: 0
        val idx = slices.indexOfFirst { it.imageIndex >= target }
        mutableStateOf(if (idx >= 0) idx.coerceIn(0, totalPages - 1) else 0)
    }
    var controlsVisible by remember { mutableStateOf(false) }
    var nextPromptVisible by remember { mutableStateOf(false) }
    var prevPromptVisible by remember { mutableStateOf(false) }
    var promptJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    var restoredWrId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.currentEpisode?.wrId, state.readerImages.isNotEmpty()) {
        val wrId = state.currentEpisode?.wrId
        if (wrId != null && wrId != restoredWrId && state.readerImages.isNotEmpty()) {
            restoredWrId = wrId
            val target = state.currentEpisode?.lastReadPage ?: 0
            val idx = slices.indexOfFirst { it.imageIndex >= target }
            if (idx in 0 until totalPages && idx != currentPage) {
                currentPage = idx
            }
        }
    }

    var prevSlices by remember { mutableStateOf(slices) }
    if (slices != prevSlices) {
        val oldSlice = prevSlices.getOrNull(currentPage)
        if (oldSlice != null) {
            val activeImage = oldSlice.imageIndex
            val newIdx = slices.indexOfFirst { it.imageIndex == activeImage && it.half == oldSlice.half }
                .takeIf { it >= 0 }
                ?: slices.indexOfFirst { it.imageIndex == activeImage }
            if (newIdx >= 0) {
                currentPage = newIdx.coerceIn(0, totalPages - 1)
            } else {
                currentPage = currentPage.coerceIn(0, totalPages - 1)
            }
        }
        prevSlices = slices
    }

    LaunchedEffect(currentPage, slices) {
        if (state.readerImages.isNotEmpty() && slices.isNotEmpty()) {
            val slice = slices.getOrNull(currentPage)
            if (slice != null) {
                viewModel.savePage(slice.imageIndex)
            }
        }
    }

    LaunchedEffect(currentPage, slices, state.readerImages) {
        if (state.readerImages.isNotEmpty() && slices.isNotEmpty()) {
            val preloadUrls = listOf(-1, 1, 2).mapNotNull { offset ->
                slices.getOrNull(currentPage + offset)?.let { slice ->
                    state.readerImages.getOrNull(slice.imageIndex)
                }
            }
            com.comics8.desktop.ui.util.DesktopImageCache.preload(ImageCacheRole.READER, preloadUrls)
        }
    }

    val currentSlice = slices.getOrNull(currentPage)
    val currentImage = currentSlice?.let { state.readerImages.getOrNull(it.imageIndex) }
    val currentHalf = currentSlice?.half ?: ImageHalf.FULL
    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT

    val onAdvance: () -> Unit = {
        controlsVisible = false
        if (currentPage < totalPages - 1) {
            nextPromptVisible = false
            prevPromptVisible = false
            currentPage++
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

    val onRetreat: () -> Unit = {
        controlsVisible = false
        if (currentPage > 0) {
            nextPromptVisible = false
            prevPromptVisible = false
            currentPage--
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

    LaunchedEffect(onAdvance, onRetreat) {
        onRegisterActions(onAdvance, onRetreat)
    }

    val wheelModifier = rememberWheelPageScroller(
        onNextPage = { onAdvance() },
        onPrevPage = { onRetreat() },
        isR2L = isR2L,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(wheelModifier),
    ) {
        if (currentImage != null) {
            DesktopAsyncImage(
                cacheRole = ImageCacheRole.READER,
                url = currentImage,
                half = currentHalf,
                contentDescription = strings.labelPageNumber(currentPage + 1),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        controlsVisible = !controlsVisible
                        nextPromptVisible = false
                        prevPromptVisible = false
                    },
                onLoaded = { bitmap ->
                    val w = bitmap.width
                    val h = bitmap.height
                    if (w > 0 && h > 0) {
                        viewModel.recordImageAspectRatio(currentSlice.imageIndex, w, h)
                    }
                },
            )
        }

        // Left / Right Click zones for navigation
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

        // Page Indicator
        if (!controlsVisible) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            ) {
                Text(
                    text = "${currentPage + 1} / $totalPages",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // Boundary Confirmation Prompt
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
                currentRangeText = "${currentPage + 1} / $totalPages",
                currentPage = currentPage + 1,
                maxPages = totalPages,
                hasPrevEpisode = state.hasPrevEpisode,
                hasNextEpisode = state.hasNextEpisode,
                onPageChange = { targetPage ->
                    currentPage = (targetPage - 1).coerceIn(0, totalPages - 1)
                },
                onPrevEp = viewModel::openPrevEpisode,
                onNextEp = viewModel::openNextEpisode,
                onClose = viewModel::closeReader,
            )
        }
    }
}

@Composable
private fun ReaderDualView(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    onRegisterActions: (onAdvance: () -> Unit, onRetreat: () -> Unit) -> Unit,
) {
    val strings = LocalStrings.current
    val totalImages = state.readerImages.size
    val spreads = remember(state.readerImages.size, state.imageAspectRatios) {
        buildDualSpreads(state.readerImages.size, state.imageAspectRatios)
    }
    val totalSpreads = spreads.size.coerceAtLeast(1)

    var currentSpreadIndex by remember(state.currentEpisode?.wrId) {
        val target = state.currentEpisode?.lastReadPage ?: 0
        val found = spreads.indexOfFirst { spread ->
            when (spread) {
                is DualSpread.Single -> spread.index >= target
                is DualSpread.Dual -> spread.secondIndex >= target
            }
        }
        mutableStateOf(if (found >= 0) found.coerceIn(0, (totalSpreads - 1).coerceAtLeast(0)) else 0)
    }

    var controlsVisible by remember { mutableStateOf(false) }
    var nextPromptVisible by remember { mutableStateOf(false) }
    var prevPromptVisible by remember { mutableStateOf(false) }
    var promptJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

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
                if (idx in 0 until totalSpreads && idx != currentSpreadIndex) {
                    currentSpreadIndex = idx
                }
            }
        }
    }

    var prevSpreads by remember { mutableStateOf(spreads) }
    if (spreads != prevSpreads) {
        val oldSpread = prevSpreads.getOrNull(currentSpreadIndex)
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
            if (newIdx >= 0) {
                currentSpreadIndex = newIdx.coerceIn(0, totalSpreads - 1)
            } else {
                currentSpreadIndex = currentSpreadIndex.coerceIn(0, totalSpreads - 1)
            }
        }
        prevSpreads = spreads
    }

    LaunchedEffect(currentSpreadIndex, spreads) {
        val spread = spreads.getOrNull(currentSpreadIndex)
        if (spread != null) {
            val firstPage = when (spread) {
                is DualSpread.Single -> spread.index
                is DualSpread.Dual -> spread.firstIndex
            }
            val lastVisible = when (spread) {
                is DualSpread.Single -> spread.index
                is DualSpread.Dual -> spread.secondIndex
            }
            viewModel.savePage(firstPage, seenThroughPage = lastVisible)
        }
    }

    LaunchedEffect(currentSpreadIndex, spreads, state.readerImages) {
        if (state.readerImages.isNotEmpty() && spreads.isNotEmpty()) {
            val preloadUrls = mutableListOf<String>()
            for (offset in listOf(-1, 1)) {
                val nextSpread = spreads.getOrNull(currentSpreadIndex + offset)
                when (nextSpread) {
                    is DualSpread.Single -> state.readerImages.getOrNull(nextSpread.index)?.let { preloadUrls.add(it) }
                    is DualSpread.Dual -> {
                        state.readerImages.getOrNull(nextSpread.firstIndex)?.let { preloadUrls.add(it) }
                        state.readerImages.getOrNull(nextSpread.secondIndex)?.let { preloadUrls.add(it) }
                    }
                    null -> {}
                }
            }
            com.comics8.desktop.ui.util.DesktopImageCache.preload(ImageCacheRole.READER, preloadUrls)
        }
    }

    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT

    val onAdvance: () -> Unit = {
        controlsVisible = false
        if (currentSpreadIndex < totalSpreads - 1) {
            nextPromptVisible = false
            prevPromptVisible = false
            currentSpreadIndex++
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

    val onRetreat: () -> Unit = {
        controlsVisible = false
        if (currentSpreadIndex > 0) {
            nextPromptVisible = false
            prevPromptVisible = false
            currentSpreadIndex--
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

    LaunchedEffect(onAdvance, onRetreat) {
        onRegisterActions(onAdvance, onRetreat)
    }

    val wheelModifier = rememberWheelPageScroller(
        onNextPage = { onAdvance() },
        onPrevPage = { onRetreat() },
        isR2L = isR2L,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(wheelModifier),
    ) {
        when (val spread = spreads.getOrNull(currentSpreadIndex)) {
            is DualSpread.Single -> {
                val ratio = state.imageAspectRatios[spread.index]
                val isWide = ratio != null && ratio >= 1.0f

                if (isWide) {
                    // Wide 2-page panoramic spread -> Display centered across full width
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
                    // Isolated single portrait page (e.g. cover page) -> Position in proper half touching center spine
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

        // Left / Right Click zones for navigation
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

        // Spread Indicator
        if (!controlsVisible) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            ) {
                val currentSpreadObj = spreads.getOrNull(currentSpreadIndex)
                val pageDesc = when (currentSpreadObj) {
                    is DualSpread.Single -> "${currentSpreadObj.index + 1} / $totalImages"
                    is DualSpread.Dual -> {
                        val i1 = currentSpreadObj.firstIndex + 1
                        val i2 = currentSpreadObj.secondIndex + 1
                        if (isR2L) "$i2-$i1 / $totalImages" else "$i1-$i2 / $totalImages"
                    }
                    null -> ""
                }

                Text(
                    text = pageDesc,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // Boundary Confirmation Prompt
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
            val currentSpreadObj = spreads.getOrNull(currentSpreadIndex)
            val spreadDesc = when (currentSpreadObj) {
                is DualSpread.Single -> "${currentSpreadObj.index + 1} / $totalImages"
                is DualSpread.Dual -> {
                    val i1 = currentSpreadObj.firstIndex + 1
                    val i2 = currentSpreadObj.secondIndex + 1
                    if (isR2L) "$i2-$i1 / $totalImages" else "$i1-$i2 / $totalImages"
                }
                null -> ""
            }
            ReaderBottomBar(
                currentRangeText = spreadDesc,
                currentPage = currentSpreadIndex + 1,
                maxPages = totalSpreads,
                hasPrevEpisode = state.hasPrevEpisode,
                hasNextEpisode = state.hasNextEpisode,
                onPageChange = { targetSpread ->
                    currentSpreadIndex = (targetSpread - 1).coerceIn(0, totalSpreads - 1)
                },
                onPrevEp = viewModel::openPrevEpisode,
                onNextEp = viewModel::openNextEpisode,
                onClose = viewModel::closeReader,
            )
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

