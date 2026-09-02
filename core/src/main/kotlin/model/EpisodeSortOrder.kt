package com.comics8.core.model

import com.comics8.core.source.local.NaturalSort

enum class EpisodeSortOrder(val key: String) {
    DEFAULT("default"),
    NAME_ASC("name_asc"),
    NAME_DESC("name_desc"),
    DATE_DESC("date_desc"),
    DATE_ASC("date_asc");

    val isAscending: Boolean
        get() = this == NAME_ASC || this == DATE_ASC

    companion object {
        fun fromKey(key: String?): EpisodeSortOrder =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

fun List<EpisodeItem>.sortedWithOrder(order: EpisodeSortOrder): List<EpisodeItem> = when (order) {
    EpisodeSortOrder.DEFAULT -> this
    EpisodeSortOrder.NAME_ASC -> sortedWith(compareBy(NaturalSort) { it.title })
    EpisodeSortOrder.NAME_DESC -> sortedWith(compareByDescending(NaturalSort) { it.title })
    EpisodeSortOrder.DATE_DESC -> sortedWith(
        compareByDescending<EpisodeItem> { it.mtime ?: (UpdateDates.parseScore(it.date).takeIf { s -> s > 0L }) ?: 0L }
            .thenByDescending(NaturalSort) { it.title }
    )
    EpisodeSortOrder.DATE_ASC -> sortedWith(
        compareBy<EpisodeItem> { it.mtime ?: (UpdateDates.parseScore(it.date).takeIf { s -> s > 0L }) ?: Long.MAX_VALUE }
            .thenBy(NaturalSort) { it.title }
    )
}
