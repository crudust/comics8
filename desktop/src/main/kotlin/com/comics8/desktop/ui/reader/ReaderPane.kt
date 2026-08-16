package com.comics8.desktop.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comics8.desktop.data.DualSpread
import com.comics8.desktop.data.ReadDirection
import com.comics8.desktop.data.ViewMode
import com.comics8.desktop.data.buildDualSpreads
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.components.ErrorPane
import com.comics8.desktop.ui.components.LoadingPane
import com.comics8.desktop.ui.components.TopBar
import com.comics8.desktop.ui.util.DesktopAsyncImage

@Composable
fun ReaderPane(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Escape -> {
                            viewModel.closeReader()
                            true
                        }
                        Key.F -> {
                            viewModel.toggleFullscreen()
                            true
                        }
                        else -> false
                    }
                } else false
            },
    ) {
        when {
            state.readerLoading && state.readerImages.isEmpty() -> {
                LoadingPane()
            }
            state.readerError != null && state.readerImages.isEmpty() -> {
                ErrorPane(
                    message = state.readerError,
                    actionLabel = "다시 시도",
                    onRetry = { state.currentEpisode?.let { viewModel.openEpisode(it) } },
                )
            }
            else -> {
                when (state.viewMode) {
                    ViewMode.SCROLL -> ReaderScrollView(state, viewModel)
                    ViewMode.SINGLE -> ReaderSingleView(state, viewModel)
                    ViewMode.DUAL -> ReaderDualView(state, viewModel)
                }
            }
        }
    }
}

@Composable
private fun ReaderScrollView(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
) {
    val listState = rememberLazyListState()
    var controlsVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.readerImages, state.currentEpisode?.lastReadPage) {
        val target = state.currentEpisode?.lastReadPage ?: 0
        if (state.readerImages.isNotEmpty() && target > 0) {
            listState.scrollToItem(target.coerceIn(0, state.readerImages.size - 1))
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
                    url = url,
                    contentDescription = "페이지 ${index + 1}",
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
                            Text("이전화")
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    if (state.hasNextEpisode) {
                        Button(onClick = viewModel::openNextEpisode) {
                            Text("다음화")
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
) {
    var currentPage by remember(state.currentEpisode?.wrId) {
        mutableStateOf(
            (state.currentEpisode?.lastReadPage ?: 0).coerceIn(0, (state.readerImages.size - 1).coerceAtLeast(0)),
        )
    }
    var controlsVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.readerImages, state.currentEpisode?.lastReadPage) {
        val target = state.currentEpisode?.lastReadPage ?: 0
        if (state.readerImages.isNotEmpty() && target > 0) {
            currentPage = target.coerceIn(0, state.readerImages.size - 1)
        }
    }

    LaunchedEffect(currentPage) {
        if (state.readerImages.isNotEmpty()) {
            viewModel.savePage(currentPage)
        }
    }

    val totalPages = state.readerImages.size
    val currentImage = state.readerImages.getOrNull(currentPage)
    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT

    val goNext = {
        if (currentPage < totalPages - 1) {
            currentPage++
        } else if (state.hasNextEpisode) {
            viewModel.openNextEpisode()
        }
    }

    val goPrev = {
        if (currentPage > 0) {
            currentPage--
        } else if (state.hasPrevEpisode) {
            viewModel.openPrevEpisode()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionRight -> {
                            if (isR2L) goPrev() else goNext()
                            true
                        }
                        Key.DirectionLeft -> {
                            if (isR2L) goNext() else goPrev()
                            true
                        }
                        Key.Spacebar -> {
                            goNext()
                            true
                        }
                        else -> false
                    }
                } else false
            },
    ) {
        if (currentImage != null) {
            DesktopAsyncImage(
                url = currentImage,
                contentDescription = "페이지 ${currentPage + 1}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        controlsVisible = !controlsVisible
                    },
            )
        }

        // Left / Right Click zones for navigation
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (isR2L) goNext() else goPrev()
                    },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
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
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (isR2L) goPrev() else goNext()
                    },
            )
        }

        // Page Indicator
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
private fun ReaderDualView(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
) {
    val totalImages = state.readerImages.size
    val spreads = remember(state.readerImages.size, state.imageAspectRatios) {
        buildDualSpreads(state.readerImages.size, state.imageAspectRatios)
    }
    val totalSpreads = spreads.size.coerceAtLeast(1)

    var currentSpreadIndex by remember(state.currentEpisode?.wrId, spreads) {
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

    LaunchedEffect(currentSpreadIndex, spreads) {
        val spread = spreads.getOrNull(currentSpreadIndex)
        if (spread != null) {
            val firstPage = when (spread) {
                is DualSpread.Single -> spread.index
                is DualSpread.Dual -> spread.firstIndex
            }
            viewModel.savePage(firstPage)
        }
    }

    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT

    val goNext = {
        if (currentSpreadIndex < totalSpreads - 1) {
            currentSpreadIndex++
        } else if (state.hasNextEpisode) {
            viewModel.openNextEpisode()
        }
    }

    val goPrev = {
        if (currentSpreadIndex > 0) {
            currentSpreadIndex--
        } else if (state.hasPrevEpisode) {
            viewModel.openPrevEpisode()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionRight -> {
                            if (isR2L) goPrev() else goNext()
                            true
                        }
                        Key.DirectionLeft -> {
                            if (isR2L) goNext() else goPrev()
                            true
                        }
                        Key.Spacebar -> {
                            goNext()
                            true
                        }
                        else -> false
                    }
                } else false
            },
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
                                url = url,
                                contentDescription = "페이지 ${spread.index + 1}",
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
                                        url = url,
                                        contentDescription = "페이지 ${spread.index + 1}",
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
                                        url = url,
                                        contentDescription = "페이지 ${spread.index + 1}",
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
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        val url = state.readerImages.getOrNull(leftIndex)
                        if (url != null) {
                            DesktopAsyncImage(
                                url = url,
                                contentDescription = "페이지 ${leftIndex + 1}",
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterEnd,
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
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        val url = state.readerImages.getOrNull(rightIndex)
                        if (url != null) {
                            DesktopAsyncImage(
                                url = url,
                                contentDescription = "페이지 ${rightIndex + 1}",
                                contentScale = ContentScale.Fit,
                                alignment = Alignment.CenterStart,
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
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (isR2L) goNext() else goPrev()
                    },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
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
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (isR2L) goPrev() else goNext()
                    },
            )
        }

        // Spread Indicator
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
