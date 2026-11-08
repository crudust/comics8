package com.comics8.core.model

import kotlin.math.ceil

/**
 * One vertical slice of a page image in the continuous scroll reader.
 *
 * Sources frequently ship a whole episode as a single extremely tall image (for example
 * 800x50000). A bitmap of that size cannot be allocated or drawn on any platform, so the reader
 * lays such an image out as [tileCount] contiguous slices instead of one bitmap.
 *
 * Slices are expressed as fractions of the source height, so a plan never needs the source pixel
 * size: the aspect ratio drives layout and the source height is only required at decode time.
 */
data class ImageTile(
    val imageIndex: Int,
    val tileIndex: Int,
    val tileCount: Int,
) {
    val topFraction: Float get() = tileIndex.toFloat() / tileCount
    val bottomFraction: Float get() = (tileIndex + 1).toFloat() / tileCount
}

/**
 * Canonical tile plan shared by every reader platform: which slices to render for a page, how tall
 * each slice is laid out, and which source rows a slice owns.
 */
object ReaderTiles {
    /** Upper bound on slices per image so the scroll list stays finite for pathological strips. */
    const val MAX_TILES = 64

    /**
     * Images at least this many times taller than wide are sliced. Normal pages and two-screen
     * scroll pages stay a single item, so their scroll granularity is unchanged.
     */
    const val TILE_HEIGHT_RATIO_THRESHOLD = 7.0f

    /**
     * Slice count for one image, sized so a slice covers roughly one viewport height. Returns 1
     * (no slicing) for unknown ratios and for images that are not strips.
     */
    fun tileCount(
        aspectRatio: Float?,
        contentWidthPx: Int,
        viewportHeightPx: Int,
    ): Int {
        if (aspectRatio == null || aspectRatio <= 0f) return 1
        if (contentWidthPx <= 0 || viewportHeightPx <= 0) return 1
        if (1f / aspectRatio < TILE_HEIGHT_RATIO_THRESHOLD) return 1
        val displayedHeightPx = contentWidthPx / aspectRatio
        return ceil(displayedHeightPx / viewportHeightPx).toInt().coerceIn(2, MAX_TILES)
    }

    /** Ordered scroll items for [imageCount] images. Consecutive items of one image are contiguous. */
    fun buildScrollItems(
        imageCount: Int,
        aspectRatios: Map<Int, Float>,
        contentWidthPx: Int,
        viewportHeightPx: Int,
    ): List<ImageTile> {
        if (imageCount <= 0) return emptyList()
        val items = ArrayList<ImageTile>(imageCount)
        for (imageIndex in 0 until imageCount) {
            val count = tileCount(aspectRatios[imageIndex], contentWidthPx, viewportHeightPx)
            for (tileIndex in 0 until count) {
                items += ImageTile(imageIndex, tileIndex, count)
            }
        }
        return items
    }

    /**
     * Aspect ratio (width / height) of one slice. Every slice of an image uses the same ratio, so
     * adjacent slices stay seamlessly aligned regardless of how accurate the source ratio is.
     */
    fun tileAspectRatio(imageAspectRatio: Float?, tile: ImageTile): Float? {
        if (imageAspectRatio == null || imageAspectRatio <= 0f) return null
        return imageAspectRatio * tile.tileCount
    }

    /**
     * Source rows owned by one slice. Ranges are contiguous and cover the whole source height, with
     * the last slice absorbing the remainder so no row is dropped or drawn twice.
     */
    fun sourceRowRange(tileIndex: Int, tileCount: Int, sourceHeight: Int): IntRange {
        if (sourceHeight <= 0) return IntRange.EMPTY
        if (tileCount <= 1) return 0 until sourceHeight
        val count = tileCount.coerceIn(1, sourceHeight)
        val index = tileIndex.coerceIn(0, count - 1)
        val start = sourceHeight.toLong() * index / count
        val end = if (index == count - 1) {
            sourceHeight.toLong()
        } else {
            sourceHeight.toLong() * (index + 1) / count
        }
        return start.toInt() until end.toInt()
    }
}
