package com.comics8.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.comics8.desktop.ui.components.MacTrackpadPinchBridge
import com.comics8.desktop.ui.components.TrackpadPinchDispatcher
import com.comics8.desktop.ui.components.applyZoomChange
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import javax.swing.JPanel

class TrackpadPinchZoomTest {

    @Test
    fun applyZoomChangeZoomsTowardFocalPointAndUnwindsOnZoomOut() {
        val size = IntSize(1000, 800)
        val focal = Offset(700f, 300f)

        val zoomedIn = applyZoomChange(
            currentScale = 1f,
            currentOffset = Offset.Zero,
            zoomFactor = 2.0f,
            containerSize = size,
            focalPoint = focal,
        )
        assertThat(zoomedIn.scale).isWithin(0.001f).of(2.0f)
        // Center is (500, 400); focal relative to center is (+200, -100)
        // offset = 0 * 2 + (+200, -100) * (1 - 2) = (-200, +100)
        assertThat(zoomedIn.offset.x).isWithin(0.001f).of(-200f)
        assertThat(zoomedIn.offset.y).isWithin(0.001f).of(100f)

        val zoomedOut = applyZoomChange(
            currentScale = zoomedIn.scale,
            currentOffset = zoomedIn.offset,
            zoomFactor = 0.5f,
            containerSize = size,
            focalPoint = focal,
        )
        assertThat(zoomedOut.scale).isEqualTo(1f)
        assertThat(zoomedOut.offset).isEqualTo(Offset.Zero)
    }

    @Test
    fun applyZoomChangeSnapsToOneAtThresholdAndClampsMaxScale() {
        val size = IntSize(1000, 800)

        val nearOne = applyZoomChange(
            currentScale = 1.05f,
            currentOffset = Offset(10f, -10f),
            zoomFactor = 0.96f,
            containerSize = size,
        )
        assertThat(nearOne.scale).isEqualTo(1f)
        assertThat(nearOne.offset).isEqualTo(Offset.Zero)

        val atMax = applyZoomChange(
            currentScale = 5f,
            currentOffset = Offset(300f, -200f),
            zoomFactor = 1.25f,
            containerSize = size,
            focalPoint = Offset(100f, 100f),
        )
        assertThat(atMax.scale).isEqualTo(5f)
        assertThat(atMax.offset.x).isWithin(0.001f).of(300f)
        assertThat(atMax.offset.y).isWithin(0.001f).of(-200f)
    }

    @Test
    fun applyZoomChangeHandlesZeroContainerSizeAndInvalidFactors() {
        val zeroSized = applyZoomChange(
            currentScale = 1f,
            currentOffset = Offset.Zero,
            zoomFactor = 1.5f,
            containerSize = IntSize.Zero,
            focalPoint = Offset(50f, 50f),
        )
        assertThat(zeroSized.scale).isWithin(0.001f).of(1.5f)
        assertThat(zeroSized.offset).isEqualTo(Offset.Zero)

        val invalid = applyZoomChange(
            currentScale = 2f,
            currentOffset = Offset(10f, 20f),
            zoomFactor = Float.NaN,
            containerSize = IntSize(500, 500),
        )
        assertThat(invalid.scale).isEqualTo(2f)
        assertThat(invalid.offset).isEqualTo(Offset(10f, 20f))
    }

    @Test
    fun dispatcherRoutesOnlyToActiveHandlerAndIgnoresZeroDeltas() {
        val dispatcher = TrackpadPinchDispatcher()
        assertThat(dispatcher.dispatch(0.1)).isFalse()

        val firstCalls = mutableListOf<Double>()
        val secondCalls = mutableListOf<Double>()
        val first: (Double) -> Boolean = { firstCalls += it; true }
        val second: (Double) -> Boolean = { secondCalls += it; true }

        dispatcher.register(first)
        assertThat(dispatcher.dispatch(0.0)).isFalse()
        assertThat(dispatcher.dispatch(Double.NaN)).isFalse()
        assertThat(dispatcher.dispatch(0.05)).isTrue()
        assertThat(firstCalls).containsExactly(0.05)

        dispatcher.register(second)
        assertThat(dispatcher.dispatch(-0.04)).isTrue()
        assertThat(firstCalls).containsExactly(0.05)
        assertThat(secondCalls).containsExactly(-0.04)

        dispatcher.unregister(second)
        assertThat(dispatcher.dispatch(0.02)).isTrue()
        assertThat(firstCalls).containsExactly(0.05, 0.02)

        dispatcher.unregister(first)
        assertThat(dispatcher.dispatch(0.02)).isFalse()
    }

    @Test
    fun macBridgeAttachesAndRemovesGestureHandlerOnMacOs() {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (!isMac) return

        val panel = JPanel()
        val handle = MacTrackpadPinchBridge.install(panel) { true }
        assertThat(panel.getClientProperty("com.apple.eawt.event.internalGestureHandler")).isNotNull()

        handle.close()
        // Removing should succeed without error and leave the component clean or empty handler list.
        val secondHandle = MacTrackpadPinchBridge.install(panel) { false }
        secondHandle.close()
    }
}
