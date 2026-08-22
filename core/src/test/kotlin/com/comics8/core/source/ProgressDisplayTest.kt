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
}
