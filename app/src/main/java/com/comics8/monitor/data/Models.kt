package com.comics8.monitor.data

data class ToonItem(
    val id: String,
    val title: String,
    val thumbUrl: String,
    val href: String,
    val genre: String,
    val updatedAt: String?,
    val ranking: String? = null,
    val isNew: Boolean = false,
    val isFavorite: Boolean = false,
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
    DUAL("양쪽보기"),
}

enum class ReadDirection(val label: String) {
    RIGHT_TO_LEFT("RL (우좌)"),
    LEFT_TO_RIGHT("LR (좌우)"),
}
