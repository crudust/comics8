package com.comics8.core.model

import com.comics8.core.source.WorkId

data class ArtistRef(
    val slug: String,
    val displayName: String,
)

data class ToonItem(
    val id: String,
    val title: String,
    val thumbUrl: String,
    val href: String,
    val genre: String = "",
    val updatedAt: String? = null,
    val ranking: String? = null,
    val isNew: Boolean = false,
    val isFavorite: Boolean = false,
    val readProgress: String? = null,
    val sourceId: String = "eleven",
    val entryEpisodeId: String? = null,
    val artistChoices: List<ArtistRef> = emptyList(),
) {
    val subtitle: String
        get() = when {
            !updatedAt.isNullOrBlank() && genre.isNotBlank() -> "$updatedAt · $genre"
            !updatedAt.isNullOrBlank() -> updatedAt
            else -> genre
        }

    fun workId(): WorkId = WorkId(sourceId.ifBlank { WorkId.DEFAULT_SOURCE }, id)

    fun listingKey(): String {
        val episode = entryEpisodeId
        return if (!episode.isNullOrBlank()) {
            "${workId().sourceId}:g:$episode"
        } else {
            workId().storageKey()
        }
    }
}

data class ListingPage(
    val items: List<ToonItem>,
    val currentPage: Int,
    val lastPage: Int,
) {
    val hasNext: Boolean get() = currentPage < lastPage
    val hasPrev: Boolean get() = currentPage > 1
}

data class EpisodeItem(
    val wrId: String,
    val title: String,
    val date: String?,
    val thumbUrl: String?,
    val href: String,
    val isRead: Boolean = false,
    val readAt: Long? = null,
    val lastReadPage: Int = 0,
    val artistChoices: List<ArtistRef> = emptyList(),
)

data class EpisodePage(
    val items: List<EpisodeItem>,
    val currentPage: Int,
    val lastPage: Int,
)

enum class ViewMode(val label: String) {
    SCROLL("세로 스크롤"),
    PAGE("단면 페이지"),
    DUAL("양쪽보기");

    companion object {
        // Alias for SINGLE
        val SINGLE = PAGE
    }
}

enum class ReadDirection(val label: String) {
    RIGHT_TO_LEFT("RL (우좌)"),
    LEFT_TO_RIGHT("LR (좌우)"),
}

enum class PageTapZone(val label: String) {
    FOLLOW_DIRECTION("읽기 방향 연동"),
    RIGHT_NEXT("오른쪽 다음 / 왼쪽 이전"),
    LEFT_NEXT("왼쪽 다음 / 오른쪽 이전"),
}

enum class SplitMode(val label: String) {
    FIT("Fit"),
    SLICE("Slice"),
}

enum class ImageHalf {
    FULL,
    LEFT,
    RIGHT,
}

data class SinglePageSlice(
    val imageIndex: Int,
    val half: ImageHalf = ImageHalf.FULL,
)

data class CropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(1)
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

fun computeContentCropRect(
    regionLeft: Int,
    regionTop: Int,
    regionRight: Int,
    regionBottom: Int,
    maxCropFractionX: Float = 0.40f,
    maxCropFractionY: Float = 0.20f,
    whiteThreshold: Int = 225,
    whiteRatio: Float = 0.95f,
    getPixelRgb: (x: Int, y: Int) -> Int,
): CropRect {
    val regionW = regionRight - regionLeft
    val regionH = regionBottom - regionTop
    if (regionW <= 10 || regionH <= 10) {
        return CropRect(regionLeft, regionTop, regionRight, regionBottom)
    }

    val maxCropX = (regionW * maxCropFractionX).toInt()
    val maxCropY = (regionH * maxCropFractionY).toInt()

    fun isPixelWhite(rgb: Int): Boolean {
        val r = (rgb shr 16) and 0xff
        val g = (rgb shr 8) and 0xff
        val b = rgb and 0xff
        return r >= whiteThreshold && g >= whiteThreshold && b >= whiteThreshold
    }

    val sampleStepX = (regionW / 120).coerceIn(2, 6)
    val sampleStepY = (regionH / 120).coerceIn(2, 6)

    // Top crop
    var cropTop = regionTop
    val topLimit = (regionTop + maxCropY).coerceAtMost(regionBottom - 10)
    var y = regionTop
    while (y < topLimit) {
        var whiteCount = 0
        var totalCount = 0
        for (x in regionLeft until regionRight step sampleStepX) {
            totalCount++
            if (isPixelWhite(getPixelRgb(x, y))) {
                whiteCount++
            }
        }
        if (totalCount > 0 && whiteCount.toFloat() / totalCount >= whiteRatio) {
            cropTop = y
            y += sampleStepY
        } else {
            break
        }
    }

    // Bottom crop
    var cropBottom = regionBottom
    val bottomLimit = (regionBottom - maxCropY).coerceAtLeast(cropTop + 10)
    y = regionBottom - 1
    while (y >= bottomLimit) {
        var whiteCount = 0
        var totalCount = 0
        for (x in regionLeft until regionRight step sampleStepX) {
            totalCount++
            if (isPixelWhite(getPixelRgb(x, y))) {
                whiteCount++
            }
        }
        if (totalCount > 0 && whiteCount.toFloat() / totalCount >= whiteRatio) {
            cropBottom = y + 1
            y -= sampleStepY
        } else {
            break
        }
    }

    // Left crop
    var cropLeft = regionLeft
    val leftLimit = (regionLeft + maxCropX).coerceAtMost(regionRight - 10)
    var x = regionLeft
    while (x < leftLimit) {
        var whiteCount = 0
        var totalCount = 0
        for (sampleY in cropTop until cropBottom step sampleStepY) {
            totalCount++
            if (isPixelWhite(getPixelRgb(x, sampleY))) {
                whiteCount++
            }
        }
        if (totalCount > 0 && whiteCount.toFloat() / totalCount >= whiteRatio) {
            cropLeft = x
            x += sampleStepX
        } else {
            break
        }
    }

    // Right crop
    var cropRight = regionRight
    val rightLimit = (regionRight - maxCropX).coerceAtLeast(cropLeft + 10)
    x = regionRight - 1
    while (x >= rightLimit) {
        var whiteCount = 0
        var totalCount = 0
        for (sampleY in cropTop until cropBottom step sampleStepY) {
            totalCount++
            if (isPixelWhite(getPixelRgb(x, sampleY))) {
                whiteCount++
            }
        }
        if (totalCount > 0 && whiteCount.toFloat() / totalCount >= whiteRatio) {
            cropRight = x + 1
            x -= sampleStepX
        } else {
            break
        }
    }

    // Apply safety padding of 2px
    val finalLeft = (cropLeft - 2).coerceAtLeast(regionLeft)
    val finalTop = (cropTop - 2).coerceAtLeast(regionTop)
    val finalRight = (cropRight + 2).coerceAtMost(regionRight)
    val finalBottom = (cropBottom + 2).coerceAtMost(regionBottom)

    return CropRect(finalLeft, finalTop, finalRight, finalBottom)
}

fun buildSinglePageSlices(
    imageCount: Int,
    aspectRatios: Map<Int, Float>,
    splitMode: SplitMode,
    readDirection: ReadDirection,
): List<SinglePageSlice> {
    if (imageCount <= 0) return emptyList()
    if (splitMode == SplitMode.FIT) {
        return (0 until imageCount).map { SinglePageSlice(it, ImageHalf.FULL) }
    }
    val slices = mutableListOf<SinglePageSlice>()
    for (i in 0 until imageCount) {
        val ratio = aspectRatios[i]
        val isWide = ratio != null && ratio >= 1.0f
        if (isWide) {
            if (readDirection == ReadDirection.RIGHT_TO_LEFT) {
                slices.add(SinglePageSlice(i, ImageHalf.RIGHT))
                slices.add(SinglePageSlice(i, ImageHalf.LEFT))
            } else {
                slices.add(SinglePageSlice(i, ImageHalf.LEFT))
                slices.add(SinglePageSlice(i, ImageHalf.RIGHT))
            }
        } else {
            slices.add(SinglePageSlice(i, ImageHalf.FULL))
        }
    }
    return slices
}

sealed interface DualSpread {
    data class Single(val index: Int) : DualSpread
    data class Dual(val firstIndex: Int, val secondIndex: Int) : DualSpread
}

fun buildDualSpreads(
    imageCount: Int,
    aspectRatios: Map<Int, Float>,
): List<DualSpread> {
    if (imageCount <= 0) return emptyList()
    val spreads = mutableListOf<DualSpread>()
    var i = 0
    while (i < imageCount) {
        val ratio = aspectRatios[i]
        val isWide = ratio != null && ratio >= 1.0f

        if (isWide) {
            spreads.add(DualSpread.Single(i))
            i += 1
        } else {
            if (i + 1 < imageCount) {
                val nextRatio = aspectRatios[i + 1]
                val nextIsWide = nextRatio != null && nextRatio >= 1.0f
                if (nextIsWide) {
                    spreads.add(DualSpread.Single(i))
                    i += 1
                } else {
                    spreads.add(DualSpread.Dual(i, i + 1))
                    i += 2
                }
            } else {
                spreads.add(DualSpread.Single(i))
                i += 1
            }
        }
    }
    return spreads
}

data class DownloadedToonSummary(
    val toonId: String,
    val toonTitle: String,
    val toonThumbUrl: String,
    val toonHref: String,
    val episodeCount: Int,
    val totalBytes: Long,
    val latestDownloadedAt: Long,
    val sourceId: String = "eleven",
) {
    fun workId(): WorkId = WorkId(sourceId.ifBlank { WorkId.DEFAULT_SOURCE }, toonId)
}
