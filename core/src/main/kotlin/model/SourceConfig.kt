package com.comics8.core.model

enum class ProgressDisplayMode(val label: String, val description: String) {
    LATEST_EPISODE("최신화 기준", "마지막으로 읽은 회차 번호 표시 (예: 120화 또는 120/150)"),
    READ_COUNT("읽은 항목 수", "읽은 누적 권수/화수 표시 (예: 5/20 읽음)"),
    PERCENTAGE("진행률(%)", "전체 회차 대비 진행률 표시 (예: 75%)"),
    HIDDEN("표시 안 함", "진행도 뱃지를 표시하지 않음");

    val requiresReadCount: Boolean get() = this == READ_COUNT

    fun requiresReadCount(sourceId: String): Boolean =
        this == READ_COUNT || (this == PERCENTAGE && defaultFor(sourceId) == READ_COUNT)

    /**
     * Renders a progress badge, or null when the work has no counted total.
     *
     * A total only exists after the whole listing was counted, so there is no approximate
     * denominator and no "at least" marker to render: an uncounted work shows no badge at all
     * rather than a number nobody verified.
     */
    fun format(lastReadOrder: Int, total: EpisodeListingTotal?, readCount: Int): String? {
        val count = total?.takeIf { it.isValid }?.count ?: return null
        return when (this) {
            LATEST_EPISODE -> if (lastReadOrder > 0) "$lastReadOrder/$count" else "${count}화"
            READ_COUNT -> when {
                readCount > 0 -> "$readCount/$count"
                else -> "${count}화"
            }
            PERCENTAGE -> {
                val progress = if (lastReadOrder > 0) lastReadOrder else readCount
                if (progress > 0) "${(progress * 100 / count).coerceIn(0, 100)}%" else null
            }
            HIDDEN -> null
        }
    }

    companion object {
        fun defaultFor(sourceId: String): ProgressDisplayMode {
            return when {
                sourceId == "local" || sourceId.startsWith("network-") -> READ_COUNT
                else -> LATEST_EPISODE
            }
        }

        fun fromName(name: String?, defaultMode: ProgressDisplayMode): ProgressDisplayMode {
            if (name.isNullOrBlank()) return defaultMode
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: defaultMode
        }

        fun fromName(name: String?, defaultSourceId: String = ""): ProgressDisplayMode {
            return fromName(name, defaultFor(defaultSourceId))
        }
    }
}
