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
}
