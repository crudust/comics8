package com.comics8.core.model

/** Pure reader decisions shared by the Android and desktop presentation layers. */
object ReaderDomain {
    private const val DEFAULT_EPISODE_PAGE_SIZE = 100
    private const val DEFAULT_DUAL_MIN_WIDTH_DP = 600

    data class EpisodePosition(
        val totalEpisodes: Int,
        val readOrder: Int?,
        val nextEpisodeIndex: Int?,
    )

    enum class PageEdge {
        FIRST,
        LAST,
    }

    sealed interface EpisodeNavigation {
        data class InCurrentPage(val index: Int) : EpisodeNavigation
        data class LoadPage(val page: Int, val edge: PageEdge) : EpisodeNavigation
        data object None : EpisodeNavigation
    }

    fun clampPage(page: Int, lastPage: Int): Int =
        page.coerceIn(1, lastPage.coerceAtLeast(1))

    fun initialImagePage(lastReadPage: Int, totalImages: Int): Int? =
        if (totalImages <= 0) null else lastReadPage.coerceIn(0, totalImages - 1)

    fun predictedEpisodePage(
        totalEpisodes: Int,
        targetReadOrder: Int,
        pageSize: Int = DEFAULT_EPISODE_PAGE_SIZE,
    ): Int = if (totalEpisodes > targetReadOrder && totalEpisodes > pageSize) {
        ((totalEpisodes - targetReadOrder) / pageSize + 1).coerceAtLeast(1)
    } else {
        1
    }

    fun resolveViewMode(
        singlePagePreference: ViewMode,
        widthDp: Int,
        heightDp: Int,
        dualMinWidthDp: Int = DEFAULT_DUAL_MIN_WIDTH_DP,
    ): ViewMode =
        if (widthDp >= dualMinWidthDp && widthDp > heightDp) ViewMode.DUAL else singlePagePreference

    fun imageAspectRatio(width: Int, height: Int): Float? =
        if (width <= 0 || height <= 0) null else width.toFloat() / height.toFloat()

    /**
     * Resolves the read order for an episode list ordered newest-to-oldest, as returned by sources.
     */
    fun episodePosition(
        episodeIds: List<String>,
        currentEpisodeId: String,
        currentPage: Int,
        lastPage: Int,
        knownLastPageCount: Int?,
        knownTotalCount: Int?,
        pageSize: Int = DEFAULT_EPISODE_PAGE_SIZE,
    ): EpisodePosition {
        val safeLastPage = lastPage.coerceAtLeast(1)
        val safeCurrentPage = clampPage(currentPage, safeLastPage)
        val itemCount = episodeIds.size
        val currentIndex = episodeIds.indexOf(currentEpisodeId)

        val totalEpisodes = when {
            knownTotalCount != null && knownTotalCount > 0 -> knownTotalCount
            safeLastPage <= 1 -> itemCount.coerceAtLeast(1)
            safeCurrentPage == safeLastPage && itemCount > 0 ->
                (safeLastPage - 1) * pageSize + itemCount
            knownLastPageCount != null -> (safeLastPage - 1) * pageSize + knownLastPageCount
            else -> (safeLastPage - 1) * pageSize + itemCount
        }.coerceAtLeast(1)

        val readOrder = if (currentIndex >= 0 && itemCount > 0) {
            val olderCount = if (safeCurrentPage == safeLastPage) {
                0
            } else {
                val lastPageItems = knownLastPageCount ?: pageSize
                val middlePagesCount = (safeLastPage - 1 - safeCurrentPage).coerceAtLeast(0) * pageSize
                lastPageItems + middlePagesCount
            }
            val onPageOrder = (itemCount - currentIndex).coerceAtLeast(1)
            (olderCount + onPageOrder).coerceIn(1, totalEpisodes)
        } else {
            null
        }

        return EpisodePosition(
            totalEpisodes = totalEpisodes,
            readOrder = readOrder,
            nextEpisodeIndex = (currentIndex - 1).takeIf { currentIndex in 1 until itemCount },
        )
    }

    fun newerEpisode(currentIndex: Int, itemCount: Int, currentPage: Int): EpisodeNavigation =
        when {
            currentIndex in 1 until itemCount -> EpisodeNavigation.InCurrentPage(currentIndex - 1)
            currentIndex == 0 && currentPage > 1 ->
                EpisodeNavigation.LoadPage(currentPage - 1, PageEdge.LAST)
            else -> EpisodeNavigation.None
        }

    fun olderEpisode(
        currentIndex: Int,
        itemCount: Int,
        currentPage: Int,
        lastPage: Int,
    ): EpisodeNavigation = when {
        currentIndex in 0 until (itemCount - 1) -> EpisodeNavigation.InCurrentPage(currentIndex + 1)
        currentIndex == itemCount - 1 && currentPage < lastPage ->
            EpisodeNavigation.LoadPage(currentPage + 1, PageEdge.FIRST)
        else -> EpisodeNavigation.None
    }
}
