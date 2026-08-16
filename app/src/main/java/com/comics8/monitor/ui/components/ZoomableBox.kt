package com.comics8.monitor.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

@Composable
fun ZoomableBox(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    onTap: ((Offset, IntSize) -> Unit)? = null,
    content: @Composable BoxScope.(scale: Float) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
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

                        // If user moved more than standard touch slop, it's a drag/pan, not a tap
                        if (abs(totalDragX) > 8f || abs(totalDragY) > 8f || zoomChange != 1f) {
                            isTap = false
                        }

                        // Handle zoom & pan
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
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    // If it was a clean tap (finger released without dragging)
                    val duration = System.currentTimeMillis() - downTime
                    if (isTap && !isMultiTouch && duration < 600) {
                        onTap?.invoke(downPos, size)
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
                },
            contentAlignment = contentAlignment,
        ) {
            content(scale)
        }
    }
}
