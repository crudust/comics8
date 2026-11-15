package com.comics8.desktop.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

internal data class ZoomTransformState(
    val scale: Float,
    val offset: Offset,
)

/**
 * Computes the next scale and clamped pan offset when zooming by [zoomFactor] around
 * [focalPoint] (in local [ZoomableBox] pixel coordinates, or container center when null).
 */
internal fun applyZoomChange(
    currentScale: Float,
    currentOffset: Offset,
    zoomFactor: Float,
    containerSize: IntSize,
    focalPoint: Offset? = null,
    minScale: Float = 1f,
    maxScale: Float = 5f,
    snapResetThreshold: Float = 1.02f,
): ZoomTransformState {
    if (!zoomFactor.isFinite() || zoomFactor <= 0f) {
        return ZoomTransformState(currentScale, currentOffset)
    }
    val newScale = (currentScale * zoomFactor).coerceIn(minScale, maxScale)
    if (newScale <= snapResetThreshold) {
        return ZoomTransformState(minScale, Offset.Zero)
    }
    val safeOldScale = currentScale.coerceAtLeast(minScale)
    val scaleRatio = newScale / safeOldScale
    val center = Offset(containerSize.width / 2f, containerSize.height / 2f)
    val relativeFocal = if (focalPoint != null) focalPoint - center else Offset.Zero
    val unclampedX = currentOffset.x * scaleRatio + relativeFocal.x * (1f - scaleRatio)
    val unclampedY = currentOffset.y * scaleRatio + relativeFocal.y * (1f - scaleRatio)
    val maxX = (containerSize.width * (newScale - 1f)) / 2f
    val maxY = (containerSize.height * (newScale - 1f)) / 2f
    return ZoomTransformState(
        scale = newScale,
        offset = Offset(
            if (maxX <= 0f) 0f else unclampedX.coerceIn(-maxX, maxX),
            if (maxY <= 0f) 0f else unclampedY.coerceIn(-maxY, maxY),
        ),
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    trackpadPinchEnabled: Boolean = false,
    contentAlignment: Alignment = Alignment.Center,
    onTap: ((Offset, IntSize) -> Unit)? = null,
    onScaleChanged: ((Float) -> Unit)? = null,
    content: @Composable BoxScope.(scale: Float) -> Unit,
) {
    var scale by remember(resetKey) { mutableFloatStateOf(1f) }
    var offset by remember(resetKey) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var lastPointerPos by remember { mutableStateOf<Offset?>(null) }
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnScaleChanged by rememberUpdatedState(onScaleChanged)
    val pinchDispatcher = LocalTrackpadPinchDispatcher.current

    val pinchHandler = rememberUpdatedState<(Double) -> Boolean> { magnificationDelta ->
        val zoomFactor = (1.0 + magnificationDelta).toFloat().coerceIn(0.5f, 2.0f)
        val next = applyZoomChange(
            currentScale = scale,
            currentOffset = offset,
            zoomFactor = zoomFactor,
            containerSize = size,
            focalPoint = lastPointerPos,
        )
        val scaleChanged = next.scale != scale
        scale = next.scale
        offset = next.offset
        if (scaleChanged) {
            currentOnScaleChanged?.invoke(scale)
        }
        true
    }
    val stablePinchRegistration = remember {
        { delta: Double -> pinchHandler.value(delta) }
    }

    DisposableEffect(pinchDispatcher, trackpadPinchEnabled) {
        if (pinchDispatcher != null && trackpadPinchEnabled) {
            pinchDispatcher.register(stablePinchRegistration)
            onDispose { pinchDispatcher.unregister(stablePinchRegistration) }
        } else {
            onDispose {}
        }
    }

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
            .onPointerEvent(PointerEventType.Enter, PointerEventPass.Initial) { event ->
                lastPointerPos = event.changes.firstOrNull()?.position
            }
            .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { event ->
                lastPointerPos = event.changes.firstOrNull()?.position
            }
            .onPointerEvent(PointerEventType.Exit, PointerEventPass.Initial) {
                lastPointerPos = null
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val isZoomModifier = event.keyboardModifiers.isCtrlPressed || event.keyboardModifiers.isMetaPressed
                if (isZoomModifier) {
                    val scrollDelta = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                    if (scrollDelta != 0f) {
                        val zoomFactor = if (scrollDelta < 0) 1.15f else 0.85f
                        val next = applyZoomChange(
                            currentScale = scale,
                            currentOffset = offset,
                            zoomFactor = zoomFactor,
                            containerSize = size,
                            focalPoint = event.changes.firstOrNull()?.position ?: lastPointerPos,
                        )
                        scale = next.scale
                        offset = next.offset
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
                                val next = applyZoomChange(
                                    currentScale = scale,
                                    currentOffset = offset,
                                    zoomFactor = 2.5f / scale.coerceAtLeast(1f),
                                    containerSize = size,
                                    focalPoint = downPos,
                                )
                                scale = next.scale
                                offset = next.offset
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
