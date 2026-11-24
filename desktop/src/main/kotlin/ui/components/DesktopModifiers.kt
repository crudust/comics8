package com.comics8.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import com.comics8.core.model.ReaderDomain
import com.comics8.core.model.ReaderDomain.BoundaryDirection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.abs

const val IMPORT_JS_LABEL = "Import JS File"

fun pickJsFile(dialogTitle: String = IMPORT_JS_LABEL): File? {
    val dialog = FileDialog(null as Frame?, dialogTitle, FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.lowercase().endsWith(".js") }
    dialog.file = "*.js"
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    val file = File(dir, name)
    return file.takeIf { it.isFile }
}

/**
 * Discrete Trackpad / Mouse Wheel Scroll Paging Recognizer.
 * Converts continuous scroll event streams and momentum/inertia drift from macOS trackpads
 * into discrete, single-turn page transitions with guaranteed single trigger per gesture.
 *
 * Gesture ownership contract:
 * - Scroll events are observed on [PointerEventPass.Initial] so a recognised page turn / pull-to-refresh
 *   is consumed before the inner scrollable applies it (no scroll + page turn double-apply).
 * - A stream locks its dominant axis once; a vertical list stream never turns pages.
 * - Pull-to-refresh requires an upward drag that started at the top edge.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.desktopDiscreteScrollPaging(
    enabled: Boolean = true,
    isR2L: Boolean = false,
    allowVerticalWheel: Boolean = false,
    threshold: Float = ReaderDomain.SCROLL_PAGE_THRESHOLD,
    idleThreshold: Float = ReaderDomain.SCROLL_IDLE_THRESHOLD,
    cooldownMs: Long = ReaderDomain.SCROLL_LATCH_COOLDOWN_MS,
    onAdvance: () -> Unit = {},
    onRetreat: () -> Unit = {},
    onPullRefresh: (() -> Unit)? = null,
    isAtTop: () -> Boolean = { false },
): Modifier {
    val isEnabled by rememberUpdatedState(enabled)
    val currentIsR2L by rememberUpdatedState(isR2L)
    val currentOnAdvance by rememberUpdatedState(onAdvance)
    val currentOnRetreat by rememberUpdatedState(onRetreat)
    val currentOnPullRefresh by rememberUpdatedState(onPullRefresh)
    val currentIsAtTop by rememberUpdatedState(isAtTop)
    val scope = rememberCoroutineScope()

    var isLatched by remember { mutableStateOf(false) }
    var lastTriggerEpoch by remember { mutableLongStateOf(0L) }
    var idleJob by remember { mutableStateOf<Job?>(null) }
    var streamStartedAtTop by remember { mutableStateOf(false) }
    var streamAxis by remember { mutableStateOf(ReaderDomain.ScrollGestureAxis.UNDECIDED) }

    DisposableEffect(Unit) {
        onDispose {
            idleJob?.cancel()
        }
    }

    return this.onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
        if (event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed) {
            return@onPointerEvent
        }
        val deltaX = event.changes.sumOf { it.scrollDelta.x.toDouble() }.toFloat()
        val deltaY = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
        val absX = abs(deltaX)
        val absY = abs(deltaY)

        idleJob?.cancel()
        idleJob = scope.launch {
            val elapsed = System.currentTimeMillis() - lastTriggerEpoch
            val remainingCooldown = (cooldownMs - elapsed).coerceAtLeast(0L)
            delay(maxOf(150L, remainingCooldown))
            isLatched = false
            streamStartedAtTop = false
            streamAxis = ReaderDomain.ScrollGestureAxis.UNDECIDED
        }

        if (streamAxis == ReaderDomain.ScrollGestureAxis.UNDECIDED) {
            streamAxis = ReaderDomain.resolveScrollGestureAxis(deltaX, deltaY, idleThreshold)
        }

        if (!streamStartedAtTop && currentIsAtTop()) {
            streamStartedAtTop = true
        }

        val isAtTopNow = currentIsAtTop()
        val isVerticalPull = currentOnPullRefresh != null && ReaderDomain.isPullRefreshGesture(
            deltaX = deltaX,
            deltaY = deltaY,
            isStreamArmedAtTop = streamStartedAtTop,
            isAtTop = isAtTopNow,
        )
        val isHorizontalPageTurnStream =
            streamAxis == ReaderDomain.ScrollGestureAxis.HORIZONTAL && absX > absY

        if (!allowVerticalWheel && !isVerticalPull && !isHorizontalPageTurnStream) {
            return@onPointerEvent
        }

        if (ReaderDomain.isScrollIdle(deltaX, deltaY, idleThreshold)) {
            if (System.currentTimeMillis() - lastTriggerEpoch >= cooldownMs) {
                isLatched = false
            }
            return@onPointerEvent
        }

        if (isLatched) {
            event.changes.forEach { it.consume() }
            return@onPointerEvent
        }

        if (!isEnabled || System.currentTimeMillis() - lastTriggerEpoch < cooldownMs) {
            return@onPointerEvent
        }

        if (isVerticalPull) {
            isLatched = true
            lastTriggerEpoch = System.currentTimeMillis()
            event.changes.forEach { it.consume() }
            currentOnPullRefresh?.invoke()
            return@onPointerEvent
        }

        val direction = ReaderDomain.resolveScrollDirection(
            deltaX = deltaX,
            deltaY = deltaY,
            isR2L = currentIsR2L,
            threshold = threshold,
        ) ?: return@onPointerEvent

        isLatched = true
        lastTriggerEpoch = System.currentTimeMillis()
        event.changes.forEach { it.consume() }

        if (direction == BoundaryDirection.ADVANCE) {
            currentOnAdvance()
        } else {
            currentOnRetreat()
        }
    }
}

/**
 * Clean floating pull-to-refresh spinner for desktop lists/grids.
 */
@Composable
fun DesktopPullToRefreshIndicator(
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = refreshing,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 4.dp,
            modifier = Modifier.padding(top = 12.dp).size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
