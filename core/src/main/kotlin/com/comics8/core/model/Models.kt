package com.comics8.core.model

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
)
