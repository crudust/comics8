package com.comics8.core.model

enum class ProgressDisplayMode(val label: String, val description: String) {
    LATEST_EPISODE("최신화 기준", "마지막으로 읽은 회차 번호 표시 (예: 120화 또는 120/150)"),
    READ_COUNT("읽은 항목 수", "읽은 누적 권수/화수 표시 (예: 5/20 읽음)"),
    PERCENTAGE("진행률(%)", "전체 회차 대비 진행률 표시 (예: 75%)"),
    HIDDEN("표시 안 함", "진행도 뱃지를 표시하지 않음");

    fun format(lastReadOrder: Int, totalEpisodes: Int, readCount: Int): String? = when (this) {
        LATEST_EPISODE -> if (totalEpisodes > 0) "$lastReadOrder/$totalEpisodes" else if (lastReadOrder > 0) "${lastReadOrder}화" else null
        READ_COUNT -> if (totalEpisodes > 0) "$readCount/$totalEpisodes" else if (readCount > 0) "${readCount}개" else null
        PERCENTAGE -> if (totalEpisodes > 0 && lastReadOrder > 0) "${(lastReadOrder * 100 / totalEpisodes).coerceIn(0, 100)}%" else null
        HIDDEN -> null
    }

    companion object {
        fun defaultFor(sourceId: String): ProgressDisplayMode {
            return when (sourceId) {
                "hitomi", "local" -> READ_COUNT
                else -> LATEST_EPISODE
            }
        }

        fun fromName(name: String?, defaultSourceId: String = ""): ProgressDisplayMode {
            if (name.isNullOrBlank()) return defaultFor(defaultSourceId)
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: defaultFor(defaultSourceId)
        }
    }
}
