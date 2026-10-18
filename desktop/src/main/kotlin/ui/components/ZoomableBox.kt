package com.comics8.desktop.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    contentAlignment: Alignment = Alignment.Center,
    onTap: ((Offset, IntSize) -> Unit)? = null,
    onScaleChanged: ((Float) -> Unit)? = null,
    content: @Composable BoxScope.(scale: Float) -> Unit,
) {
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnScaleChanged by rememberUpdatedState(onScaleChanged)

    LaunchedEffect(resetKey) {
        scale = 1f
        offset = Offset.Zero
        currentOnScaleChanged?.invoke(1f)
    }

    var lastTapTime by remember(resetKey) { mutableLongStateOf(0L) }
    var lastTapPos by remember(resetKey) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { size = it }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val isZoomModifier = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                if (isZoomModifier) {
                    val scrollDelta = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                    if (scrollDelta != 0f) {
                        val zoomFactor = if (scrollDelta < 0) 1.15f else 0.85f
                        val newScale = (scale * zoomFactor).coerceIn(1f, 5f)
                        if (newScale <= 1.02f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = newScale
                            val maxX = (size.width * (newScale - 1f)) / 2f
                            val maxY = (size.height * (newScale - 1f)) / 2f
                            offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
                        }
                        currentOnScaleChanged?.invoke(scale)
                        event.changes.forEach { it.consume() }
                    }
                } else if (scale > 1.05f) {
                    // Panning with wheel / trackpad scroll when zoomed in
                    val deltaX = event.changes.sumOf { it.scrollDelta.x.toDouble() }.toFloat() * -24f
                    val deltaY = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat() * -24f
                    val maxX = (size.width * (scale - 1f)) / 2f
                    val maxY = (size.height * (scale - 1f)) / 2f
                    offset = Offset(
                        (offset.x + deltaX).coerceIn(-maxX, maxX),
                        (offset.y + deltaY).coerceIn(-maxY, maxY),
                    )
                    event.changes.forEach { it.consume() }
                }
            }
            .pointerInput(resetKey) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    val downPos = down.position
                    var isTap = true
                    var isMultiTouch = false
                    var totalDragX = 0f
                    var totalDragY = 0f

                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1) {
                            isMultiTouch = true
                            isTap = false
                        }

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        totalDragX += panChange.x
                        totalDragY += panChange.y

                        if (abs(totalDragX) > 8f || abs(totalDragY) > 8f || zoomChange != 1f) {
                            isTap = false
                        }

                        if (zoomChange != 1f || (scale > 1.05f && !isTap)) {
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            if (newScale <= 1.02f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = newScale
                                val maxX = (size.width * (newScale - 1f)) / 2f
                                val maxY = (size.height * (newScale - 1f)) / 2f
                                val newX = (offset.x + panChange.x).coerceIn(-maxX, maxX)
                                val newY = (offset.y + panChange.y).coerceIn(-maxY, maxY)
                                offset = Offset(newX, newY)
                            }
                            currentOnScaleChanged?.invoke(scale)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    val duration = System.currentTimeMillis() - downTime
                    if (isTap && !isMultiTouch && duration < 500) {
                        val now = System.currentTimeMillis()
                        val isDoubleTap = (now - lastTapTime < 350L) &&
                            (abs(downPos.x - lastTapPos.x) < 40f && abs(downPos.y - lastTapPos.y) < 40f)

                        if (isDoubleTap) {
                            lastTapTime = 0L
                            if (scale > 1.05f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                val targetScale = 2.5f
                                scale = targetScale
                                val clickDelta = Offset(
                                    downPos.x - size.width / 2f,
                                    downPos.y - size.height / 2f,
                                )
                                val maxX = (size.width * (targetScale - 1f)) / 2f
                                val maxY = (size.height * (targetScale - 1f)) / 2f
                                offset = Offset(
                                    (-clickDelta.x * (targetScale - 1f)).coerceIn(-maxX, maxX),
                                    (-clickDelta.y * (targetScale - 1f)).coerceIn(-maxY, maxY),
                                )
                            }
                            currentOnScaleChanged?.invoke(scale)
                        } else {
                            lastTapTime = now
                            lastTapPos = downPos
                            currentOnTap?.invoke(downPos, size)
                        }
                    }
                }
            },
        contentAlignment = contentAlignment,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    clip = true
                },
            contentAlignment = contentAlignment,
        ) {
            content(scale)
        }
    }
}
