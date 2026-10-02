package com.comics8.core.sync

import org.json.JSONObject

data class SyncApplyCounts(
    val favorites: Int = 0,
    val history: Int = 0,
    val episodes: Int = 0,
)

/**
 * Storage abstraction for SyncManager.
 * Implemented by Android Room DB and Desktop SQLite DB.
 */
interface SyncStorageAdapter {
    /**
     * Extracts local changes modified since [since] timestamp (in epoch ms).
     * Returns JSON matching the sync delta protocol.
     */
    suspend fun getLocalChangesSince(since: Long): JSONObject

    /**
     * Extracts a full snapshot of all local syncable entities.
     */
    suspend fun getFullSnapshot(): JSONObject

    /**
     * Applies incremental remote changes from the server.
     * Returns SyncApplyCounts(appliedFavoritesCount, appliedHistoryCount, appliedEpisodesCount).
     */
    suspend fun applyRemoteChanges(serverChanges: JSONObject, serverTime: Long): SyncApplyCounts

    /**
     * Replaces or merges full snapshot from the server.
     * Returns SyncApplyCounts(appliedFavoritesCount, appliedHistoryCount, appliedEpisodesCount).
     */
    suspend fun applyFullSnapshot(snapshot: JSONObject, serverTime: Long): SyncApplyCounts

    /**
     * Deletes tombstones older than [cutoff] timestamp.
     */
    suspend fun deleteOldTombstones(cutoff: Long)

    /**
     * Reads a preference value.
     */
    fun getPreference(key: String, defaultValue: String?): String?

    /**
     * Saves a preference value.
     */
    fun setPreference(key: String, value: String?)
}
