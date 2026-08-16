package com.comics8.monitor.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
                // Instant tap response (0ms delay - no double-tap detector)
                detectTapGestures(
                    onTap = { tapOffset ->
                        onTap?.invoke(tapOffset, size)
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        if (zoomChange != 1f || scale > 1.05f) {
                            val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                            if (newScale <= 1.02f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = newScale
                                val maxX = (size.width * (newScale - 1f)) / 2f
                                val maxY = (size.height * (newScale - 1f)) / 2f
                                val targetX = (offset.x + panChange.x).coerceIn(-maxX, maxX)
                                val targetY = (offset.y + panChange.y).coerceIn(-maxY, maxY)

                                // Consume event if within pan bounds
                                val isAtHorizontalEdge = (targetX == offset.x && panChange.x != 0f)
                                if (!isAtHorizontalEdge || event.changes.size > 1) {
                                    event.changes.forEach { it.consume() }
                                }
                                offset = Offset(targetX, targetY)
                            }
                        }
                    } while (event.changes.any { it.pressed })
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
