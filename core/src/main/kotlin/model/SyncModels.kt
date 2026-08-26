package com.comics8.core.model

data class SyncState(
    val isSyncing: Boolean = false,
    val isSuccess: Boolean? = null,
    val lastSyncedAt: Long = 0L,
    val syncKey: String = "",
    val serverUrl: String = "",
    val autoSyncEnabled: Boolean = true,
    val useServerProxy: Boolean = true,
    val isPro: Boolean = false,
    val syncMessage: String? = null,
)

data class SyncResult(
    val success: Boolean,
    val message: String,
    val favoritesCount: Int = 0,
    val historyCount: Int = 0,
)

data class BackupStats(
    val favoriteCount: Int,
    val historyCount: Int,
    val episodeCount: Int,
    val settingCount: Int,
)

data class BackupResult(
    val success: Boolean,
    val message: String,
    val favoriteCount: Int = 0,
    val historyCount: Int = 0,
)

data class SyncTombstone(
    val entityType: String, // "FAVORITE", "HISTORY", "EPISODE", "SETTING"
    val entityId: String,
    val deletedAt: Long,
)

data class PairRequestResult(
    val success: Boolean,
    val code: String = "",
    val expiresInSeconds: Int = 300,
    val message: String? = null,
)

data class PairConfirmResult(
    val success: Boolean,
    val syncKey: String = "",
    val message: String? = null,
)
