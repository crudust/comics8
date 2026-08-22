package com.comics8.core.source

import com.comics8.core.source.hitomi.HitomiSource
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
    fun hitomiUsesReadCountAndElevenKeepsOrder() {
        val registry = SourceRegistry.forTests()
        assertThat(registry.get(WorkId.DEFAULT_SOURCE).progressDisplay).isEqualTo(ProgressDisplay.LAST_READ_ORDER)
        assertThat(registry.get(HitomiSource.ID).progressDisplay).isEqualTo(ProgressDisplay.READ_COUNT)
        assertThat(registry.formatReadProgress(WorkId.DEFAULT_SOURCE, 52, 64, 3)).isEqualTo("52/64")
        assertThat(registry.formatReadProgress(HitomiSource.ID, 52, 64, 3)).isEqualTo("3/64")
    }
}
