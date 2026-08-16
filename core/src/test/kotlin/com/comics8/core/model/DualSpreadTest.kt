package com.comics8.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DualSpreadTest {
    @Test
    fun spreadsGroupConsecutivePortraits() {
        val spreads = buildDualSpreads(4, emptyMap())
        assertThat(spreads).containsExactly(
            DualSpread.Dual(0, 1),
            DualSpread.Dual(2, 3),
        ).inOrder()
    }

    @Test
    fun spreadsKeepOddTrailingAsSingle() {
        val spreads = buildDualSpreads(5, emptyMap())
        assertThat(spreads).containsExactly(
            DualSpread.Dual(0, 1),
            DualSpread.Dual(2, 3),
            DualSpread.Single(4),
        ).inOrder()
    }

    @Test
    fun widePageRemainsSingle() {
        val spreads = buildDualSpreads(
            imageCount = 4,
            aspectRatios = mapOf(1 to 1.4f),
        )
        assertThat(spreads).containsExactly(
            DualSpread.Single(0),
            DualSpread.Single(1),
            DualSpread.Dual(2, 3),
        ).inOrder()
    }
}
