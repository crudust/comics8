package com.comics8.monitor.data

import android.content.Context
import android.content.SharedPreferences
import com.comics8.core.model.SyncResult
import com.comics8.core.model.SyncState
import com.comics8.core.network.ToonClient
import com.comics8.core.source.WorkId
import com.comics8.core.sync.BaseSyncManager
import com.comics8.core.sync.SyncConstants
import com.comics8.core.sync.SyncStorageAdapter
import com.comics8.core.sync.SyncWire
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.data.local.FavoriteToon
import com.comics8.monitor.data.local.ReadEpisode
import com.comics8.monitor.data.local.ReadHistory
import com.comics8.monitor.data.local.ReaderSetting
import com.comics8.monitor.data.local.delete
import com.comics8.monitor.data.local.deleteByToonId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

typealias AndroidSyncState = SyncState
typealias AndroidSyncResult = SyncResult

class AndroidRoomSyncStorageAdapter(
    private val context: Context,
    private val database: AppDatabase,
) : SyncStorageAdapter {

    private val prefs: SharedPreferences = context.getSharedPreferences("comics8_sync", Context.MODE_PRIVATE)

    override fun getPreference(key: String, defaultValue: String?): String? {
        return prefs.getString(key, defaultValue)
    }

    override fun setPreference(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, value).apply()
        }
    }

    override suspend fun deleteOldTombstones(cutoff: Long) {
        database.tombstoneDao().deleteOlderThan(cutoff)
    }

    override suspend fun getLocalChangesSince(since: Long): JSONObject {
        val localFavs = database.favoriteDao().getSince(since).filter { SyncWire.isSyncableSource(it.sourceId) }
        val localHist = database.readHistoryDao().getSince(since).filter { SyncWire.isSyncableSource(it.sourceId) }
        val localEps = database.readEpisodeDao().getSince(since).filter { SyncWire.isSyncableSource(it.sourceId) }
        val localSettings = database.readerSettingDao().getSince(since).filter { SyncWire.isSyncableSource(it.sourceId) }
        val localTombs = database.tombstoneDao().getSince(since).filter { SyncWire.isSyncableSource(WorkId.parse(it.entityId).sourceId) }

        return JSONObject().apply {
            val favArr = JSONArray()
            localFavs.forEach { f ->
                favArr.put(JSONObject().apply {
                    put("id", f.id)
                    put("sourceId", f.sourceId)
                    put("title", f.title)
                    put("thumbUrl", f.thumbUrl)
                    put("href", f.href)
                    put("genre", f.genre)
                    put("updatedAt", f.updatedAt ?: JSONObject.NULL)
                    put("savedAt", f.savedAt)
                })
            }
            put("favorites", favArr)

            val histArr = JSONArray()
            localHist.forEach { h ->
                histArr.put(JSONObject().apply {
                    put("toonId", h.toonId)
                    put("sourceId", h.sourceId)
                    put("toonTitle", h.toonTitle)
                    put("toonThumbUrl", h.toonThumbUrl)
                    put("toonHref", h.toonHref)
                    put("lastWrId", h.lastWrId)
                    put("lastEpisodeTitle", h.lastEpisodeTitle)
                    put("lastEpisodeHref", h.lastEpisodeHref)
                    put("lastReadOrder", h.lastReadOrder)
                    put("totalEpisodes", h.totalEpisodes)
                    put("lastReadAt", h.lastReadAt)
                    put("nextWrId", h.nextWrId ?: JSONObject.NULL)
                    put("nextEpisodeTitle", h.nextEpisodeTitle ?: JSONObject.NULL)
                    put("nextEpisodeHref", h.nextEpisodeHref ?: JSONObject.NULL)
                    put("hasNew", if (h.hasNew) 1 else 0)
                })
            }
            put("history", histArr)

            val epArr = JSONArray()
            localEps.forEach { ep ->
                epArr.put(JSONObject().apply {
                    put("toonId", ep.toonId)
                    put("sourceId", ep.sourceId)
                    put("wrId", ep.wrId)
                    put("readAt", ep.readAt)
                    put("lastPage", ep.lastPage)
                })
            }
            put("readEpisodes", epArr)

            val setArr = JSONArray()
            localSettings.forEach { s ->
                setArr.put(JSONObject().apply {
                    put("toonId", s.toonId)
                    put("sourceId", s.sourceId)
                    put("viewMode", s.viewMode)
                    put("readDirection", s.readDirection)
                    put("splitMode", s.splitMode)
                    put("updatedAt", s.updatedAt)
                })
            }
            put("readerSettings", setArr)

            val tombArr = JSONArray()
            localTombs.forEach { t ->
                tombArr.put(JSONObject().apply {
                    put("entityType", t.entityType)
                    put("entityId", SyncWire.tombstoneEntityId(t.entityId))
                    put("deletedAt", t.deletedAt)
                })
            }
            put("tombstones", tombArr)
        }
    }

    override suspend fun getFullSnapshot(): JSONObject {
        val favs = database.favoriteDao().all().filter { SyncWire.isSyncableSource(it.sourceId) }
        val hist = database.readHistoryDao().all().filter { SyncWire.isSyncableSource(it.sourceId) }
        val eps = database.readEpisodeDao().all().filter { SyncWire.isSyncableSource(it.sourceId) }
        val settings = database.readerSettingDao().all().filter { SyncWire.isSyncableSource(it.sourceId) }

        return JSONObject().apply {
            put("version", 2)
            put("exportedAt", System.currentTimeMillis())
            put("appName", "Comics8")

            val favArr = JSONArray()
            favs.forEach { f ->
                favArr.put(JSONObject().apply {
                    put("id", f.id)
                    put("sourceId", f.sourceId)
                    put("title", f.title)
                    put("thumbUrl", f.thumbUrl)
                    put("href", f.href)
                    put("genre", f.genre)
                    put("updatedAt", f.updatedAt ?: JSONObject.NULL)
                    put("savedAt", f.savedAt)
                })
            }
            put("favorites", favArr)

            val histArr = JSONArray()
            hist.forEach { h ->
                histArr.put(JSONObject().apply {
                    put("toonId", h.toonId)
                    put("sourceId", h.sourceId)
                    put("toonTitle", h.toonTitle)
                    put("toonThumbUrl", h.toonThumbUrl)
                    put("toonHref", h.toonHref)
                    put("lastWrId", h.lastWrId)
                    put("lastEpisodeTitle", h.lastEpisodeTitle)
                    put("lastEpisodeHref", h.lastEpisodeHref)
                    put("lastReadOrder", h.lastReadOrder)
                    put("totalEpisodes", h.totalEpisodes)
                    put("lastReadAt", h.lastReadAt)
                    put("nextWrId", h.nextWrId ?: JSONObject.NULL)
                    put("nextEpisodeTitle", h.nextEpisodeTitle ?: JSONObject.NULL)
                    put("nextEpisodeHref", h.nextEpisodeHref ?: JSONObject.NULL)
                    put("hasNew", if (h.hasNew) 1 else 0)
                })
            }
            put("history", histArr)

            val epArr = JSONArray()
            eps.forEach { ep ->
                epArr.put(JSONObject().apply {
                    put("toonId", ep.toonId)
                    put("sourceId", ep.sourceId)
                    put("wrId", ep.wrId)
                    put("readAt", ep.readAt)
                    put("lastPage", ep.lastPage)
                })
            }
            put("readEpisodes", epArr)

            val setArr = JSONArray()
            settings.forEach { s ->
                setArr.put(JSONObject().apply {
                    put("toonId", s.toonId)
                    put("sourceId", s.sourceId)
                    put("viewMode", s.viewMode)
                    put("readDirection", s.readDirection)
                    put("splitMode", s.splitMode)
                    put("updatedAt", s.updatedAt)
                })
            }
            put("readerSettings", setArr)
        }
    }

    override suspend fun applyRemoteChanges(serverChanges: JSONObject, serverTime: Long): Pair<Int, Int> {
        // Apply received tombstones
        val serverTombs = serverChanges.optJSONArray("tombstones") ?: JSONArray()
        for (i in 0 until serverTombs.length()) {
            val t = serverTombs.getJSONObject(i)
            val type = t.optString("entityType", "")
            val id = t.optString("entityId", "")
            val workId = WorkId.parse(id)
            when (type) {
                "FAVORITE" -> database.favoriteDao().delete(workId)
                "HISTORY" -> database.readHistoryDao().delete(workId)
                "EPISODE" -> database.readEpisodeDao().deleteByToonId(workId)
            }
        }

        // Apply received favorites
        val serverFavs = serverChanges.optJSONArray("favorites") ?: JSONArray()
        val favList = mutableListOf<FavoriteToon>()
        for (i in 0 until serverFavs.length()) {
            val f = serverFavs.getJSONObject(i)
            val workId = BackupJson.workId(f, preferId = true) ?: continue
            favList.add(
                FavoriteToon(
                    sourceId = workId.sourceId,
                    id = workId.toonId,
                    title = f.getString("title"),
                    thumbUrl = f.optString("thumbUrl", ""),
                    href = f.optString("href", ""),
                    genre = f.optString("genre", ""),
                    updatedAt = if (f.isNull("updatedAt")) null else f.optString("updatedAt", "").ifBlank { null },
                    savedAt = f.optLong("savedAt", serverTime),
                )
            )
        }
        if (favList.isNotEmpty()) database.favoriteDao().upsertAll(favList)

        // Apply received history
        val serverHist = serverChanges.optJSONArray("history") ?: JSONArray()
        val histList = mutableListOf<ReadHistory>()
        for (i in 0 until serverHist.length()) {
            val h = serverHist.getJSONObject(i)
            val workId = BackupJson.workId(h, preferId = false) ?: continue
            histList.add(
                ReadHistory(
                    sourceId = workId.sourceId,
                    toonId = workId.toonId,
                    toonTitle = h.getString("toonTitle"),
                    toonThumbUrl = h.optString("toonThumbUrl", ""),
                    toonHref = h.optString("toonHref", ""),
                    lastWrId = h.getString("lastWrId"),
                    lastEpisodeTitle = h.optString("lastEpisodeTitle", ""),
                    lastEpisodeHref = h.optString("lastEpisodeHref", ""),
                    lastReadOrder = h.optInt("lastReadOrder", 0),
                    totalEpisodes = h.optInt("totalEpisodes", 0),
                    lastReadAt = h.optLong("lastReadAt", serverTime),
                    nextWrId = if (h.isNull("nextWrId")) null else h.optString("nextWrId", "").ifBlank { null },
                    nextEpisodeTitle = if (h.isNull("nextEpisodeTitle")) null else h.optString("nextEpisodeTitle", "").ifBlank { null },
                    nextEpisodeHref = if (h.isNull("nextEpisodeHref")) null else h.optString("nextEpisodeHref", "").ifBlank { null },
                    hasNew = h.optInt("hasNew", 0) == 1,
                )
            )
        }
        if (histList.isNotEmpty()) database.readHistoryDao().upsertAll(histList)

        // Apply received read episodes
        val serverEps = serverChanges.optJSONArray("readEpisodes") ?: JSONArray()
        val epList = mutableListOf<ReadEpisode>()
        for (i in 0 until serverEps.length()) {
            val ep = serverEps.getJSONObject(i)
            val workId = BackupJson.workId(ep, preferId = false) ?: continue
            epList.add(
                ReadEpisode(
                    sourceId = workId.sourceId,
                    toonId = workId.toonId,
                    wrId = ep.getString("wrId"),
                    readAt = ep.optLong("readAt", serverTime),
                    lastPage = ep.optInt("lastPage", 0),
                )
            )
        }
        if (epList.isNotEmpty()) database.readEpisodeDao().markReadAll(epList)

        // Apply received reader settings
        val serverSettings = serverChanges.optJSONArray("readerSettings") ?: JSONArray()
        val setList = mutableListOf<ReaderSetting>()
        for (i in 0 until serverSettings.length()) {
            val s = serverSettings.getJSONObject(i)
            val workId = BackupJson.workId(s, preferId = false) ?: continue
            setList.add(
                ReaderSetting(
                    sourceId = workId.sourceId,
                    toonId = workId.toonId,
                    viewMode = s.optString("viewMode", "SCROLL"),
                    readDirection = s.optString("readDirection", "TOP_TO_BOTTOM"),
                    splitMode = s.optString("splitMode", "NONE"),
                    updatedAt = s.optLong("updatedAt", serverTime),
                )
            )
        }
        if (setList.isNotEmpty()) database.readerSettingDao().saveAll(setList)

        return Pair(favList.size, histList.size)
    }

    override suspend fun applyFullSnapshot(snapshot: JSONObject, serverTime: Long): Pair<Int, Int> {
        val favs = snapshot.optJSONArray("favorites") ?: JSONArray()
        val hist = snapshot.optJSONArray("history") ?: JSONArray()
        val eps = snapshot.optJSONArray("readEpisodes") ?: JSONArray()
        val settings = snapshot.optJSONArray("readerSettings") ?: JSONArray()

        val favList = mutableListOf<FavoriteToon>()
        for (i in 0 until favs.length()) {
            val f = favs.getJSONObject(i)
            val workId = BackupJson.workId(f, preferId = true) ?: continue
            favList.add(
                FavoriteToon(
                    sourceId = workId.sourceId,
                    id = workId.toonId,
                    title = f.getString("title"),
                    thumbUrl = f.optString("thumbUrl", ""),
                    href = f.optString("href", ""),
                    genre = f.optString("genre", ""),
                    updatedAt = if (f.isNull("updatedAt")) null else f.optString("updatedAt", "").ifBlank { null },
                    savedAt = f.optLong("savedAt", serverTime),
                )
            )
        }
        if (favList.isNotEmpty()) database.favoriteDao().upsertAll(favList)

        val histList = mutableListOf<ReadHistory>()
        for (i in 0 until hist.length()) {
            val h = hist.getJSONObject(i)
            val workId = BackupJson.workId(h, preferId = false) ?: continue
            histList.add(
                ReadHistory(
                    sourceId = workId.sourceId,
                    toonId = workId.toonId,
                    toonTitle = h.getString("toonTitle"),
                    toonThumbUrl = h.optString("toonThumbUrl", ""),
                    toonHref = h.optString("toonHref", ""),
                    lastWrId = h.getString("lastWrId"),
                    lastEpisodeTitle = h.optString("lastEpisodeTitle", ""),
                    lastEpisodeHref = h.optString("lastEpisodeHref", ""),
                    lastReadOrder = h.optInt("lastReadOrder", 0),
                    totalEpisodes = h.optInt("totalEpisodes", 0),
                    lastReadAt = h.optLong("lastReadAt", serverTime),
                    nextWrId = if (h.isNull("nextWrId")) null else h.optString("nextWrId", "").ifBlank { null },
                    nextEpisodeTitle = if (h.isNull("nextEpisodeTitle")) null else h.optString("nextEpisodeTitle", "").ifBlank { null },
                    nextEpisodeHref = if (h.isNull("nextEpisodeHref")) null else h.optString("nextEpisodeHref", "").ifBlank { null },
                    hasNew = h.optInt("hasNew", 0) == 1,
                )
            )
        }
        if (histList.isNotEmpty()) database.readHistoryDao().upsertAll(histList)

        val epList = mutableListOf<ReadEpisode>()
        for (i in 0 until eps.length()) {
            val ep = eps.getJSONObject(i)
            val workId = BackupJson.workId(ep, preferId = false) ?: continue
            epList.add(
                ReadEpisode(
                    sourceId = workId.sourceId,
                    toonId = workId.toonId,
                    wrId = ep.getString("wrId"),
                    readAt = ep.optLong("readAt", serverTime),
                    lastPage = ep.optInt("lastPage", 0),
                )
            )
        }
        if (epList.isNotEmpty()) database.readEpisodeDao().markReadAll(epList)

        val setList = mutableListOf<ReaderSetting>()
        for (i in 0 until settings.length()) {
            val s = settings.getJSONObject(i)
            val workId = BackupJson.workId(s, preferId = false) ?: continue
            setList.add(
                ReaderSetting(
                    sourceId = workId.sourceId,
                    toonId = workId.toonId,
                    viewMode = s.optString("viewMode", "SCROLL"),
                    readDirection = s.optString("readDirection", "TOP_TO_BOTTOM"),
                    splitMode = s.optString("splitMode", "NONE"),
                    updatedAt = s.optLong("updatedAt", serverTime),
                )
            )
        }
        if (setList.isNotEmpty()) database.readerSettingDao().saveAll(setList)

        return Pair(favList.size, histList.size)
    }
}

class SyncManager(
    context: Context,
    database: AppDatabase,
    toonClient: ToonClient? = null,
    client: OkHttpClient = OkHttpClient.Builder()
        .dns(com.comics8.core.network.FallbackDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) : BaseSyncManager(
    storage = AndroidRoomSyncStorageAdapter(context, database),
    toonClient = toonClient,
    client = client,
) {
    companion object {
        const val DEFAULT_SERVER_URL = SyncConstants.DEFAULT_SERVER_URL
        const val KEY_SYNC_KEY = SyncConstants.KEY_SYNC_KEY
        const val KEY_SERVER_URL = SyncConstants.KEY_SERVER_URL
        const val KEY_AUTO_SYNC = SyncConstants.KEY_AUTO_SYNC
        const val KEY_LAST_SYNCED_AT = SyncConstants.KEY_LAST_SYNCED_AT
        const val KEY_USE_SERVER_PROXY = SyncConstants.KEY_USE_SERVER_PROXY

        fun generateSyncKey(): String = SyncConstants.generateSyncKey()
    }
}
