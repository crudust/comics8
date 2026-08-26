package com.comics8.core.source

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgressDisplayTest {
    @Test
    fun lastReadOrderIgnoresReadCount() {
        assertThat(ProgressDisplay.LAST_READ_ORDER.format(52, 64, 3)).isEqualTo("52/64")
    }

    @Test
    fun readCountUsesOpenedEpisodes() {
        assertThat(ProgressDisplay.READ_COUNT.format(52, 64, 3)).isEqualTo("3/64")
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
        assertThat(registry.formatReadProgress(WorkId.DEFAULT_SOURCE, 52, 64, 3)).isEqualTo("52/64")
        assertThat(registry.formatReadProgress("hitomi", 52, 64, 3)).isEqualTo("3/64")
        assertThat(registry.formatReadProgress("unknown", 52, 64, 3)).isEqualTo("52/64")
    }

    @Test
    fun progressDisplayModeDefaultForNetworkSourcesIsReadCount() {
        assertThat(com.comics8.core.model.ProgressDisplayMode.defaultFor("network-1234")).isEqualTo(com.comics8.core.model.ProgressDisplayMode.READ_COUNT)
        assertThat(com.comics8.core.model.ProgressDisplayMode.defaultFor("local")).isEqualTo(com.comics8.core.model.ProgressDisplayMode.READ_COUNT)
        assertThat(com.comics8.core.model.ProgressDisplayMode.defaultFor("hitomi")).isEqualTo(com.comics8.core.model.ProgressDisplayMode.READ_COUNT)
        assertThat(com.comics8.core.model.ProgressDisplayMode.defaultFor("eleven")).isEqualTo(com.comics8.core.model.ProgressDisplayMode.LATEST_EPISODE)
    }

    @Test
    fun registryFormatsWithExplicitProgressDisplayMode() {
        val registry = SourceRegistry(emptyList())
        assertThat(
            registry.formatReadProgress("network-smb", 5, 20, 3, com.comics8.core.model.ProgressDisplayMode.READ_COUNT),
        ).isEqualTo("3/20")
        assertThat(
            registry.formatReadProgress("network-smb", 5, 20, 3, com.comics8.core.model.ProgressDisplayMode.LATEST_EPISODE),
        ).isEqualTo("5/20")
        assertThat(
            registry.formatReadProgress("network-smb", 5, 20, 3, com.comics8.core.model.ProgressDisplayMode.PERCENTAGE),
        ).isEqualTo("25%")
        assertThat(
            registry.formatReadProgress("network-smb", 5, 20, 3, com.comics8.core.model.ProgressDisplayMode.HIDDEN),
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
            .isEqualTo(com.comics8.core.model.ProgressDisplayMode.LATEST_EPISODE)
        assertThat(registry.defaultProgressDisplayMode("storage_source"))
            .isEqualTo(com.comics8.core.model.ProgressDisplayMode.READ_COUNT)

        assertThat(registry.defaultProgressDisplayMode("storage_source").requiresReadCount).isTrue()
        assertThat(registry.defaultProgressDisplayMode("remote_webtoon").requiresReadCount).isFalse()
    }
}
