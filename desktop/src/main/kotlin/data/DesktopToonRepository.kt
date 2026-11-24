package com.comics8.desktop.data

import com.comics8.core.data.SharedToonRepository
import com.comics8.core.network.ToonClient
import com.comics8.core.source.SourceBindingManager
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId

class DesktopToonRepository(
    client: ToonClient,
    private val database: DesktopDatabase,
    override val syncManager: DesktopSyncManager? = null,
    override val downloadManager: DesktopDownloadManager? = null,
    now: () -> Long = { System.currentTimeMillis() },
    sources: SourceRegistry,
    isSourceEnabled: (String) -> Boolean = { false },
    installedIds: () -> Set<String> = { sources.knownIds() },
    bindingManager: SourceBindingManager = SourceBindingManager.desktopGlobal,
) : SharedToonRepository(
    client = client,
    store = DesktopComicLocalStore(database),
    sourceSettings = DesktopSourcePrefs,
    syncManager = syncManager,
    downloadManager = downloadManager,
    now = now,
    sources = sources,
    isSourceEnabled = isSourceEnabled,
    installedIds = installedIds,
    bindingManager = bindingManager,
    seenNotifiedKeyFallback = { item -> "${item.workId().storageKey()}|${item.updatedAt.orEmpty()}" },
    upsertSeenOnSaveHistory = false,
) {
    init {
        database.isSourceEnabled = isSourceEnabled
        database.installedIds = installedIds
    }

    fun setSourceWriteAccess(isEnabled: (String) -> Boolean) {
        database.isSourceEnabled = isEnabled
    }

    suspend fun getReadEpisode(workId: WorkId, wrId: String): ReadEpisodeRecord? =
        getReadEpisodeRecord(workId, wrId)

    suspend fun getHistory(sourceId: String): List<ReadHistoryRecord> =
        getHistoryRecords(sourceId)

    suspend fun getHistory(workId: WorkId): ReadHistoryRecord? =
        getHistoryRecord(workId)

    suspend fun saveHistory(history: ReadHistoryRecord) =
        saveHistoryRecord(history)
}
