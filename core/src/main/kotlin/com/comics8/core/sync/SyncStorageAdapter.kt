package com.comics8.core.sync

import org.json.JSONObject

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
     * Returns Pair(appliedFavoritesCount, appliedHistoryCount).
     */
    suspend fun applyRemoteChanges(serverChanges: JSONObject, serverTime: Long): Pair<Int, Int>

    /**
     * Replaces or merges full snapshot from the server.
     * Returns Pair(appliedFavoritesCount, appliedHistoryCount).
     */
    suspend fun applyFullSnapshot(snapshot: JSONObject, serverTime: Long): Pair<Int, Int>

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
