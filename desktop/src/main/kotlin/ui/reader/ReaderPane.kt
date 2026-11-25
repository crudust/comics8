package com.comics8.desktop.ui.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.comics8.core.model.ReadDirection
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.components.ZoomableBox
import com.comics8.desktop.ui.components.desktopDiscreteScrollPaging
import com.comics8.shared.ui.reader.ReaderPlatformConfig
import com.comics8.shared.ui.reader.SharedReaderPane

private val DesktopReaderConfig = ReaderPlatformConfig(
    scrollContentWidthFraction = 0.65f,
    bottomBarWidthFraction = 0.6f,
    compactTopBar = false,
    pagerUserScrollEnabled = false,
    respectTapZonePreference = false,
    tapEdgeFraction = 0.38f,
    indicatorAlignment = Alignment.BottomCenter,
    scrollPlaceholderMinHeight = 200.dp,
    singlePreloadOffsets = listOf(-1, 1, 2),
    dualPreloadOffsets = listOf(-1, 1, 2),
    retryReopensEpisode = true,
    showBottomBarIcons = true,
    showFooterEpisodeListButton = false,
    showViewerOptionsSheet = false,
    showReaderToast = true,
    applyNavigationBarsPadding = false,
)

@Composable
fun ReaderPane(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyDispatcher = LocalReaderKeyDispatcher.current
    var onAdvanceAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onRetreatAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val currentIsR2L by rememberUpdatedState(state.readDirection == ReadDirection.RIGHT_TO_LEFT)
    val currentHasReaderContent by rememberUpdatedState(state.readerImages.isNotEmpty())
    val currentOnAdvance by rememberUpdatedState(onAdvanceAction)
    val currentOnRetreat by rememberUpdatedState(onRetreatAction)

    // Clear focus left on clicked overlay chips/buttons or retry controls so Spacebar never re-triggers them.
    LaunchedEffect(
        state.currentEpisode?.wrId,
        state.effectiveViewMode,
        state.readDirection,
        state.splitMode,
        state.seriesFavorited,
        state.readerImages.isEmpty(),
        state.readerLoading,
    ) {
        focusManager.clearFocus(force = true)
    }

    val keyHandler = remember {
        { input: ReaderKeyInput ->
            val command = resolveReaderKeyCommand(
                key = input.key,
                isKeyDown = input.isKeyDown,
                isModifierPressed = input.isModifierPressed,
                isR2L = currentIsR2L,
            )
            when (command) {
                ReaderKeyCommand.TOGGLE_FULLSCREEN -> {
                    viewModel.toggleFullscreen()
                    true
                }
                ReaderKeyCommand.ADVANCE -> {
                    if (currentHasReaderContent) currentOnAdvance?.invoke()
                    true
                }
                ReaderKeyCommand.RETREAT -> {
                    if (currentHasReaderContent) currentOnRetreat?.invoke()
                    true
                }
                null -> false
            }
        }
    }

    DisposableEffect(keyDispatcher) {
        if (keyDispatcher != null) {
            keyDispatcher.register(keyHandler)
            onDispose { keyDispatcher.unregister(keyHandler) }
        } else {
            onDispose {}
        }
    }

    SharedReaderPane(
        state = state,
        controller = viewModel.controller,
        config = DesktopReaderConfig,
        zoomContainer = { resetKey, isActivePage, onTap, onScaleChanged, content ->
            ZoomableBox(
                modifier = Modifier.fillMaxSize(),
                resetKey = resetKey,
                trackpadPinchEnabled = isActivePage,
                onTap = onTap,
                onScaleChanged = onScaleChanged,
            ) { scale ->
                content(scale)
            }
        },
        pagedModifier = { _, pagerIsR2L, isCurrentPageZoomed, onAdvance, onRetreat ->
            Modifier.desktopDiscreteScrollPaging(
                enabled = !isCurrentPageZoomed,
                isR2L = pagerIsR2L,
                allowVerticalWheel = true,
                onAdvance = onAdvance,
                onRetreat = onRetreat,
            )
        },
        onRegisterNavActions = { adv, ret ->
            onAdvanceAction = adv
            onRetreatAction = ret
        },
        modifier = modifier.fillMaxSize(),
    )
}
