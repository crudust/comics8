package com.comics8.desktop.data

import com.comics8.core.network.ToonClient
import com.comics8.core.source.SourceBindingManager
import com.comics8.core.sync.BaseSyncManager
import com.comics8.core.sync.StoreSyncStorageAdapter
import com.comics8.core.sync.SyncStorageAdapter
import com.comics8.shared.db.AppDatabase
import com.comics8.shared.db.RoomComicLocalStore
import okhttp3.OkHttpClient

class DesktopSyncStorageAdapter(
    database: AppDatabase,
    bindingManager: SourceBindingManager = SourceBindingManager.desktopGlobal,
) : SyncStorageAdapter by StoreSyncStorageAdapter(
    store = RoomComicLocalStore(database),
    prefs = DesktopPropertiesPrefs(),
    bindingManager = bindingManager,
    requireTitleFields = false,
)

class DesktopSyncManager(
    database: AppDatabase,
    toonClient: ToonClient? = null,
    client: OkHttpClient = BaseSyncManager.defaultHttpClient(),
) : BaseSyncManager(
    storage = DesktopSyncStorageAdapter(database),
    toonClient = toonClient,
    client = client,
)
