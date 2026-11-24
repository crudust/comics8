package com.comics8.desktop.data

import com.comics8.core.model.SyncResult
import com.comics8.core.model.SyncState
import com.comics8.core.network.ToonClient
import com.comics8.core.source.SourceBindingManager
import com.comics8.core.sync.BaseSyncManager
import com.comics8.core.sync.StoreSyncStorageAdapter
import com.comics8.core.sync.SyncConstants
import com.comics8.core.sync.SyncStorageAdapter
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

typealias DesktopSyncState = SyncState
typealias DesktopSyncResult = SyncResult

class DesktopSyncStorageAdapter(
    database: DesktopDatabase,
    bindingManager: SourceBindingManager = SourceBindingManager.desktopGlobal,
) : SyncStorageAdapter by StoreSyncStorageAdapter(
    store = DesktopComicLocalStore(database),
    prefs = DesktopPropertiesPrefs(),
    bindingManager = bindingManager,
    requireTitleFields = false,
)

class DesktopSyncManager(
    database: DesktopDatabase,
    toonClient: ToonClient? = null,
    client: OkHttpClient = OkHttpClient.Builder()
        .dns(com.comics8.core.network.FallbackDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) : BaseSyncManager(
    storage = DesktopSyncStorageAdapter(database),
    toonClient = toonClient,
    client = client,
) {
    companion object {
        val DEFAULT_SERVER_URL: String get() = SyncConstants.DEFAULT_SERVER_URL
        const val KEY_SYNC_KEY = SyncConstants.KEY_SYNC_KEY
        const val KEY_SERVER_URL = SyncConstants.KEY_SERVER_URL
        const val KEY_AUTO_SYNC = SyncConstants.KEY_AUTO_SYNC
        const val KEY_LAST_SYNCED_AT = SyncConstants.KEY_LAST_SYNCED_AT
        const val KEY_USE_SERVER_PROXY = SyncConstants.KEY_USE_SERVER_PROXY

        fun generateSyncKey(): String = SyncConstants.generateSyncKey()
    }
}
