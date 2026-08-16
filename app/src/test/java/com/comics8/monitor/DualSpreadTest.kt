package com.comics8.monitor

import com.comics8.monitor.data.DualSpread
import com.comics8.monitor.data.buildDualSpreads
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DualSpreadTest {

    @Test
    fun testAllPortraitImages_pairedNormally() {
        val ratios = mapOf(
            0 to 0.7f,
            1 to 0.7f,
            2 to 0.7f,
            3 to 0.7f,
        )
        val spreads = buildDualSpreads(4, ratios)
        assertThat(spreads).containsExactly(
            DualSpread.Dual(0, 1),
            DualSpread.Dual(2, 3),
        ).inOrder()
    }

    @Test
    fun testOddPortraitImages_lastIsSingle() {
        val ratios = mapOf(
            0 to 0.7f,
            1 to 0.7f,
            2 to 0.7f,
        )
        val spreads = buildDualSpreads(3, ratios)
        assertThat(spreads).containsExactly(
            DualSpread.Dual(0, 1),
            DualSpread.Single(2),
        ).inOrder()
    }

    @Test
    fun testWideSpreadInMiddle_isolatedAsSingle() {
        val ratios = mapOf(
            0 to 0.7f,
            1 to 0.7f,
            2 to 1.42f, // wide 2-page spread
            3 to 0.7f,
            4 to 0.7f,
        )
        val spreads = buildDualSpreads(5, ratios)
        assertThat(spreads).containsExactly(
            DualSpread.Dual(0, 1),
            DualSpread.Single(2),
            DualSpread.Dual(3, 4),
        ).inOrder()
    }

    @Test
    fun testWideSpreadAtPage1_preventsPairingWithWide() {
        val ratios = mapOf(
            0 to 0.7f,
            1 to 1.4f, // wide spread
            2 to 0.7f,
            3 to 0.7f,
        )
        val spreads = buildDualSpreads(4, ratios)
        assertThat(spreads).containsExactly(
            DualSpread.Single(0),
            DualSpread.Single(1),
            DualSpread.Dual(2, 3),
        ).inOrder()
    }
}
