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
    val totalEpisodes: Int = 0,
    val updatedAt: Long = 0L,
    /** True when [totalEpisodes] was derived from the last page of the listing. */
    val exact: Boolean = false,
    /** Page count of the listing when it was verified; 0 when never swept in full. */
    val lastPage: Int = 0,
    /** Items on the last page at verification time; the single unknown of the total formula. */
    val lastPageItemCount: Int = -1,
) {
    fun workId(): WorkId = WorkId(sourceId, toonId)

    fun total(): com.comics8.core.model.EpisodeTotal =
        com.comics8.core.model.EpisodeTotal.fromStored(totalEpisodes, exact)

    fun snapshot(): com.comics8.core.model.EpisodeListingSnapshot? =
        if (lastPage > 0 && lastPageItemCount >= 0) {
            com.comics8.core.model.EpisodeListingSnapshot(lastPage, lastPageItemCount)
        } else {
            null
        }

    companion object {
        fun of(
            workId: WorkId,
            total: com.comics8.core.model.EpisodeTotal,
            updatedAt: Long,
            snapshot: com.comics8.core.model.EpisodeListingSnapshot? = null,
        ): ToonCatalogRecord = ToonCatalogRecord(
            sourceId = workId.sourceId,
            toonId = workId.toonId,
            totalEpisodes = total.count,
            updatedAt = updatedAt,
            exact = total.isExact,
            lastPage = snapshot?.lastPage ?: 0,
            lastPageItemCount = snapshot?.lastPageItemCount ?: -1,
        )
    }
}


