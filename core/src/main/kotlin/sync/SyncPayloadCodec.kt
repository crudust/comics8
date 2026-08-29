package com.comics8.core.sync

import com.comics8.core.backup.BackupEpisodeWire
import com.comics8.core.backup.BackupFavoriteWire
import com.comics8.core.backup.BackupHistoryWire
import com.comics8.core.backup.BackupReaderSettingWire
import com.comics8.core.backup.BackupWire
import com.comics8.core.backup.BackupWireCodec
import com.comics8.core.backup.BackupWireDefaults
import org.json.JSONArray
import org.json.JSONObject

data class SyncTombstoneWire(
    val entityType: String,
    val entityId: String,
    val deletedAt: Long,
)

data class SyncPayload(
    val favorites: List<BackupFavoriteWire> = emptyList(),
    val history: List<BackupHistoryWire> = emptyList(),
    val readEpisodes: List<BackupEpisodeWire> = emptyList(),
    val readerSettings: List<BackupReaderSettingWire> = emptyList(),
    val tombstones: List<SyncTombstoneWire> = emptyList(),
)

object SyncPayloadCodec {
    fun encode(
        payload: SyncPayload,
        includeMetadata: Boolean = false,
        exportedAt: Long = System.currentTimeMillis(),
    ): JSONObject {
        val root = BackupWireCodec.encodeObject(
            BackupWire(
                exportedAt = exportedAt,
                favorites = payload.favorites,
                history = payload.history,
                readEpisodes = payload.readEpisodes,
                readerSettings = payload.readerSettings,
            ),
        )
        if (!includeMetadata) {
            root.remove("version")
            root.remove("appName")
            root.remove("exportedAt")
        }
        val history = root.optJSONArray("history") ?: JSONArray()
        for (index in 0 until history.length()) {
            val item = history.getJSONObject(index)
            item.put("hasNew", if (item.optBoolean("hasNew")) 1 else 0)
        }
        if (payload.tombstones.isNotEmpty() || !includeMetadata) {
            root.put("tombstones", JSONArray().apply {
                payload.tombstones.forEach { tombstone ->
                    put(JSONObject().apply {
                        put("entityType", tombstone.entityType)
                        put("entityId", SyncWire.tombstoneEntityId(tombstone.entityId))
                        put("deletedAt", tombstone.deletedAt)
                    })
                }
            })
        }
        return root
    }

    fun decode(root: JSONObject, serverTime: Long): SyncPayload {
        val backup = BackupWireCodec.decodeObject(
            root,
            nowMillis = { serverTime },
            defaults = BackupWireDefaults.SYNC,
        )
        val tombstones = root.optJSONArray("tombstones") ?: JSONArray()
        return SyncPayload(
            favorites = backup.favorites,
            history = backup.history,
            readEpisodes = backup.readEpisodes,
            readerSettings = backup.readerSettings,
            tombstones = List(tombstones.length()) { index ->
                val item = tombstones.getJSONObject(index)
                SyncTombstoneWire(
                    entityType = item.optString("entityType", ""),
                    entityId = item.optString("entityId", ""),
                    deletedAt = item.optLong("deletedAt", serverTime),
                )
            },
        )
    }
}
