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
        val position = ReaderDomain.episodePosition(
            episodeIds = listOf("205", "204", "203"),
            currentEpisodeId = "204",
            currentPage = 2,
            lastPage = 3,
            knownLastPageCount = 5,
            knownTotalCount = null,
        )

        assertThat(position.totalEpisodes).isEqualTo(205)
        assertThat(position.readOrder).isEqualTo(7)
        assertThat(position.nextEpisodeIndex).isEqualTo(0)
    }

    @Test
    fun episodePositionPrefersKnownTotalAndHandlesUnknownEpisode() {
        val position = ReaderDomain.episodePosition(
            episodeIds = listOf("3", "2", "1"),
            currentEpisodeId = "missing",
            currentPage = 1,
            lastPage = 1,
            knownLastPageCount = null,
            knownTotalCount = 10,
        )

        assertThat(position.totalEpisodes).isEqualTo(10)
        assertThat(position.readOrder).isNull()
        assertThat(position.nextEpisodeIndex).isNull()
    }

    @Test
    fun episodeNavigationChoosesLocalItemOrAdjacentPageEdge() {
        assertThat(ReaderDomain.newerEpisode(2, 4, 2))
            .isEqualTo(ReaderDomain.EpisodeNavigation.InCurrentPage(1))
        assertThat(ReaderDomain.newerEpisode(0, 4, 2))
            .isEqualTo(ReaderDomain.EpisodeNavigation.LoadPage(1, ReaderDomain.PageEdge.LAST))
        assertThat(ReaderDomain.olderEpisode(3, 4, 2, 3))
            .isEqualTo(ReaderDomain.EpisodeNavigation.LoadPage(3, ReaderDomain.PageEdge.FIRST))
        assertThat(ReaderDomain.olderEpisode(3, 4, 3, 3))
            .isEqualTo(ReaderDomain.EpisodeNavigation.None)
    }

    @Test
    fun imageAspectRatioRejectsInvalidDimensions() {
        assertThat(ReaderDomain.imageAspectRatio(1600, 800)).isEqualTo(2f)
        assertThat(ReaderDomain.imageAspectRatio(0, 800)).isNull()
        assertThat(ReaderDomain.imageAspectRatio(800, 0)).isNull()
    }

    @Test
    fun episodePositionAndNavigationRemainConsistentRegardlessOfUiSortOrder() {
        val rawEpisodes = listOf(
            EpisodeItem(wrId = "300", title = "3화", date = "2026-03-01", thumbUrl = null, href = "ep/3"),
            EpisodeItem(wrId = "200", title = "2화", date = "2026-02-01", thumbUrl = null, href = "ep/2"),
            EpisodeItem(wrId = "100", title = "1화", date = "2026-01-01", thumbUrl = null, href = "ep/1"),
        )
        val rawIds = rawEpisodes.map(EpisodeItem::wrId)

        val uiSortedEpisodes = rawEpisodes.sortedWithOrder(EpisodeSortOrder.NAME_ASC)
        assertThat(uiSortedEpisodes.map { it.title }).containsExactly("1화", "2화", "3화").inOrder()

        val pos1 = ReaderDomain.episodePosition(
            episodeIds = rawIds,
            currentEpisodeId = "100",
            currentPage = 1,
            lastPage = 1,
            knownLastPageCount = 3,
            knownTotalCount = 3,
        )
        assertThat(pos1.readOrder).isEqualTo(1)
        assertThat(pos1.totalEpisodes).isEqualTo(3)
        assertThat(pos1.nextEpisodeIndex?.let { rawIds[it] }).isEqualTo("200")

        val pos3 = ReaderDomain.episodePosition(
            episodeIds = rawIds,
            currentEpisodeId = "300",
            currentPage = 1,
            lastPage = 1,
            knownLastPageCount = 3,
            knownTotalCount = 3,
        )
        assertThat(pos3.readOrder).isEqualTo(3)
        assertThat(pos3.totalEpisodes).isEqualTo(3)
        assertThat(pos3.nextEpisodeIndex).isNull()

        val navFrom1 = ReaderDomain.newerEpisode(currentIndex = 2, itemCount = 3, currentPage = 1)
        assertThat(navFrom1).isEqualTo(ReaderDomain.EpisodeNavigation.InCurrentPage(1))
        assertThat(rawIds[(navFrom1 as ReaderDomain.EpisodeNavigation.InCurrentPage).index]).isEqualTo("200")
    }
}
