package com.comics8.core.sync

import com.comics8.core.source.WorkId
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class SyncOutbound(
    val json: JSONObject,
    val omittedHitomi: Boolean,
)

object SyncWire {
    const val HITOMI_OMITTED_MESSAGE =
        "서버가 구버전(schema 1)이라 Hitomi 변경은 전송하지 않았습니다."

    fun parseSchema(value: String?): Int {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return 1
        return raw.toIntOrNull() ?: 1
    }

    fun resolveServerSchema(healthJson: String?, headerValue: String?): Int {
        val header = headerValue?.trim().orEmpty()
        if (header.isNotEmpty()) return parseSchema(header)
        if (healthJson.isNullOrBlank()) return 1
        return try {
            val obj = JSONObject(healthJson)
            if (!obj.has("syncSchema")) return 1
            parseSchema(obj.opt("syncSchema")?.toString())
        } catch (_: Exception) {
            1
        }
    }

    fun probeServerSchema(client: OkHttpClient, serverUrl: String, key: String): Int {
        return try {
            val req = Request.Builder()
                .url(SyncConstants.healthUrl(serverUrl))
                .addComics8SyncHeaders(key)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val header = resp.header(SyncConstants.SCHEMA_HEADER)
                val body = if (resp.isSuccessful) resp.body?.string() else null
                resolveServerSchema(body, header)
            }
        } catch (_: Exception) {
            1
        }
    }

    fun allowOutbound(sourceId: String, serverSchema: Int): Boolean {
        if (serverSchema >= SyncConstants.SCHEMA_VERSION) return true
        return sourceId.ifBlank { WorkId.DEFAULT_SOURCE } == WorkId.DEFAULT_SOURCE
    }

    fun tombstoneEntityId(entityId: String, serverSchema: Int): String {
        val work = WorkId.parse(entityId)
        return if (serverSchema >= SyncConstants.SCHEMA_VERSION) {
            work.storageKey()
        } else {
            work.toonId
        }
    }

    fun workId(obj: JSONObject, preferId: Boolean): WorkId? {
        val sourceId = obj.optString("sourceId").ifBlank { WorkId.DEFAULT_SOURCE }
        val local = if (preferId) {
            obj.optString("id").ifBlank { obj.optString("toonId") }
        } else {
            obj.optString("toonId").ifBlank { obj.optString("id") }
        }
        if (local.isBlank()) return null
        return WorkId.writable(sourceId, local)
    }

    fun withOmittedHint(message: String, omittedHitomi: Boolean): String {
        if (!omittedHitomi) return message
        return if (message.isBlank()) HITOMI_OMITTED_MESSAGE else "$message $HITOMI_OMITTED_MESSAGE"
    }

    fun filterOutbound(root: JSONObject, serverSchema: Int): SyncOutbound {
        val out = JSONObject(root.toString())
        var omitted = false
        omitted = filterArray(out, "favorites", serverSchema, tombstones = false) || omitted
        omitted = filterArray(out, "history", serverSchema, tombstones = false) || omitted
        omitted = filterArray(out, "readEpisodes", serverSchema, tombstones = false) || omitted
        omitted = filterArray(out, "readerSettings", serverSchema, tombstones = false) || omitted
        omitted = filterArray(out, "tombstones", serverSchema, tombstones = true) || omitted
        val nested = out.optJSONObject("changes")
        if (nested != null) {
            val inner = filterOutbound(nested, serverSchema)
            out.put("changes", inner.json)
            omitted = omitted || inner.omittedHitomi
        }
        return SyncOutbound(out, omitted)
    }

    private fun filterArray(
        parent: JSONObject,
        key: String,
        serverSchema: Int,
        tombstones: Boolean,
    ): Boolean {
        val arr = parent.optJSONArray(key) ?: return false
        val next = JSONArray()
        var omitted = false
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val source = if (tombstones) {
                WorkId.parse(item.optString("entityId")).sourceId
            } else {
                item.optString("sourceId").ifBlank { WorkId.DEFAULT_SOURCE }
            }
            if (!allowOutbound(source, serverSchema)) {
                omitted = true
                continue
            }
            next.put(normalizeRow(JSONObject(item.toString()), key, serverSchema))
        }
        parent.put(key, next)
        return omitted
    }

    private fun normalizeRow(copy: JSONObject, key: String, serverSchema: Int): JSONObject {
        when (key) {
            "favorites" -> {
                val id = copy.optString("id").ifBlank { copy.optString("toonId") }
                copy.remove("toonId")
                if (id.isNotBlank()) copy.put("id", id)
                copy.put("sourceId", copy.optString("sourceId").ifBlank { WorkId.DEFAULT_SOURCE })
            }
            "tombstones" -> {
                copy.put("entityId", tombstoneEntityId(copy.optString("entityId"), serverSchema))
            }
            else -> {
                if (copy.optString("sourceId").isBlank()) {
                    copy.put("sourceId", WorkId.DEFAULT_SOURCE)
                }
            }
        }
        return copy
    }
}

fun Request.Builder.addComics8SyncHeaders(key: String): Request.Builder {
    return addHeader("Authorization", "Bearer $key")
        .addHeader("X-Sync-Key", key)
        .addHeader(SyncConstants.SCHEMA_HEADER, SyncConstants.SCHEMA_VERSION.toString())
}
