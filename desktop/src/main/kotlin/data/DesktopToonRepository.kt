package com.comics8.desktop.data

import com.comics8.core.data.SharedToonRepository
import com.comics8.core.network.ToonClient
import com.comics8.core.source.SourceBindingManager
import com.comics8.core.source.SourceRegistry
import com.comics8.shared.db.AppDatabase
import com.comics8.shared.db.RoomComicLocalStore

class DesktopToonRepository(
    client: ToonClient,
    database: AppDatabase,
    override val syncManager: DesktopSyncManager? = null,
    override val downloadManager: DesktopDownloadManager? = null,
    now: () -> Long = { System.currentTimeMillis() },
    sources: SourceRegistry,
    isSourceEnabled: (String) -> Boolean = { false },
    installedIds: () -> Set<String> = { sources.knownIds() },
    bindingManager: SourceBindingManager = SourceBindingManager.desktopGlobal,
) : SharedToonRepository(
    client = client,
    store = RoomComicLocalStore(database),
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
)
