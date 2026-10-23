package com.comics8.core.model

/** Pure reader decisions shared by the Android and desktop presentation layers. */
object ReaderDomain {
    private const val DEFAULT_EPISODE_PAGE_SIZE = 100
    private const val DEFAULT_DUAL_MIN_WIDTH_DP = 600

    data class EpisodePosition(
        val total: EpisodeTotal,
        val readOrder: Int?,
        val nextEpisodeIndex: Int?,
    ) {
        val totalEpisodes: Int get() = total.count
    }

    enum class PageEdge {
        FIRST,
        LAST,
    }

    enum class BoundaryDirection {
        ADVANCE,
        RETREAT,
    }

    sealed interface BoundaryDecision {
        data class PageTurn(val targetPage: Int) : BoundaryDecision
        data class ShowPrompt(val edge: PageEdge) : BoundaryDecision
        data class ConfirmNavigate(val edge: PageEdge) : BoundaryDecision
    }

    fun resolveBoundaryDecision(
        currentPage: Int,
        totalPages: Int,
        direction: BoundaryDirection,
        isPromptActive: Boolean,
    ): BoundaryDecision {
        val safeTotal = totalPages.coerceAtLeast(1)
        val safeCurrent = currentPage.coerceIn(0, safeTotal - 1)

        return when (direction) {
            BoundaryDirection.ADVANCE -> {
                if (safeCurrent < safeTotal - 1) {
                    BoundaryDecision.PageTurn(safeCurrent + 1)
                } else {
                    if (isPromptActive) {
                        BoundaryDecision.ConfirmNavigate(PageEdge.LAST)
                    } else {
                        BoundaryDecision.ShowPrompt(PageEdge.LAST)
                    }
                }
            }
            BoundaryDirection.RETREAT -> {
                if (safeCurrent > 0) {
                    BoundaryDecision.PageTurn(safeCurrent - 1)
                } else {
                    if (isPromptActive) {
                        BoundaryDecision.ConfirmNavigate(PageEdge.FIRST)
                    } else {
                        BoundaryDecision.ShowPrompt(PageEdge.FIRST)
                    }
                }
            }
        }
    }

    const val SCROLL_PAGE_THRESHOLD = 1.0f
    const val SCROLL_IDLE_THRESHOLD = 0.2f
    const val SCROLL_LATCH_COOLDOWN_MS = 350L

    /** Dominant axis of a single physical scroll/trackpad gesture stream. */
    enum class ScrollGestureAxis {
        HORIZONTAL,
        VERTICAL,
        UNDECIDED,
    }

    fun isScrollIdle(deltaX: Float, deltaY: Float, threshold: Float = SCROLL_IDLE_THRESHOLD): Boolean =
        kotlin.math.abs(deltaX) < threshold && kotlin.math.abs(deltaY) < threshold

    /**
     * Locks the axis of a gesture stream once one delta clearly dominates the other.
     * Ambiguous (diagonal) deltas stay [ScrollGestureAxis.UNDECIDED] so a single noisy event
     * cannot misclassify the whole gesture.
     */
    fun resolveScrollGestureAxis(
        deltaX: Float,
        deltaY: Float,
        threshold: Float = SCROLL_IDLE_THRESHOLD,
    ): ScrollGestureAxis {
        val absX = kotlin.math.abs(deltaX)
        val absY = kotlin.math.abs(deltaY)
        if (maxOf(absX, absY) < threshold) return ScrollGestureAxis.UNDECIDED
        return when {
            absY - absX > threshold -> ScrollGestureAxis.VERTICAL
            absX - absY > threshold -> ScrollGestureAxis.HORIZONTAL
            else -> ScrollGestureAxis.UNDECIDED
        }
    }

    /**
     * Pull-to-refresh is only an upward drag (negative deltaY) that starts while already at the
     * top edge. Scroll-down at the top edge must scroll the list, never navigate or refresh.
     */
    fun isPullRefreshGesture(
        deltaX: Float,
        deltaY: Float,
        isStreamArmedAtTop: Boolean,
        isAtTop: Boolean,
    ): Boolean = isStreamArmedAtTop && isAtTop && deltaY < 0f && kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX)

    fun resolveScrollDirection(
        deltaX: Float,
        deltaY: Float,
        isR2L: Boolean,
        threshold: Float = SCROLL_PAGE_THRESHOLD,
    ): BoundaryDirection? {
        val absX = kotlin.math.abs(deltaX)
        val absY = kotlin.math.abs(deltaY)
        if (absX < threshold && absY < threshold) return null

        return if (absX >= absY) {
            val isForward = if (isR2L) deltaX < 0f else deltaX > 0f
            if (isForward) BoundaryDirection.ADVANCE else BoundaryDirection.RETREAT
        } else {
            if (deltaY > 0f) BoundaryDirection.ADVANCE else BoundaryDirection.RETREAT
        }
    }

    sealed interface EpisodeNavigation {
        data class InCurrentPage(val index: Int) : EpisodeNavigation
        data class LoadPage(val page: Int, val edge: PageEdge) : EpisodeNavigation
        data object None : EpisodeNavigation
    }

    fun clampPage(page: Int, lastPage: Int): Int =
        page.coerceIn(1, lastPage.coerceAtLeast(1))

    fun initialImagePage(lastReadPage: Int, totalImages: Int): Int? =
        if (totalImages <= 0) null else ReaderProgress.startPageOnOpen(lastReadPage).coerceIn(0, totalImages - 1)

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

    const val AUTO_SCROLL_HEIGHT_RATIO_THRESHOLD = 2.0f
    const val AUTO_SCROLL_MAX_CHECK_PAGES = 2

    fun isAutoScrollCandidate(pageIndex: Int): Boolean =
        pageIndex in 0 until AUTO_SCROLL_MAX_CHECK_PAGES

    fun isWebtoonStrip(
        width: Int,
        height: Int,
        threshold: Float = AUTO_SCROLL_HEIGHT_RATIO_THRESHOLD,
    ): Boolean = width > 0 && height > 0 && (height.toFloat() / width.toFloat()) >= threshold

    /**
     * Resolves the read order for an episode list ordered newest-to-oldest, as returned by sources.
     */
    fun episodePosition(
        episodeIds: List<String>,
        currentEpisodeId: String,
        currentPage: Int,
        lastPage: Int,
        knownTotal: EpisodeTotal? = null,
        pageSize: Int = DEFAULT_EPISODE_PAGE_SIZE,
    ): EpisodePosition {
        val safeLastPage = lastPage.coerceAtLeast(1)
        val safeCurrentPage = clampPage(currentPage, safeLastPage)
        val itemCount = episodeIds.size
        val currentIndex = episodeIds.indexOf(currentEpisodeId)
        val safePageSize = pageSize.coerceAtLeast(1)

        val observed = when {
            itemCount <= 0 -> EpisodeTotal.Unknown
            safeCurrentPage == safeLastPage -> EpisodeTotal.Exact((safeLastPage - 1) * safePageSize + itemCount)
            else -> EpisodeTotal.AtLeast((safeCurrentPage - 1) * safePageSize + itemCount)
        }
        val total = EpisodeTotal.merge(knownTotal, observed)

        val readOrder = if (currentIndex >= 0 && itemCount > 0) {
            // Unseen pages below the current one may hold up to pageSize items each.
            val unseenOlder = (safeLastPage - safeCurrentPage).coerceAtLeast(0) * safePageSize
            val onPageOrder = (itemCount - currentIndex).coerceAtLeast(1)
            val order = (unseenOlder + onPageOrder).coerceAtLeast(1)
            // Only an exact total may bound the order; a lower bound truncates real progress.
            if (total.isExact) order.coerceAtMost(total.count) else order
        } else {
            null
        }

        return EpisodePosition(
            total = total,
            readOrder = readOrder,
            nextEpisodeIndex = (currentIndex - 1).takeIf { currentIndex in 1 until itemCount },
        )
    }

    fun nextEpisode(
        currentIndex: Int,
        itemCount: Int,
        currentPage: Int,
        sortOrder: EpisodeSortOrder = EpisodeSortOrder.DEFAULT,
    ): EpisodeNavigation {
        if (currentIndex < 0 || itemCount <= 0) return EpisodeNavigation.None
        return if (sortOrder.isAscending) {
            when {
                currentIndex in 0 until (itemCount - 1) -> EpisodeNavigation.InCurrentPage(currentIndex + 1)
                else -> EpisodeNavigation.None
            }
        } else {
            when {
                currentIndex in 1 until itemCount -> EpisodeNavigation.InCurrentPage(currentIndex - 1)
                currentIndex == 0 && currentPage > 1 ->
                    EpisodeNavigation.LoadPage(currentPage - 1, PageEdge.LAST)
                else -> EpisodeNavigation.None
            }
        }
    }

    fun prevEpisode(
        currentIndex: Int,
        itemCount: Int,
        currentPage: Int,
        lastPage: Int,
        sortOrder: EpisodeSortOrder = EpisodeSortOrder.DEFAULT,
    ): EpisodeNavigation {
        if (currentIndex < 0 || itemCount <= 0) return EpisodeNavigation.None
        return if (sortOrder.isAscending) {
            when {
                currentIndex in 1 until itemCount -> EpisodeNavigation.InCurrentPage(currentIndex - 1)
                else -> EpisodeNavigation.None
            }
        } else {
            when {
                currentIndex in 0 until (itemCount - 1) -> EpisodeNavigation.InCurrentPage(currentIndex + 1)
                currentIndex == itemCount - 1 && currentPage < lastPage ->
                    EpisodeNavigation.LoadPage(currentPage + 1, PageEdge.FIRST)
                else -> EpisodeNavigation.None
            }
        }
    }
}
