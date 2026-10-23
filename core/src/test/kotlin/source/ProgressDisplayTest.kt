package com.comics8.core.source

import com.comics8.core.model.EpisodeListingTotal
import com.comics8.core.model.ProgressDisplayMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgressDisplayTest {
    private fun counted(count: Int) = EpisodeListingTotal(count = count, lastPage = 1, pageSize = 100)

    @Test
    fun lastReadOrderIgnoresReadCount() {
        assertThat(ProgressDisplayMode.LATEST_EPISODE.format(52, counted(64), 3)).isEqualTo("52/64")
    }

    @Test
    fun readCountUsesOpenedEpisodes() {
        assertThat(ProgressDisplayMode.READ_COUNT.format(52, counted(64), 3)).isEqualTo("3/64")
    }

    @Test
    fun registryFormatsUsingLoadedSourceDisplay() {
        val registry = SourceRegistry(
            listOf(
                StubComicSource(
                    id = WorkId.DEFAULT_SOURCE,
                    progressDisplay = ProgressDisplay.LAST_READ_ORDER,
                ),
                StubComicSource(
                    id = "hitomi",
                    progressDisplay = ProgressDisplay.READ_COUNT,
                ),
            ),
        )
        assertThat(registry.get(WorkId.DEFAULT_SOURCE).progressDisplay).isEqualTo(ProgressDisplay.LAST_READ_ORDER)
        assertThat(registry.get("hitomi").progressDisplay).isEqualTo(ProgressDisplay.READ_COUNT)
        assertThat(registry.formatReadProgress(WorkId.DEFAULT_SOURCE, 52, counted(64), 3)).isEqualTo("52/64")
        assertThat(registry.formatReadProgress("hitomi", 52, counted(64), 3)).isEqualTo("3/64")
        assertThat(registry.formatReadProgress("unknown", 52, counted(64), 3)).isEqualTo("52/64")
    }

    @Test
    fun progressDisplayModeDefaultForNetworkSourcesIsReadCount() {
        assertThat(ProgressDisplayMode.defaultFor("network-1234")).isEqualTo(ProgressDisplayMode.READ_COUNT)
        assertThat(ProgressDisplayMode.defaultFor("local")).isEqualTo(ProgressDisplayMode.READ_COUNT)
        assertThat(ProgressDisplayMode.defaultFor("hitomi")).isEqualTo(ProgressDisplayMode.LATEST_EPISODE)
        assertThat(ProgressDisplayMode.defaultFor("eleven")).isEqualTo(ProgressDisplayMode.LATEST_EPISODE)
    }

    @Test
    fun registryFormatsWithExplicitProgressDisplayMode() {
        val registry = SourceRegistry(emptyList())
        assertThat(
            registry.formatReadProgress("network-smb", 5, counted(20), 3, ProgressDisplayMode.READ_COUNT),
        ).isEqualTo("3/20")
        assertThat(
            registry.formatReadProgress("network-smb", 5, counted(20), 3, ProgressDisplayMode.LATEST_EPISODE),
        ).isEqualTo("5/20")
        assertThat(
            registry.formatReadProgress("network-smb", 5, counted(20), 3, ProgressDisplayMode.PERCENTAGE),
        ).isEqualTo("25%")
        assertThat(
            registry.formatReadProgress("network-smb", 5, counted(20), 3, ProgressDisplayMode.HIDDEN),
        ).isEqualTo("")
    }

    @Test
    fun progressDisplayModeFormatsUnreadWhenOnlyTotalEpisodesKnown() {
        val registry = SourceRegistry(emptyList())
        assertThat(
            registry.formatReadProgress("eleven", 0, counted(104), 0, ProgressDisplayMode.LATEST_EPISODE),
        ).isEqualTo("104화")
        assertThat(
            registry.formatReadProgress("eleven", 0, counted(104), 0, ProgressDisplayMode.READ_COUNT),
        ).isEqualTo("104화")
        assertThat(
            registry.formatReadProgress("eleven", 0, counted(104), 0, ProgressDisplayMode.PERCENTAGE),
        ).isEqualTo("")
    }

    @Test
    fun sourceContractProvidesDefaultProgressDisplayMode() {
        val registry = SourceRegistry(
            listOf(
                StubComicSource(
                    id = "remote_webtoon",
                    progressDisplay = ProgressDisplay.LAST_READ_ORDER,
                ),
                StubComicSource(
                    id = "storage_source",
                    progressDisplay = ProgressDisplay.READ_COUNT,
                ),
            ),
        )
        assertThat(registry.defaultProgressDisplayMode("remote_webtoon"))
            .isEqualTo(ProgressDisplayMode.LATEST_EPISODE)
        assertThat(registry.defaultProgressDisplayMode("storage_source"))
            .isEqualTo(ProgressDisplayMode.READ_COUNT)

        assertThat(registry.defaultProgressDisplayMode("storage_source").requiresReadCount).isTrue()
        assertThat(registry.defaultProgressDisplayMode("remote_webtoon").requiresReadCount).isFalse()
    }

    @Test
    fun percentageModeUsesReadCountWhenLastReadOrderZero() {
        val registry = SourceRegistry(emptyList())
        // Storage source: lastReadOrder = 0, readCount = 5, total = 20 -> 25%
        assertThat(
            registry.formatReadProgress("network-smb", 0, counted(20), 5, ProgressDisplayMode.PERCENTAGE),
        ).isEqualTo("25%")
    }

    @Test
    fun uncountedTotalRendersNoBadge() {
        val registry = SourceRegistry(emptyList())
        // No page sweep has happened yet, so nothing at all is shown instead of a partial count.
        assertThat(registry.formatReadProgress("eleven", 52, null, 0, ProgressDisplayMode.LATEST_EPISODE))
            .isEqualTo("")
        assertThat(registry.formatReadProgress("network-smb", 50, null, 3, ProgressDisplayMode.READ_COUNT))
            .isEqualTo("")
        assertThat(registry.formatReadProgress("network-smb", 50, null, 3, ProgressDisplayMode.PERCENTAGE))
            .isEqualTo("")
    }

    @Test
    fun denominatorIsNeverInflatedByReadOrderOrReadCount() {
        // Regression: the old implementation padded the denominator with maxOf(total, order, readCount),
        // which turned "read 199 of 199" into "200/200" and hid unfinished work.
        assertThat(ProgressDisplayMode.LATEST_EPISODE.format(55, counted(50), 0)).isEqualTo("55/50")
        assertThat(ProgressDisplayMode.READ_COUNT.format(0, counted(50), 55)).isEqualTo("55/50")
    }

    @Test
    fun exactTotalMayCorrectDownwardToActualEpisodeCount() {
        // 199 real episodes must display 199, never a page-rounded 200.
        assertThat(ProgressDisplayMode.LATEST_EPISODE.format(199, counted(199), 199)).isEqualTo("199/199")
        assertThat(ProgressDisplayMode.PERCENTAGE.format(199, counted(199), 199)).isEqualTo("100%")
        assertThat(ProgressDisplayMode.PERCENTAGE.format(198, counted(199), 198)).isEqualTo("99%")
    }

    @Test
    fun requiresReadCountBySourceId() {
        val mode = ProgressDisplayMode.PERCENTAGE
        assertThat(mode.requiresReadCount("local")).isTrue()
        assertThat(mode.requiresReadCount("network-webdav")).isTrue()
        assertThat(mode.requiresReadCount("naver")).isFalse()
    }
}
