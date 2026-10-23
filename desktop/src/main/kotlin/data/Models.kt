package com.comics8.desktop.data

import com.comics8.core.source.WorkId

data class FavoriteRecord(
    val sourceId: String = WorkId.DEFAULT_SOURCE,
    val id: String,
    val title: String,
    val thumbUrl: String,
    val href: String,
    val genre: String,
    val updatedAt: String?,
    val savedAt: Long,
) {
    fun workId(): WorkId = WorkId(sourceId, id)
}

data class SeenRecord(
    val sourceId: String = WorkId.DEFAULT_SOURCE,
    val id: String,
    val title: String,
    val updatedAt: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val notifiedKey: String,
) {
    fun workId(): WorkId = WorkId(sourceId, id)
}

data class ReadEpisodeRecord(
    val sourceId: String = WorkId.DEFAULT_SOURCE,
    val toonId: String,
    val wrId: String,
    val readAt: Long,
    val lastPage: Int = 0,
) {
    fun workId(): WorkId = WorkId(sourceId, toonId)
}

data class ReadHistoryRecord(
    val sourceId: String = WorkId.DEFAULT_SOURCE,
    val toonId: String,
    val toonTitle: String,
    val toonThumbUrl: String,
    val toonHref: String,
    val lastWrId: String,
    val lastEpisodeTitle: String,
    val lastEpisodeHref: String,
    val lastReadOrder: Int,
    val totalEpisodes: Int,
    val lastReadAt: Long,
    val nextWrId: String? = null,
    val nextEpisodeTitle: String? = null,
    val nextEpisodeHref: String? = null,
    val hasNew: Boolean = false,
) {
    fun workId(): WorkId = WorkId(sourceId, toonId)
}

data class ReaderSettingRecord(
    val sourceId: String = WorkId.DEFAULT_SOURCE,
    val toonId: String,
    val viewMode: String,
    val readDirection: String,
    val splitMode: String = "FIT",
    val updatedAt: Long,
) {
    fun workId(): WorkId = WorkId(sourceId, toonId)
}

data class DownloadedEpisodeRecord(
    val sourceId: String = WorkId.DEFAULT_SOURCE,
    val toonId: String,
    val wrId: String,
    val toonTitle: String,
    val toonThumbUrl: String,
    val toonHref: String,
    val episodeTitle: String,
    val episodeHref: String,
    val imageCount: Int,
    val totalBytes: Long,
    val downloadedAt: Long,
    val localDirPath: String,
) {
    fun workId(): WorkId = WorkId(sourceId, toonId)
}

data class ToonCatalogRecord(
    val sourceId: String = WorkId.DEFAULT_SOURCE,
    val toonId: String,
    /** Counted episode total; 0 means the listing has never been counted. */
    val totalEpisodes: Int = 0,
    val updatedAt: Long = 0L,
    /** Page count of the listing when it was counted (0 = never counted). */
    val lastPage: Int = 0,
    /** Page size in effect when it was counted (0 = never counted). */
    val pageSize: Int = 0,
    /** Correction learned by the counting sweep (see EpisodeListingTotal.delta). */
    val delta: Int = 0,
) {
    fun workId(): WorkId = WorkId(sourceId, toonId)

    fun total(): com.comics8.core.model.EpisodeListingTotal? =
        com.comics8.core.model.EpisodeListingTotal.fromStored(totalEpisodes, lastPage, pageSize, delta)

    companion object {
        fun of(
            workId: WorkId,
            total: com.comics8.core.model.EpisodeListingTotal?,
            updatedAt: Long,
        ): ToonCatalogRecord = ToonCatalogRecord(
            sourceId = workId.sourceId,
            toonId = workId.toonId,
            totalEpisodes = total?.count ?: 0,
            updatedAt = updatedAt,
            lastPage = total?.lastPage ?: 0,
            pageSize = total?.pageSize ?: 0,
            delta = total?.delta ?: 0,
        )
    }
}


