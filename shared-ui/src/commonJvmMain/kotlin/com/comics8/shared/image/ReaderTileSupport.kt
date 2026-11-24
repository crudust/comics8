package com.comics8.shared.image

import coil3.Extras
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import com.comics8.core.model.CropRect
import com.comics8.core.model.ImageHalf
import com.comics8.core.model.ImageTile
import java.util.concurrent.ConcurrentHashMap

const val READER_SHORT_EDGE_PX = 1800
const val READER_LONG_EDGE_PX = 16384
const val THUMBNAIL_MAX_EDGE_PX = 480

/** Marks a request as one vertical slice of [ImageTile] instead of the whole page image. */
val ReaderTileKey: Extras.Key<ImageTile?> = Extras.Key(null)

internal const val READER_TILE_IDENTITY_MARKER = "#tile/"

/**
 * Memory cache identity of a reader image slice. Slices of one image must not share a cache entry
 * because they hold different regions, while an unsliced image keeps its plain url identity.
 */
fun readerTileIdentity(url: String, tile: ImageTile?): String =
    if (tile == null || tile.tileCount <= 1) url else "$url$READER_TILE_IDENTITY_MARKER${tile.tileIndex}/${tile.tileCount}"

/** Prefix owned by [url]'s slices, used to evict every slice of one image at once. */
fun readerTileIdentityPrefix(url: String): String = "$url$READER_TILE_IDENTITY_MARKER"

/** Attaches the slice to render so platform tile decoders decode only that region. */
fun ImageRequest.Builder.readerTile(tile: ImageTile?): ImageRequest.Builder = apply {
    if (tile != null && tile.tileCount > 1) extras[ReaderTileKey] = tile
}

/**
 * Decode caps for reader images: nothing is upscaled, the short edge is bounded by the viewport
 * width (at least [READER_SHORT_EDGE_PX]) and the long edge by [READER_LONG_EDGE_PX].
 */
fun ImageRequest.Builder.readerImageSizing(
    maxBitmapSize: Size,
): ImageRequest.Builder =
    size(Size.ORIGINAL)
        .scale(Scale.FIT)
        .precision(Precision.EXACT)
        .maxBitmapSize(maxBitmapSize)

/** Caches computed split-mode content crop rectangles by `(url, half)`. */
object ReaderCropRectCache {
    private val cache = ConcurrentHashMap<Pair<String, ImageHalf>, CropRect>()

    fun get(url: String, half: ImageHalf): CropRect? =
        if (url.isBlank()) null else cache[url to half]

    fun put(url: String, half: ImageHalf, rect: CropRect) {
        if (url.isBlank()) return
        if (cache.size > 200) cache.clear()
        cache[url to half] = rect
    }

    fun clear() {
        cache.clear()
    }
}
