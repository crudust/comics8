package com.comics8.desktop.data

import com.comics8.core.data.SharedDownloadManager
import com.comics8.core.download.DownloadProgressState as CoreDownloadProgressState
import com.comics8.core.download.DownloadTask as CoreDownloadTask
import com.comics8.core.network.ToonClient
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import java.io.File

typealias DesktopDownloadTask = CoreDownloadTask
typealias DesktopDownloadProgressState = CoreDownloadProgressState

class DesktopDownloadManager(
    database: DesktopDatabase,
    client: ToonClient,
    isSourceEnabled: (String) -> Boolean = { false },
    baseDir: File = File(System.getProperty("user.home"), ".comics8/downloads"),
    sources: SourceRegistry,
    installedIds: () -> Set<String> = { sources.knownIds() },
) : SharedDownloadManager(
    store = DesktopComicLocalStore(database),
    client = client,
    isSourceEnabled = isSourceEnabled,
    baseDir = baseDir,
    sources = sources,
    installedIds = installedIds,
) {
    suspend fun getDownloadedEpisode(workId: WorkId, wrId: String): DownloadedEpisodeRecord? =
        getDownloadedEpisodeRecord(workId, wrId)

    suspend fun getDownloadedEpisodes(workId: WorkId): List<DownloadedEpisodeRecord> =
        getDownloadedEpisodeRecords(workId)
}
