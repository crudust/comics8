package com.comics8.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class UpdateDatesTest {
    private val today = LocalDate.of(2026, 8, 18)

    @Test
    fun parseScoreTreatsListingAndEpisodeDatesAsSameDay() {
        assertThat(UpdateDates.parseScore("08.16", today))
            .isEqualTo(UpdateDates.parseScore("26.08.16", today))
        assertThat(UpdateDates.parseScore("08.16", today)).isEqualTo(260816L)
    }

    @Test
    fun parseScoreOrdersNewerListingDateAboveOlderEpisodeDate() {
        assertThat(UpdateDates.parseScore("08.17", today))
            .isGreaterThan(UpdateDates.parseScore("26.08.16", today))
    }

    @Test
    fun parseScoreTreatsDecemberListingDateAsPreviousYearInJanuary() {
        val january = LocalDate.of(2026, 1, 5)
        assertThat(UpdateDates.parseScore("12.31", january)).isEqualTo(251231L)
        assertThat(UpdateDates.parseScore("01.05", january)).isEqualTo(260105L)
    }

    @Test
    fun shouldReplaceUsesDateScoreNotStringOrder() {
        assertThat(UpdateDates.shouldReplace("26.08.16", "08.17", today)).isTrue()
        assertThat(UpdateDates.shouldReplace("26.08.16", "08.16", today)).isTrue()
        assertThat(UpdateDates.shouldReplace("08.17", "26.08.16", today)).isFalse()
        assertThat(UpdateDates.shouldReplace(null, "08.16", today)).isTrue()
        assertThat(UpdateDates.shouldReplace("08.16", "", today)).isFalse()
    }

    @Test
    fun toListingDateNormalizesEpisodeFormat() {
        assertThat(UpdateDates.toListingDate("26.08.16")).isEqualTo("08.16")
        assertThat(UpdateDates.toListingDate("8.5")).isEqualTo("08.05")
    }

    @Test
    fun formatEpochFormatsMillisToDateString() {
        // 2026-08-30 00:00:00 UTC = 1788048000000 ms approx
        val millis = 1788048000000L
        assertThat(UpdateDates.formatEpoch(millis, "yyyy")).isEqualTo("2026")
        assertThat(UpdateDates.formatEpoch(millis)).isNotEmpty()
    }
}
