package com.comics8.core.model

import java.time.LocalDate

/**
 * Listing pages expose MM.DD while episode pages expose YY.MM.DD.
 * String comparison of those formats is wrong ("26.08.16" < "08.17" is false).
 */
object UpdateDates {
    fun parseScore(dateStr: String?, today: LocalDate = LocalDate.now()): Long {
        if (dateStr.isNullOrBlank()) return 0L
        val parts = dateStr.trim().split('.')
        return try {
            when (parts.size) {
                3 -> {
                    val yy = parts[0].toInt()
                    val mm = parts[1].toInt()
                    val dd = parts[2].toInt()
                    (yy * 10000L) + (mm * 100L) + dd
                }
                2 -> {
                    val mm = parts[0].toInt()
                    val dd = parts[1].toInt()
                    val year = inferTwoDigitYear(mm, dd, today)
                    (year * 10000L) + (mm * 100L) + dd
                }
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun shouldReplace(current: String?, incoming: String?, today: LocalDate = LocalDate.now()): Boolean {
        if (incoming.isNullOrBlank()) return false
        if (current.isNullOrBlank()) return true
        return parseScore(incoming, today) >= parseScore(current, today)
    }

    fun toListingDate(dateStr: String?): String? {
        if (dateStr.isNullOrBlank()) return null
        val parts = dateStr.trim().split('.')
        return try {
            when (parts.size) {
                3 -> formatMd(parts[1].toInt(), parts[2].toInt())
                2 -> formatMd(parts[0].toInt(), parts[1].toInt())
                else -> dateStr.trim()
            }
        } catch (_: Exception) {
            dateStr.trim()
        }
    }

    private fun inferTwoDigitYear(month: Int, day: Int, today: LocalDate): Int {
        val thisYear = today.year
        val candidate = runCatching { LocalDate.of(thisYear, month, day) }.getOrNull()
            ?: return thisYear % 100
        val year = if (candidate.isAfter(today.plusDays(14))) thisYear - 1 else thisYear
        return year % 100
    }

    private fun formatMd(month: Int, day: Int): String =
        "%02d.%02d".format(month, day)

    fun formatEpoch(millis: Long, pattern: String = "yy.MM.dd"): String {
        val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
        return sdf.format(java.util.Date(millis))
    }
}
