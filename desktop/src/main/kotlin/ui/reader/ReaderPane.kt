package com.comics8.desktop.ui.reader

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val focusRequester = remember { FocusRequester() }
    var onAdvanceAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onRetreatAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val isR2L = state.readDirection == ReadDirection.RIGHT_TO_LEFT

    LaunchedEffect(Unit, state.currentEpisode?.wrId) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {
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
        modifier = modifier
            .fillMaxSize()
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
                        key == Key.Escape || isBack -> {
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
                } else {
                    false
                }
            },
    )
}
