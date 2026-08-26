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

