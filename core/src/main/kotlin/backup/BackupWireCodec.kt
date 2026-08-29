package com.comics8.core.backup

import com.comics8.core.source.WorkId
import com.comics8.core.sync.SyncWire
import org.json.JSONArray
import org.json.JSONObject

data class BackupWire(
    val version: Int = BackupWireCodec.VERSION,
    val appName: String = BackupWireCodec.APP_NAME,
    val exportedAt: Long,
    val favorites: List<BackupFavoriteWire> = emptyList(),
    val history: List<BackupHistoryWire> = emptyList(),
    val readEpisodes: List<BackupEpisodeWire> = emptyList(),
    val readerSettings: List<BackupReaderSettingWire> = emptyList(),
)

data class BackupFavoriteWire(
    val sourceId: String?,
    val id: String?,
    val toonId: String? = null,
    val title: String?,
    val thumbUrl: String,
    val href: String,
    val genre: String,
    val updatedAt: String?,
    val savedAt: Long,
)

data class BackupHistoryWire(
    val sourceId: String?,
    val toonId: String?,
    val id: String? = null,
    val toonTitle: String?,
    val toonThumbUrl: String,
    val toonHref: String,
    val lastWrId: String?,
    val lastEpisodeTitle: String?,
    val lastEpisodeHref: String,
    val lastReadOrder: Int,
    val totalEpisodes: Int,
    val lastReadAt: Long,
    val nextWrId: String?,
    val nextEpisodeTitle: String?,
    val nextEpisodeHref: String?,
    val hasNew: Boolean,
)

data class BackupEpisodeWire(
    val sourceId: String?,
    val toonId: String?,
    val id: String? = null,
    val wrId: String?,
    val readAt: Long,
    val lastPage: Int,
)

data class BackupReaderSettingWire(
    val sourceId: String?,
    val toonId: String?,
    val id: String? = null,
    val viewMode: String,
    val readDirection: String,
    val splitMode: String,
    val updatedAt: Long,
)

object BackupWireCodec {
    const val VERSION = 2
    const val APP_NAME = "Comics8"

    fun encode(backup: BackupWire): String = JSONObject().apply {
        put("version", backup.version)
        put("appName", backup.appName)
        put("exportedAt", backup.exportedAt)
        put("favorites", JSONArray().apply { backup.favorites.forEach { put(it.toJson()) } })
        put("history", JSONArray().apply { backup.history.forEach { put(it.toJson()) } })
        put("readEpisodes", JSONArray().apply { backup.readEpisodes.forEach { put(it.toJson()) } })
        put("readerSettings", JSONArray().apply { backup.readerSettings.forEach { put(it.toJson()) } })
    }.toString(2)

    fun decode(jsonString: String, nowMillis: () -> Long = System::currentTimeMillis): BackupWire {
        val root = JSONObject(jsonString)
        return BackupWire(
            version = root.optInt("version", 0),
            appName = root.optString("appName", ""),
            exportedAt = root.optLong("exportedAt", 0L),
            favorites = root.objects("favorites").map { obj ->
                BackupFavoriteWire(
                    sourceId = obj.optionalString("sourceId"),
                    id = obj.optionalString("id"),
                    toonId = obj.optionalString("toonId"),
                    title = obj.optionalString("title"),
                    thumbUrl = obj.optString("thumbUrl", ""),
                    href = obj.optString("href", ""),
                    genre = obj.optString("genre", ""),
                    updatedAt = obj.optionalString("updatedAt"),
                    savedAt = obj.optLong("savedAt", nowMillis()),
                )
            },
            history = root.objects("history").map { obj ->
                BackupHistoryWire(
                    sourceId = obj.optionalString("sourceId"),
                    toonId = obj.optionalString("toonId"),
                    id = obj.optionalString("id"),
                    toonTitle = obj.optionalString("toonTitle"),
                    toonThumbUrl = obj.optString("toonThumbUrl", ""),
                    toonHref = obj.optString("toonHref", ""),
                    lastWrId = obj.optionalString("lastWrId"),
                    lastEpisodeTitle = obj.optionalString("lastEpisodeTitle"),
                    lastEpisodeHref = obj.optString("lastEpisodeHref", ""),
                    lastReadOrder = obj.optInt("lastReadOrder", 1),
                    totalEpisodes = obj.optInt("totalEpisodes", 1),
                    lastReadAt = obj.optLong("lastReadAt", nowMillis()),
                    nextWrId = obj.optionalString("nextWrId"),
                    nextEpisodeTitle = obj.optionalString("nextEpisodeTitle"),
                    nextEpisodeHref = obj.optionalString("nextEpisodeHref"),
                    hasNew = obj.optBoolean("hasNew", false),
                )
            },
            readEpisodes = root.objects("readEpisodes").map { obj ->
                BackupEpisodeWire(
                    sourceId = obj.optionalString("sourceId"),
                    toonId = obj.optionalString("toonId"),
                    id = obj.optionalString("id"),
                    wrId = obj.optionalString("wrId"),
                    readAt = obj.optLong("readAt", nowMillis()),
                    lastPage = obj.optInt("lastPage", 0),
                )
            },
            readerSettings = root.objects("readerSettings").map { obj ->
                BackupReaderSettingWire(
                    sourceId = obj.optionalString("sourceId"),
                    toonId = obj.optionalString("toonId"),
                    id = obj.optionalString("id"),
                    viewMode = obj.optString("viewMode", "SINGLE"),
                    readDirection = obj.optString("readDirection", "RIGHT_TO_LEFT"),
                    splitMode = obj.optString("splitMode", "FIT"),
                    updatedAt = obj.optLong("updatedAt", nowMillis()),
                )
            },
        )
    }

    fun workId(sourceId: String?, id: String?, toonId: String?, preferId: Boolean): WorkId? =
        SyncWire.workId(
            JSONObject().apply {
                sourceId?.let { put("sourceId", it) }
                id?.let { put("id", it) }
                toonId?.let { put("toonId", it) }
            },
            preferId,
        )

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: JSONArray()
        return List(array.length()) { array.getJSONObject(it) }
    }

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun BackupFavoriteWire.toJson() = JSONObject().apply {
        put("id", id ?: toonId.orEmpty())
        put("sourceId", sourceId.orEmpty())
        put("title", title.orEmpty())
        put("thumbUrl", thumbUrl)
        put("href", href)
        put("genre", genre)
        put("updatedAt", updatedAt ?: JSONObject.NULL)
        put("savedAt", savedAt)
    }

    private fun BackupHistoryWire.toJson() = JSONObject().apply {
        put("toonId", toonId ?: id.orEmpty())
        put("sourceId", sourceId.orEmpty())
        put("toonTitle", toonTitle.orEmpty())
        put("toonThumbUrl", toonThumbUrl)
        put("toonHref", toonHref)
        put("lastWrId", lastWrId.orEmpty())
        put("lastEpisodeTitle", lastEpisodeTitle.orEmpty())
        put("lastEpisodeHref", lastEpisodeHref)
        put("lastReadOrder", lastReadOrder)
        put("totalEpisodes", totalEpisodes)
        put("lastReadAt", lastReadAt)
        put("nextWrId", nextWrId ?: JSONObject.NULL)
        put("nextEpisodeTitle", nextEpisodeTitle ?: JSONObject.NULL)
        put("nextEpisodeHref", nextEpisodeHref ?: JSONObject.NULL)
        put("hasNew", hasNew)
    }

    private fun BackupEpisodeWire.toJson() = JSONObject().apply {
        put("toonId", toonId ?: id.orEmpty())
        put("sourceId", sourceId.orEmpty())
        put("wrId", wrId.orEmpty())
        put("readAt", readAt)
        put("lastPage", lastPage)
    }

    private fun BackupReaderSettingWire.toJson() = JSONObject().apply {
        put("toonId", toonId ?: id.orEmpty())
        put("sourceId", sourceId.orEmpty())
        put("viewMode", viewMode)
        put("readDirection", readDirection)
        put("splitMode", splitMode)
        put("updatedAt", updatedAt)
    }
}
