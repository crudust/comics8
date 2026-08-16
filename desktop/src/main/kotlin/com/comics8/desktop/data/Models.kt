package com.comics8.desktop.data

enum class ViewMode {
    SCROLL,
    SINGLE,
    DUAL,
}

enum class ReadDirection {
    RIGHT_TO_LEFT,
    LEFT_TO_RIGHT,
}

data class ToonItem(
    val id: String,
    val title: String,
    val thumbUrl: String,
    val href: String,
    val genre: String = "",
    val updatedAt: String? = null,
    val subtitle: String = "",
    val isNew: Boolean = false,
    val isFavorite: Boolean = false,
    val readProgress: String? = null,
)

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

data class ListingPage(
    val items: List<ToonItem>,
    val currentPage: Int,
    val lastPage: Int,
)

data class EpisodePage(
    val items: List<EpisodeItem>,
    val currentPage: Int,
    val lastPage: Int,
)

data class FavoriteRecord(
    val id: String,
    val title: String,
    val thumbUrl: String,
    val href: String,
    val genre: String,
    val updatedAt: String?,
    val savedAt: Long,
)

data class SeenRecord(
    val id: String,
    val title: String,
    val updatedAt: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val notifiedKey: String,
)

data class ReadEpisodeRecord(
    val toonId: String,
    val wrId: String,
    val readAt: Long,
    val lastPage: Int = 0,
)

data class ReadHistoryRecord(
    val toonId: String,
    val toonTitle: String,
    val toonThumbUrl: String,
    val toonHref: String,
    val lastWrId: String,
    val lastEpisodeTitle: String,
    val lastEpisodeHref: String,
    val lastReadOrder: Int,
    val totalEpisodes: Int,
    val lastReadAt: Long,
    val nextWrId: String? = null,
    val nextEpisodeTitle: String? = null,
    val nextEpisodeHref: String? = null,
    val hasNew: Boolean = false,
)

data class ReaderSettingRecord(
    val toonId: String,
    val viewMode: String,
    val readDirection: String,
    val updatedAt: Long,
)

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
