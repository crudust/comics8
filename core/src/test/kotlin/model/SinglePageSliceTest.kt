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

    @Test
    fun computeContentCropRectTrimsWhiteMargins() {
        // Create 200x200 simulated image where outer 20px is white (0xFFFFFF) and inner [20..180] has dark content (0x000000)
        val width = 200
        val height = 200
        val getPixel = { x: Int, y: Int ->
            if (x in 20 until 180 && y in 20 until 180) {
                0x000000 // Black content
            } else {
                0xFFFFFF // White margin
            }
        }

        val crop = computeContentCropRect(
            regionLeft = 0,
            regionTop = 0,
            regionRight = width,
            regionBottom = height,
            maxCropFractionX = 0.2f,
            maxCropFractionY = 0.2f,
            getPixelRgb = getPixel,
        )

        // With 2px safety padding, crop should be around [18..182]
        assertThat(crop.left).isAtLeast(16)
        assertThat(crop.left).isAtMost(20)
        assertThat(crop.top).isAtLeast(16)
        assertThat(crop.top).isAtMost(20)
        assertThat(crop.right).isAtLeast(180)
        assertThat(crop.right).isAtMost(184)
        assertThat(crop.bottom).isAtLeast(180)
        assertThat(crop.bottom).isAtMost(184)
    }

    @Test
    fun computeContentCropRectTrimsWideSpreadGutterMargin() {
        // Simulated 1000x1500 left half of a 2-page scan
        // Left margin is at [0..40], Content is at [40..650], Center gutter margin is at [650..1000] (35% of width)
        // Background has off-white noise (RGB 0xEBEBEB = 235,235,235)
        val width = 1000
        val height = 1500
        val getPixel = { x: Int, y: Int ->
            if (x in 40..650 && y in 50..1420) {
                0x101010 // Dark comic art
            } else {
                0xEBEBEB // Off-white scanner margin (RGB 235)
            }
        }

        val crop = computeContentCropRect(
            regionLeft = 0,
            regionTop = 0,
            regionRight = width,
            regionBottom = height,
            getPixelRgb = getPixel,
        )

        // Left margin should be cropped from 0 inwards towards ~34..42
        assertThat(crop.left).isAtLeast(30)
        assertThat(crop.left).isAtMost(45)
        // Right gutter margin should be cropped from 1000 down towards ~645..660
        assertThat(crop.right).isAtLeast(645)
        assertThat(crop.right).isAtMost(660)
    }
}
