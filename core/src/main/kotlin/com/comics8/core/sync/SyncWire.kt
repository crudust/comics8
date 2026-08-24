package com.comics8.core.sync

import com.comics8.core.source.WorkId
import okhttp3.Request
import org.json.JSONObject

object SyncWire {
    fun isSyncableSource(sourceId: String): Boolean {
        return sourceId.isNotBlank() && sourceId != WorkId.LOCAL_SOURCE
    }

    fun workId(obj: JSONObject, preferId: Boolean): WorkId? {
        val rawSource = obj.optString("sourceId")
        if (rawSource == WorkId.LOCAL_SOURCE) return null
        val sourceId = rawSource.ifBlank { WorkId.DEFAULT_SOURCE }
        val local = if (preferId) {
            obj.optString("id").ifBlank { obj.optString("toonId") }
        } else {
            obj.optString("toonId").ifBlank { obj.optString("id") }
        }
        return WorkId.stored(sourceId, local)
    }

    fun tombstoneEntityId(entityId: String): String {
        val work = WorkId.parse(entityId)
        return work.storageKey()
    }
}

fun Request.Builder.addComics8SyncHeaders(key: String): Request.Builder {
    return addHeader("Authorization", "Bearer $key")
        .addHeader("X-Sync-Key", key)
}
