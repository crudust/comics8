package com.comics8.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SinglePageSliceTest {
    @Test
    fun fitModeReturnsAllFullPages() {
        val slices = buildSinglePageSlices(
            imageCount = 3,
            aspectRatios = mapOf(1 to 1.4f),
            splitMode = SplitMode.FIT,
            readDirection = ReadDirection.RIGHT_TO_LEFT,
        )
        assertThat(slices).containsExactly(
            SinglePageSlice(0, ImageHalf.FULL),
            SinglePageSlice(1, ImageHalf.FULL),
            SinglePageSlice(2, ImageHalf.FULL),
        ).inOrder()
    }

    @Test
    fun sliceModeRightToLeftSplitsWidePageRightThenLeft() {
        val slices = buildSinglePageSlices(
            imageCount = 3,
            aspectRatios = mapOf(1 to 1.5f), // Image 1 is wide (spread scan)
            splitMode = SplitMode.SLICE,
            readDirection = ReadDirection.RIGHT_TO_LEFT,
        )
        assertThat(slices).containsExactly(
            SinglePageSlice(0, ImageHalf.FULL),
            SinglePageSlice(1, ImageHalf.RIGHT),
            SinglePageSlice(1, ImageHalf.LEFT),
            SinglePageSlice(2, ImageHalf.FULL),
        ).inOrder()
    }

    @Test
    fun sliceModeLeftToRightSplitsWidePageLeftThenRight() {
        val slices = buildSinglePageSlices(
            imageCount = 3,
            aspectRatios = mapOf(1 to 1.5f), // Image 1 is wide (spread scan)
            splitMode = SplitMode.SLICE,
            readDirection = ReadDirection.LEFT_TO_RIGHT,
        )
        assertThat(slices).containsExactly(
            SinglePageSlice(0, ImageHalf.FULL),
            SinglePageSlice(1, ImageHalf.LEFT),
            SinglePageSlice(1, ImageHalf.RIGHT),
            SinglePageSlice(2, ImageHalf.FULL),
        ).inOrder()
    }

    @Test
    fun sliceModeKeepsNormalPortraitsIntact() {
        val slices = buildSinglePageSlices(
            imageCount = 2,
            aspectRatios = mapOf(0 to 0.7f, 1 to 0.75f),
            splitMode = SplitMode.SLICE,
            readDirection = ReadDirection.RIGHT_TO_LEFT,
        )
        assertThat(slices).containsExactly(
            SinglePageSlice(0, ImageHalf.FULL),
            SinglePageSlice(1, ImageHalf.FULL),
        ).inOrder()
    }
}
