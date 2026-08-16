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
    val isUpdated: Boolean = false,
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
)

data class EpisodePage(
    val items: List<EpisodeItem>,
    val currentPage: Int,
    val lastPage: Int,
)
