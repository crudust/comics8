package com.comics8.core.model

enum class ProgressDisplayMode(val label: String, val description: String) {
    LATEST_EPISODE("최신화 기준", "마지막으로 읽은 회차 번호 표시 (예: 120화 또는 120/150)"),
    READ_COUNT("읽은 항목 수", "읽은 누적 권수/화수 표시 (예: 5/20 읽음)"),
    PERCENTAGE("진행률(%)", "전체 회차 대비 진행률 표시 (예: 75%)"),
    HIDDEN("표시 안 함", "진행도 뱃지를 표시하지 않음");

    val requiresReadCount: Boolean get() = this == READ_COUNT

    fun requiresReadCount(sourceId: String): Boolean =
        this == READ_COUNT || (this == PERCENTAGE && defaultFor(sourceId) == READ_COUNT)

    fun format(lastReadOrder: Int, totalEpisodes: Int, readCount: Int): String? {
        val effectiveTotal = maxOf(totalEpisodes, lastReadOrder, readCount)
        return when (this) {
            LATEST_EPISODE -> when {
                lastReadOrder > 0 && effectiveTotal > 0 -> "$lastReadOrder/$effectiveTotal"
                lastReadOrder > 0 -> "${lastReadOrder}화"
                effectiveTotal > 0 -> "${effectiveTotal}화"
                else -> null
            }
            READ_COUNT -> when {
                readCount > 0 && effectiveTotal > 0 -> "$readCount/$effectiveTotal"
                readCount > 0 -> "${readCount}개"
                effectiveTotal > 0 -> "${effectiveTotal}화"
                else -> null
            }
            PERCENTAGE -> if (effectiveTotal > 0 && (lastReadOrder > 0 || readCount > 0)) {
                val progress = if (lastReadOrder > 0) lastReadOrder else readCount
                "${(progress * 100 / effectiveTotal).coerceIn(0, 100)}%"
            } else null
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
