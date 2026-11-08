package com.comics8.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReaderTilesTest {

    private val phoneWidth = 1080
    private val phoneHeight = 2400
    private val stripRatio = 800f / 49_984f

    @Test
    fun normalPortraitPageIsASingleItem() {
        assertThat(ReaderTiles.tileCount(800f / 1200f, phoneWidth, phoneHeight)).isEqualTo(1)
    }

    @Test
    fun landscapeSpreadIsASingleItem() {
        assertThat(ReaderTiles.tileCount(2.4f, phoneWidth, phoneHeight)).isEqualTo(1)
    }

    @Test
    fun twoScreenScrollPageStaysUnsliced() {
        // An 800x2600 page displays ~3510px tall at phone width, under two viewports.
        assertThat(ReaderTiles.tileCount(800f / 2600f, phoneWidth, phoneHeight)).isEqualTo(1)
    }

    @Test
    fun singleStripEpisodeIsSlicedIntoViewportSizedPieces() {
        // 1080 / (800/49984) = 67478px displayed, so roughly one viewport per slice.
        assertThat(ReaderTiles.tileCount(stripRatio, phoneWidth, phoneHeight)).isEqualTo(29)
    }

    @Test
    fun unknownOrDegenerateRatiosAreNeverSliced() {
        assertThat(ReaderTiles.tileCount(null, phoneWidth, phoneHeight)).isEqualTo(1)
        assertThat(ReaderTiles.tileCount(0f, phoneWidth, phoneHeight)).isEqualTo(1)
        assertThat(ReaderTiles.tileCount(-1f, phoneWidth, phoneHeight)).isEqualTo(1)
        assertThat(ReaderTiles.tileCount(stripRatio, 0, phoneHeight)).isEqualTo(1)
        assertThat(ReaderTiles.tileCount(stripRatio, phoneWidth, 0)).isEqualTo(1)
    }

    @Test
    fun sliceCountIsCappedForPathologicalStrips() {
        assertThat(ReaderTiles.tileCount(800f / 500_000f, phoneWidth, phoneHeight))
            .isEqualTo(ReaderTiles.MAX_TILES)
    }

    @Test
    fun scrollItemsKeepConsecutiveSlicesOfOneImageTogether() {
        val ratios = mapOf(0 to 800f / 1200f, 1 to stripRatio, 2 to 800f / 1500f)
        val items = ReaderTiles.buildScrollItems(3, ratios, phoneWidth, phoneHeight)

        assertThat(items).hasSize(1 + 29 + 1)
        assertThat(items.first()).isEqualTo(ImageTile(0, 0, 1))
        assertThat(items[1]).isEqualTo(ImageTile(1, 0, 29))
        assertThat(items[29]).isEqualTo(ImageTile(1, 28, 29))
        assertThat(items.last()).isEqualTo(ImageTile(2, 0, 1))
    }

    @Test
    fun emptyImageListProducesNoItems() {
        assertThat(ReaderTiles.buildScrollItems(0, emptyMap(), phoneWidth, phoneHeight)).isEmpty()
    }

    @Test
    fun sliceAspectRatioScalesWithSliceCount() {
        val tile = ImageTile(0, 3, 29)
        assertThat(ReaderTiles.tileAspectRatio(stripRatio, tile)).isWithin(1e-6f).of(stripRatio * 29)
        assertThat(ReaderTiles.tileAspectRatio(stripRatio, ImageTile(0, 0, 1))).isWithin(1e-6f).of(stripRatio)
        assertThat(ReaderTiles.tileAspectRatio(null, tile)).isNull()
        assertThat(ReaderTiles.tileAspectRatio(0f, tile)).isNull()
    }

    @Test
    fun sliceFractionsCoverTheFullSourceHeight() {
        assertThat(ImageTile(0, 0, 4).topFraction).isEqualTo(0f)
        assertThat(ImageTile(0, 1, 4).topFraction).isEqualTo(0.25f)
        assertThat(ImageTile(0, 3, 4).bottomFraction).isEqualTo(1f)
    }

    @Test
    fun sourceRowsAreContiguousAndCoverTheImageExactly() {
        val height = 49_984
        val count = 29
        val ranges = (0 until count).map { ReaderTiles.sourceRowRange(it, count, height) }

        assertThat(ranges.first().first).isEqualTo(0)
        assertThat(ranges.last().last).isEqualTo(height - 1)
        for (i in 1 until ranges.size) {
            assertThat(ranges[i - 1].last + 1).isEqualTo(ranges[i].first)
            assertThat(ranges[i].count()).isAtLeast(1)
        }
        assertThat(ranges.sumOf { it.count() }).isEqualTo(height)
    }

    @Test
    fun singleSliceOwnsTheWholeSource() {
        assertThat(ReaderTiles.sourceRowRange(0, 1, 1200)).isEqualTo(0 until 1200)
    }

    @Test
    fun sliceCountLargerThanSourceNeverProducesEmptyRanges() {
        val ranges = (0 until 5).map { ReaderTiles.sourceRowRange(it, 5, 3) }
        assertThat(ranges.all { it.count() >= 1 }).isTrue()
        assertThat(ranges.first().first).isEqualTo(0)
        assertThat(ranges.last().last).isEqualTo(2)
    }

    @Test
    fun degenerateSourceHeightYieldsNoRows() {
        assertThat(ReaderTiles.sourceRowRange(0, 4, 0).isEmpty()).isTrue()
    }
}
