package com.comics8.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReaderDomainTest {
    @Test
    fun clampPageUsesOneBasedValidRange() {
        assertThat(ReaderDomain.clampPage(-3, 0)).isEqualTo(1)
        assertThat(ReaderDomain.clampPage(8, 5)).isEqualTo(5)
        assertThat(ReaderDomain.clampPage(3, 5)).isEqualTo(3)
    }

    @Test
    fun initialImagePageHandlesEmptyAndOutOfRangePositions() {
        assertThat(ReaderDomain.initialImagePage(4, 0)).isNull()
        assertThat(ReaderDomain.initialImagePage(-2, 10)).isEqualTo(0)
        assertThat(ReaderDomain.initialImagePage(20, 10)).isEqualTo(9)
    }

    @Test
    fun predictedEpisodePageUsesNewestFirstPagination() {
        assertThat(ReaderDomain.predictedEpisodePage(totalEpisodes = 250, targetReadOrder = 20)).isEqualTo(3)
        assertThat(ReaderDomain.predictedEpisodePage(totalEpisodes = 80, targetReadOrder = 20)).isEqualTo(1)
    }

    @Test
    fun resolveViewModeUsesDualOnlyForWideLandscapeScreens() {
        assertThat(ReaderDomain.resolveViewMode(ViewMode.PAGE, 800, 600)).isEqualTo(ViewMode.DUAL)
        assertThat(ReaderDomain.resolveViewMode(ViewMode.SCROLL, 599, 400)).isEqualTo(ViewMode.SCROLL)
        assertThat(ReaderDomain.resolveViewMode(ViewMode.PAGE, 800, 900)).isEqualTo(ViewMode.PAGE)
    }

    @Test
    fun episodePositionUsesNewestFirstOrderingAcrossPages() {
        val counted = EpisodeListingTotal(count = 205, lastPage = 3, pageSize = 100)
        val position = ReaderDomain.episodePosition(
            episodeIds = listOf("205", "204", "203"),
            currentEpisodeId = "204",
            currentPage = 2,
            lastPage = 3,
            knownTotal = counted,
        )

        assertThat(position.total).isEqualTo(counted)
        // 205 total episodes: page 1 holds 100 (205..106).
        // On page 2 (safeCurrentPage = 2), index 1 ("204") is preceded by index 0 ("205"),
        // so 101 newer episodes precede it -> readOrder is 205 - 101 = 104.
        assertThat(position.readOrder).isEqualTo(104)
        assertThat(position.nextEpisodeIndex).isEqualTo(0)
    }

    @Test
    fun episodePositionDerivesExactReadOrderWithMultiPageRemainder() {
        // "한밤중 하트튠": 122 total episodes across 2 pages (100 on page 1, 22 on page 2).
        val counted = EpisodeListingTotal(count = 122, lastPage = 2, pageSize = 100)
        val page1Episodes = (122 downTo 23).map { it.toString() }
        val position = ReaderDomain.episodePosition(
            episodeIds = page1Episodes,
            currentEpisodeId = "96", // index 26
            currentPage = 1,
            lastPage = 2,
            knownTotal = counted,
            pageSize = 100,
        )

        assertThat(position.total).isEqualTo(counted)
        assertThat(position.readOrder).isEqualTo(96)
        assertThat(position.nextEpisodeIndex).isEqualTo(25)
    }

    @Test
    fun episodePositionOnLastPageIsExactAndDerivesReadOrder() {
        // 205 total episodes, 3 pages, pageSize = 100 -> page 3 holds 5 episodes.
        val counted = EpisodeListingTotal(count = 205, lastPage = 3, pageSize = 100)
        val position = ReaderDomain.episodePosition(
            episodeIds = listOf("205", "204", "203", "202", "201"),
            currentEpisodeId = "204",
            currentPage = 3,
            lastPage = 3,
            knownTotal = counted,
        )

        assertThat(position.total).isEqualTo(counted)
        assertThat(position.readOrder).isEqualTo(4)
        assertThat(position.nextEpisodeIndex).isEqualTo(0)
    }

    @Test
    fun episodePositionNeverInventsATotalFromUnseenPages() {
        // Regression: page 1 of a 3-page listing used to report 300 episodes, which then froze.
        val position = ReaderDomain.episodePosition(
            episodeIds = listOf("201", "200", "199"),
            currentEpisodeId = "201",
            currentPage = 1,
            lastPage = 3,
            pageSize = 100,
        )
        assertThat(position.total).isNull()
        // Without a verified total, unseen older pages cannot be guessed, so order stays null.
        assertThat(position.readOrder).isNull()
    }

    @Test
    fun episodePositionKeepsTheStoredCountEvenWhenSmallerThanTheDerivedOrder() {
        // A shrunk listing keeps its counted total; the read order is clamped to it rather than
        // letting the badge show "203/199".
        val counted = EpisodeListingTotal(count = 199, lastPage = 2, pageSize = 100)
        val shrunk = ReaderDomain.episodePosition(
            episodeIds = listOf("3", "2", "1"),
            currentEpisodeId = "2",
            currentPage = 1,
            lastPage = 1,
            knownTotal = counted,
        )
        assertThat(shrunk.total).isEqualTo(counted)
        assertThat(shrunk.readOrder).isEqualTo(2)
    }

    @Test
    fun episodePositionHandlesUnknownEpisode() {
        val position = ReaderDomain.episodePosition(
            episodeIds = listOf("3", "2", "1"),
            currentEpisodeId = "missing",
            currentPage = 1,
            lastPage = 1,
            knownTotal = EpisodeListingTotal(count = 10, lastPage = 1, pageSize = 100),
        )

        assertThat(position.total).isEqualTo(EpisodeListingTotal(count = 10, lastPage = 1, pageSize = 100))
        assertThat(position.readOrder).isNull()
        assertThat(position.nextEpisodeIndex).isNull()
    }

    @Test
    fun episodeNavigationDefaultOrderChoosesLocalItemOrAdjacentPageEdge() {
        assertThat(ReaderDomain.nextEpisode(2, 4, 2, EpisodeSortOrder.DEFAULT))
            .isEqualTo(ReaderDomain.EpisodeNavigation.InCurrentPage(1))
        assertThat(ReaderDomain.nextEpisode(0, 4, 2, EpisodeSortOrder.DEFAULT))
            .isEqualTo(ReaderDomain.EpisodeNavigation.LoadPage(1, ReaderDomain.PageEdge.LAST))
        assertThat(ReaderDomain.prevEpisode(3, 4, 2, 3, EpisodeSortOrder.DEFAULT))
            .isEqualTo(ReaderDomain.EpisodeNavigation.LoadPage(3, ReaderDomain.PageEdge.FIRST))
        assertThat(ReaderDomain.prevEpisode(3, 4, 3, 3, EpisodeSortOrder.DEFAULT))
            .isEqualTo(ReaderDomain.EpisodeNavigation.None)
    }

    @Test
    fun episodeNavigationAscendingOrderAdvancesDownwards() {
        // In ascending order: index 0 is first/oldest (e.g. 1화), index 1 is 2화, index 2 is 3화
        assertThat(ReaderDomain.nextEpisode(0, 3, 1, EpisodeSortOrder.NAME_ASC))
            .isEqualTo(ReaderDomain.EpisodeNavigation.InCurrentPage(1))
        assertThat(ReaderDomain.nextEpisode(1, 3, 1, EpisodeSortOrder.NAME_ASC))
            .isEqualTo(ReaderDomain.EpisodeNavigation.InCurrentPage(2))
        assertThat(ReaderDomain.nextEpisode(2, 3, 1, EpisodeSortOrder.NAME_ASC))
            .isEqualTo(ReaderDomain.EpisodeNavigation.None)

        assertThat(ReaderDomain.prevEpisode(2, 3, 1, 1, EpisodeSortOrder.NAME_ASC))
            .isEqualTo(ReaderDomain.EpisodeNavigation.InCurrentPage(1))
        assertThat(ReaderDomain.prevEpisode(1, 3, 1, 1, EpisodeSortOrder.NAME_ASC))
            .isEqualTo(ReaderDomain.EpisodeNavigation.InCurrentPage(0))
        assertThat(ReaderDomain.prevEpisode(0, 3, 1, 1, EpisodeSortOrder.NAME_ASC))
            .isEqualTo(ReaderDomain.EpisodeNavigation.None)
    }

    @Test
    fun imageAspectRatioRejectsInvalidDimensions() {
        assertThat(ReaderDomain.imageAspectRatio(1600, 800)).isEqualTo(2f)
        assertThat(ReaderDomain.imageAspectRatio(0, 800)).isNull()
        assertThat(ReaderDomain.imageAspectRatio(800, 0)).isNull()
    }

    @Test
    fun isWebtoonStripDetectsExtremeVerticalRatio() {
        // Standard manga page (width 1000, height 1450) -> ratio = 1.45 < 2.0 -> false
        assertThat(ReaderDomain.isWebtoonStrip(1000, 1450)).isFalse()
        // Boundary case: exactly 2.0x -> true
        assertThat(ReaderDomain.isWebtoonStrip(1000, 2000)).isTrue()
        // Webtoon strip (width 800, height 5000) -> 6.25x -> true
        assertThat(ReaderDomain.isWebtoonStrip(800, 5000)).isTrue()
        // Invalid dimensions
        assertThat(ReaderDomain.isWebtoonStrip(0, 5000)).isFalse()
        assertThat(ReaderDomain.isWebtoonStrip(800, 0)).isFalse()
    }

    @Test
    fun isAutoScrollCandidateOnlyAllowsFirstTwoPages() {
        assertThat(ReaderDomain.isAutoScrollCandidate(0)).isTrue()
        assertThat(ReaderDomain.isAutoScrollCandidate(1)).isTrue()
        assertThat(ReaderDomain.isAutoScrollCandidate(2)).isFalse()
        assertThat(ReaderDomain.isAutoScrollCandidate(-1)).isFalse()
    }

    @Test
    fun episodePositionAndNavigationRemainConsistentWithAscendingSortOrder() {
        val rawEpisodes = listOf(
            EpisodeItem(wrId = "300", title = "3화", date = "2026-03-01", thumbUrl = null, href = "ep/3"),
            EpisodeItem(wrId = "200", title = "2화", date = "2026-02-01", thumbUrl = null, href = "ep/2"),
            EpisodeItem(wrId = "100", title = "1화", date = "2026-01-01", thumbUrl = null, href = "ep/1"),
        )
        val rawIds = rawEpisodes.map(EpisodeItem::wrId)

        val uiSortedEpisodes = rawEpisodes.sortedWithOrder(EpisodeSortOrder.NAME_ASC)
        assertThat(uiSortedEpisodes.map { it.title }).containsExactly("1화", "2화", "3화").inOrder()
        val sortedIds = uiSortedEpisodes.map(EpisodeItem::wrId)

        // Raw IDs are used for global position calculation across pages
        val pos1 = ReaderDomain.episodePosition(
            episodeIds = rawIds,
            currentEpisodeId = "100",
            currentPage = 1,
            lastPage = 1,
            knownTotal = EpisodeListingTotal(count = 3, lastPage = 1, pageSize = 100),
        )
        assertThat(pos1.readOrder).isEqualTo(1)
        assertThat(pos1.total).isEqualTo(EpisodeListingTotal(count = 3, lastPage = 1, pageSize = 100))
        assertThat(pos1.nextEpisodeIndex?.let { rawIds[it] }).isEqualTo("200")

        // UI sorted list navigation
        val navFrom1 = ReaderDomain.nextEpisode(
            currentIndex = 0,
            itemCount = sortedIds.size,
            currentPage = 1,
            sortOrder = EpisodeSortOrder.NAME_ASC,
        )
        assertThat(navFrom1).isEqualTo(ReaderDomain.EpisodeNavigation.InCurrentPage(1))
        assertThat(sortedIds[(navFrom1 as ReaderDomain.EpisodeNavigation.InCurrentPage).index]).isEqualTo("200")

        val navFrom2 = ReaderDomain.nextEpisode(
            currentIndex = 1,
            itemCount = sortedIds.size,
            currentPage = 1,
            sortOrder = EpisodeSortOrder.NAME_ASC,
        )
        assertThat(navFrom2).isEqualTo(ReaderDomain.EpisodeNavigation.InCurrentPage(2))
        assertThat(sortedIds[(navFrom2 as ReaderDomain.EpisodeNavigation.InCurrentPage).index]).isEqualTo("300")
    }

    @Test
    fun resolveBoundaryDecisionPagingAndConfirmation() {
        // Normal page advance within chapter
        assertThat(
            ReaderDomain.resolveBoundaryDecision(
                currentPage = 2,
                totalPages = 10,
                direction = ReaderDomain.BoundaryDirection.ADVANCE,
                isPromptActive = false,
            )
        ).isEqualTo(ReaderDomain.BoundaryDecision.PageTurn(3))

        // Normal page retreat within chapter
        assertThat(
            ReaderDomain.resolveBoundaryDecision(
                currentPage = 2,
                totalPages = 10,
                direction = ReaderDomain.BoundaryDirection.RETREAT,
                isPromptActive = false,
            )
        ).isEqualTo(ReaderDomain.BoundaryDecision.PageTurn(1))

        // Advance at last page: 1st swipe shows prompt
        assertThat(
            ReaderDomain.resolveBoundaryDecision(
                currentPage = 9,
                totalPages = 10,
                direction = ReaderDomain.BoundaryDirection.ADVANCE,
                isPromptActive = false,
            )
        ).isEqualTo(ReaderDomain.BoundaryDecision.ShowPrompt(ReaderDomain.PageEdge.LAST))

        // Advance at last page: 2nd swipe confirms navigation to next episode
        assertThat(
            ReaderDomain.resolveBoundaryDecision(
                currentPage = 9,
                totalPages = 10,
                direction = ReaderDomain.BoundaryDirection.ADVANCE,
                isPromptActive = true,
            )
        ).isEqualTo(ReaderDomain.BoundaryDecision.ConfirmNavigate(ReaderDomain.PageEdge.LAST))

        // Retreat at first page: 1st swipe shows prompt
        assertThat(
            ReaderDomain.resolveBoundaryDecision(
                currentPage = 0,
                totalPages = 10,
                direction = ReaderDomain.BoundaryDirection.RETREAT,
                isPromptActive = false,
            )
        ).isEqualTo(ReaderDomain.BoundaryDecision.ShowPrompt(ReaderDomain.PageEdge.FIRST))

        // Retreat at first page: 2nd swipe confirms navigation to prev episode
        assertThat(
            ReaderDomain.resolveBoundaryDecision(
                currentPage = 0,
                totalPages = 10,
                direction = ReaderDomain.BoundaryDirection.RETREAT,
                isPromptActive = true,
            )
        ).isEqualTo(ReaderDomain.BoundaryDecision.ConfirmNavigate(ReaderDomain.PageEdge.FIRST))
    }

    @Test
    fun resolveScrollDirectionContract() {
        // Below threshold returns null
        assertThat(
            ReaderDomain.resolveScrollDirection(deltaX = 0.5f, deltaY = 0.2f, isR2L = false, threshold = 0.8f)
        ).isNull()

        // LTR horizontal trackpad swipe: positive deltaX -> ADVANCE
        assertThat(
            ReaderDomain.resolveScrollDirection(deltaX = 1.2f, deltaY = 0.1f, isR2L = false, threshold = 0.8f)
        ).isEqualTo(ReaderDomain.BoundaryDirection.ADVANCE)

        // LTR horizontal trackpad swipe: negative deltaX -> RETREAT
        assertThat(
            ReaderDomain.resolveScrollDirection(deltaX = -1.2f, deltaY = 0.1f, isR2L = false, threshold = 0.8f)
        ).isEqualTo(ReaderDomain.BoundaryDirection.RETREAT)

        // RTL horizontal trackpad swipe: negative deltaX -> ADVANCE
        assertThat(
            ReaderDomain.resolveScrollDirection(deltaX = -1.2f, deltaY = 0.1f, isR2L = true, threshold = 0.8f)
        ).isEqualTo(ReaderDomain.BoundaryDirection.ADVANCE)

        // RTL horizontal trackpad swipe: positive deltaX -> RETREAT
        assertThat(
            ReaderDomain.resolveScrollDirection(deltaX = 1.2f, deltaY = 0.1f, isR2L = true, threshold = 0.8f)
        ).isEqualTo(ReaderDomain.BoundaryDirection.RETREAT)

        // Vertical mouse wheel / scroll down -> ADVANCE
        assertThat(
            ReaderDomain.resolveScrollDirection(deltaX = 0.1f, deltaY = 1.0f, isR2L = false, threshold = 0.8f)
        ).isEqualTo(ReaderDomain.BoundaryDirection.ADVANCE)

        // Vertical mouse wheel / scroll up -> RETREAT
        assertThat(
            ReaderDomain.resolveScrollDirection(deltaX = 0.1f, deltaY = -1.0f, isR2L = false, threshold = 0.8f)
        ).isEqualTo(ReaderDomain.BoundaryDirection.RETREAT)

        // Default threshold (1.0f) contract
        assertThat(
            ReaderDomain.resolveScrollDirection(deltaX = 0.9f, deltaY = 0.1f, isR2L = false)
        ).isNull()
        assertThat(
            ReaderDomain.resolveScrollDirection(deltaX = 1.1f, deltaY = 0.1f, isR2L = false)
        ).isEqualTo(ReaderDomain.BoundaryDirection.ADVANCE)
    }

    @Test
    fun isScrollIdleContract() {
        // Active motion above threshold (0.2f)
        assertThat(ReaderDomain.isScrollIdle(deltaX = 0.5f, deltaY = 0.0f)).isFalse()
        assertThat(ReaderDomain.isScrollIdle(deltaX = 0.0f, deltaY = 0.3f)).isFalse()
        assertThat(ReaderDomain.isScrollIdle(deltaX = -0.25f, deltaY = 0.0f)).isFalse()

        // Decayed motion below threshold (0.2f)
        assertThat(ReaderDomain.isScrollIdle(deltaX = 0.15f, deltaY = 0.1f)).isTrue()
        assertThat(ReaderDomain.isScrollIdle(deltaX = -0.1f, deltaY = 0.0f)).isTrue()
        assertThat(ReaderDomain.isScrollIdle(deltaX = 0.0f, deltaY = 0.0f)).isTrue()
    }

    @Test
    fun resolveScrollGestureAxisContract() {
        // Vertical-dominant stream locks to VERTICAL
        assertThat(ReaderDomain.resolveScrollGestureAxis(deltaX = 0.05f, deltaY = 0.8f))
            .isEqualTo(ReaderDomain.ScrollGestureAxis.VERTICAL)
        assertThat(ReaderDomain.resolveScrollGestureAxis(deltaX = 0.05f, deltaY = -0.8f))
            .isEqualTo(ReaderDomain.ScrollGestureAxis.VERTICAL)

        // Horizontal-dominant stream locks to HORIZONTAL
        assertThat(ReaderDomain.resolveScrollGestureAxis(deltaX = 0.8f, deltaY = 0.05f))
            .isEqualTo(ReaderDomain.ScrollGestureAxis.HORIZONTAL)

        // Diagonal / tiny deltas stay undecided so noise cannot flip the axis
        assertThat(ReaderDomain.resolveScrollGestureAxis(deltaX = 0.3f, deltaY = 0.3f))
            .isEqualTo(ReaderDomain.ScrollGestureAxis.UNDECIDED)
        assertThat(ReaderDomain.resolveScrollGestureAxis(deltaX = 0.0f, deltaY = 0.0f))
            .isEqualTo(ReaderDomain.ScrollGestureAxis.UNDECIDED)
    }

    @Test
    fun isPullRefreshGestureRequiresUpwardDragFromTop() {
        // Scroll down at the top edge must scroll the list, not refresh nor page-turn
        assertThat(
            ReaderDomain.isPullRefreshGesture(
                deltaX = 0.0f,
                deltaY = 0.6f,
                isStreamArmedAtTop = true,
                isAtTop = true,
            )
        ).isFalse()

        // Upward drag that started at the top edge arms the refresh
        assertThat(
            ReaderDomain.isPullRefreshGesture(
                deltaX = 0.0f,
                deltaY = -0.6f,
                isStreamArmedAtTop = true,
                isAtTop = true,
            )
        ).isTrue()

        // Stream not armed at the top edge (list was mid-scroll) stays scroll-only
        assertThat(
            ReaderDomain.isPullRefreshGesture(
                deltaX = 0.0f,
                deltaY = -0.6f,
                isStreamArmedAtTop = false,
                isAtTop = true,
            )
        ).isFalse()

        // Horizontal-dominant stream is never a pull refresh
        assertThat(
            ReaderDomain.isPullRefreshGesture(
                deltaX = 0.8f,
                deltaY = -0.3f,
                isStreamArmedAtTop = true,
                isAtTop = true,
            )
        ).isFalse()
    }
}
