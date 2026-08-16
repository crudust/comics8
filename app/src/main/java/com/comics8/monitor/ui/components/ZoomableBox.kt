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
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            val centerX = size.width / 2f
                            val centerY = size.height / 2f
                            val targetX = (centerX - tapOffset.x) * 1.5f
                            val targetY = (centerY - tapOffset.y) * 1.5f
                            val maxX = (size.width * 1.5f) / 2f
                            val maxY = (size.height * 1.5f) / 2f
                            offset = Offset(
                                targetX.coerceIn(-maxX, maxX),
                                targetY.coerceIn(-maxY, maxY),
                            )
                        }
                    },
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
                                offset = Offset(
                                    (offset.x + panChange.x).coerceIn(-maxX, maxX),
                                    (offset.y + panChange.y).coerceIn(-maxY, maxY),
                                )
                            }
                            if (scale > 1.05f) {
                                event.changes.forEach { it.consume() }
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
