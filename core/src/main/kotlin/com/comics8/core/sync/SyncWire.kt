package com.comics8.core.sync

import com.comics8.core.source.WorkId
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class ServerSchema {
    data class Known(val version: Int) : ServerSchema()
    data object Unknown : ServerSchema()

    val omitForeign: Boolean
        get() = when (this) {
            Unknown -> true
            is Known -> version < SyncConstants.SCHEMA_VERSION
        }

    val commitForeign: Boolean
        get() = this is Known && version >= SyncConstants.SCHEMA_VERSION
}

data class SyncOutbound(
    val json: JSONObject,
    val omittedForeignSource: Boolean,
    val schema: ServerSchema,
)

object SyncWire {
    const val FOREIGN_OMITTED_SCHEMA1_MESSAGE =
        "서버가 구버전(schema 1)이라 Hitomi 등 다른 소스 변경은 전송하지 않았습니다."
    const val FOREIGN_OMITTED_UNKNOWN_MESSAGE =
        "서버 schema를 확인하지 못해 Hitomi 등 다른 소스 변경은 전송하지 않았습니다."

    private data class CachedProbe(val schema: ServerSchema, val cachedAt: Long)

    private val probeCache = ConcurrentHashMap<String, CachedProbe>()

    fun clearProbeCache() {
        probeCache.clear()
    }

    fun parseSchema(value: String?): Int {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return 1
        return raw.toIntOrNull() ?: 1
    }

    fun resolveSuccessfulHealth(healthJson: String?, headerValue: String?): ServerSchema {
        if (!healthJson.isNullOrBlank()) {
            try {
                val obj = JSONObject(healthJson)
                if (obj.has("syncSchema")) {
                    return ServerSchema.Known(parseSchema(obj.opt("syncSchema")?.toString()))
                }
            } catch (_: Exception) {
                val header = headerValue?.trim().orEmpty()
                if (header.isNotEmpty()) return ServerSchema.Known(parseSchema(header))
                return ServerSchema.Unknown
            }
        }
        val header = headerValue?.trim().orEmpty()
        if (header.isNotEmpty()) return ServerSchema.Known(parseSchema(header))
        return ServerSchema.Known(1)
    }

    fun probeServerSchema(
        client: OkHttpClient,
        serverUrl: String,
        key: String,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = SyncConstants.SCHEMA_PROBE_TTL_MS,
    ): ServerSchema {
        val cacheKey = SyncConstants.healthUrl(serverUrl)
        val cached = probeCache[cacheKey]
        if (cached != null && cached.schema is ServerSchema.Known && nowMs - cached.cachedAt < ttlMs) {
            return cached.schema
        }
        val probed = probeUncached(client, serverUrl, key)
        if (probed is ServerSchema.Known) {
            probeCache[cacheKey] = CachedProbe(probed, nowMs)
            return probed
        }
        val lastKnown = cached?.schema
        if (lastKnown is ServerSchema.Known) return lastKnown
        return ServerSchema.Unknown
    }

    private fun probeUncached(client: OkHttpClient, serverUrl: String, key: String): ServerSchema {
        return try {
            val probeClient = client.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
            val req = Request.Builder()

                .url(SyncConstants.healthUrl(serverUrl))
                .addComics8SyncHeaders(key)
                .get()
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ServerSchema.Unknown
                resolveSuccessfulHealth(resp.body?.string(), resp.header(SyncConstants.SCHEMA_HEADER))
            }
        } catch (_: Exception) {
            ServerSchema.Unknown
        }
    }

    fun isSyncableSource(sourceId: String): Boolean {
        if (sourceId == WorkId.LOCAL_SOURCE) return false
        return true
    }

    fun allowOutbound(sourceId: String, schema: ServerSchema): Boolean {
        if (!isSyncableSource(sourceId)) return false
        if (!schema.omitForeign) return true
        return sourceId.ifBlank { WorkId.DEFAULT_SOURCE } == WorkId.DEFAULT_SOURCE
    }

    fun localQuerySince(elevenSince: Long, foreignSince: Long, schema: ServerSchema): Long {
        return minOf(elevenSince, foreignSince)
    }

    fun requestSince(elevenSince: Long, foreignSince: Long, schema: ServerSchema): Long {
        return if (schema.commitForeign) minOf(elevenSince, foreignSince) else elevenSince
    }

    fun includeRow(
        sourceId: String,
        rowTime: Long,
        elevenSince: Long,
        foreignSince: Long,
        schema: ServerSchema,
    ): Boolean {
        if (!isSyncableSource(sourceId)) return false
        val source = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        return if (source == WorkId.DEFAULT_SOURCE) {
            rowTime > elevenSince
        } else {
            rowTime > foreignSince
        }
    }

    fun includeTombstone(
        entityId: String,
        deletedAt: Long,
        elevenSince: Long,
        foreignSince: Long,
        schema: ServerSchema,
    ): Boolean = includeRow(WorkId.parse(entityId).sourceId, deletedAt, elevenSince, foreignSince, schema)

    fun shouldCommitForeign(schema: ServerSchema, omittedForeignSource: Boolean): Boolean {
        return schema.commitForeign && !omittedForeignSource
    }

    fun tombstoneEntityId(entityId: String, schema: ServerSchema): String {
        val work = WorkId.parse(entityId)
        return if (schema.commitForeign) work.storageKey() else work.toonId
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

    fun omittedMessage(schema: ServerSchema): String {
        return if (schema is ServerSchema.Known && schema.version < SyncConstants.SCHEMA_VERSION) {
            FOREIGN_OMITTED_SCHEMA1_MESSAGE
        } else {
            FOREIGN_OMITTED_UNKNOWN_MESSAGE
        }
    }

    fun withOmittedHint(message: String, omittedForeignSource: Boolean, schema: ServerSchema): String {
        if (!omittedForeignSource) return message
        val hint = omittedMessage(schema)
        return if (message.isBlank()) hint else "$message $hint"
    }

    fun filterOutbound(root: JSONObject, schema: ServerSchema): SyncOutbound {
        val out = JSONObject(root.toString())
        var omitted = false
        omitted = filterArray(out, "favorites", schema, tombstones = false) || omitted
        omitted = filterArray(out, "history", schema, tombstones = false) || omitted
        omitted = filterArray(out, "readEpisodes", schema, tombstones = false) || omitted
        omitted = filterArray(out, "readerSettings", schema, tombstones = false) || omitted
        omitted = filterArray(out, "tombstones", schema, tombstones = true) || omitted
        val nested = out.optJSONObject("changes")
        if (nested != null) {
            val inner = filterOutbound(nested, schema)
            out.put("changes", inner.json)
            omitted = omitted || inner.omittedForeignSource
        }
        return SyncOutbound(out, omitted, schema)
    }

    private fun filterArray(
        parent: JSONObject,
        key: String,
        schema: ServerSchema,
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
            if (!isSyncableSource(source)) {
                continue
            }
            if (!allowOutbound(source, schema)) {
                omitted = true
                continue
            }
            next.put(normalizeRow(JSONObject(item.toString()), key, schema))
        }
        parent.put(key, next)
        return omitted
    }

    private fun normalizeRow(copy: JSONObject, key: String, schema: ServerSchema): JSONObject {
        when (key) {
            "favorites" -> {
                val id = copy.optString("id").ifBlank { copy.optString("toonId") }
                copy.remove("toonId")
                if (id.isNotBlank()) copy.put("id", id)
                copy.put("sourceId", copy.optString("sourceId").ifBlank { WorkId.DEFAULT_SOURCE })
            }
            "tombstones" -> {
                copy.put("entityId", tombstoneEntityId(copy.optString("entityId"), schema))
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
