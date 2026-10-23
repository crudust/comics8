package com.comics8.core.source

import com.comics8.core.model.EpisodeTotal
import com.comics8.core.model.ProgressDisplayMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgressDisplayTest {
    private fun exact(count: Int) = EpisodeTotal.Exact(count)
    private fun atLeast(count: Int) = EpisodeTotal.AtLeast(count)

    @Test
    fun lastReadOrderIgnoresReadCount() {
        assertThat(ProgressDisplayMode.LATEST_EPISODE.format(52, exact(64), 3)).isEqualTo("52/64")
    }

    @Test
    fun readCountUsesOpenedEpisodes() {
        assertThat(ProgressDisplayMode.READ_COUNT.format(52, exact(64), 3)).isEqualTo("3/64")
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
        assertThat(registry.formatReadProgress(WorkId.DEFAULT_SOURCE, 52, exact(64), 3)).isEqualTo("52/64")
        assertThat(registry.formatReadProgress("hitomi", 52, exact(64), 3)).isEqualTo("3/64")
        assertThat(registry.formatReadProgress("unknown", 52, exact(64), 3)).isEqualTo("52/64")
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
            registry.formatReadProgress("network-smb", 5, exact(20), 3, ProgressDisplayMode.READ_COUNT),
        ).isEqualTo("3/20")
        assertThat(
            registry.formatReadProgress("network-smb", 5, exact(20), 3, ProgressDisplayMode.LATEST_EPISODE),
        ).isEqualTo("5/20")
        assertThat(
            registry.formatReadProgress("network-smb", 5, exact(20), 3, ProgressDisplayMode.PERCENTAGE),
        ).isEqualTo("25%")
        assertThat(
            registry.formatReadProgress("network-smb", 5, exact(20), 3, ProgressDisplayMode.HIDDEN),
        ).isEqualTo("")
    }

    @Test
    fun progressDisplayModeFormatsUnreadWhenOnlyTotalEpisodesKnown() {
        val registry = SourceRegistry(emptyList())
        assertThat(
            registry.formatReadProgress("eleven", 0, exact(104), 0, ProgressDisplayMode.LATEST_EPISODE),
        ).isEqualTo("104화")
        assertThat(
            registry.formatReadProgress("eleven", 0, exact(104), 0, ProgressDisplayMode.READ_COUNT),
        ).isEqualTo("104화")
        assertThat(
            registry.formatReadProgress("eleven", 0, exact(104), 0, ProgressDisplayMode.PERCENTAGE),
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
            registry.formatReadProgress("network-smb", 0, exact(20), 5, ProgressDisplayMode.PERCENTAGE),
        ).isEqualTo("25%")
    }

    @Test
    fun lowerBoundTotalIsMarkedAndNeverClaimsCompletion() {
        val registry = SourceRegistry(emptyList())
        // Only the first page of a 3-page listing was inspected: 100 episodes are known, not 300.
        assertThat(
            registry.formatReadProgress("eleven", 52, atLeast(100), 0, ProgressDisplayMode.LATEST_EPISODE),
        ).isEqualTo("52/100+")
        assertThat(
            registry.formatReadProgress("network-smb", 50, atLeast(100), 3, ProgressDisplayMode.READ_COUNT),
        ).isEqualTo("3/100+")
    }

    @Test
    fun lowerBoundTotalIsNotInflatedByReadOrderOrReadCount() {
        // Regression: the old implementation padded the denominator with maxOf(total, order, readCount),
        // which turned "read 199 of 199" into "200/200" and hid unfinished work.
        assertThat(ProgressDisplayMode.LATEST_EPISODE.format(55, exact(50), 0)).isEqualTo("55/50")
        assertThat(ProgressDisplayMode.READ_COUNT.format(0, atLeast(50), 55)).isEqualTo("55/50+")
    }

    @Test
    fun exactTotalMayCorrectDownwardToActualEpisodeCount() {
        // 199 real episodes must display 199, never a page-rounded 200.
        assertThat(ProgressDisplayMode.LATEST_EPISODE.format(199, exact(199), 199)).isEqualTo("199/199")
        assertThat(ProgressDisplayMode.PERCENTAGE.format(199, exact(199), 199)).isEqualTo("100%")
        assertThat(ProgressDisplayMode.PERCENTAGE.format(198, exact(199), 198)).isEqualTo("99%")
    }

    @Test
    fun requiresReadCountBySourceId() {
        val mode = ProgressDisplayMode.PERCENTAGE
        assertThat(mode.requiresReadCount("local")).isTrue()
        assertThat(mode.requiresReadCount("network-webdav")).isTrue()
        assertThat(mode.requiresReadCount("naver")).isFalse()
    }
}
