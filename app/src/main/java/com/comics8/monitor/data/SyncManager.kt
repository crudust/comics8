package com.comics8.monitor.data

import android.content.Context
import android.content.SharedPreferences
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.data.local.FavoriteToon
import com.comics8.monitor.data.local.ReadEpisode
import com.comics8.monitor.data.local.ReadHistory
import com.comics8.monitor.data.local.ReaderSetting
import com.comics8.monitor.data.local.Tombstone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SyncState(
    val syncKey: String = "",
    val serverUrl: String = SyncManager.DEFAULT_SERVER_URL,
    val autoSyncEnabled: Boolean = true,
    val isSyncing: Boolean = false,
    val lastSyncedAt: Long = 0L,
    val syncMessage: String? = null,
    val isSuccess: Boolean? = null,
)

data class SyncResult(
    val success: Boolean,
    val message: String,
    val favoritesCount: Int = 0,
    val historyCount: Int = 0,
)

class SyncManager(
    private val context: Context,
    private val database: AppDatabase,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("comics8_sync", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var debounceJob: Job? = null

    private val _syncState = MutableStateFlow(
        SyncState(
            syncKey = getOrGenerateSyncKey(),
            serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL,
            autoSyncEnabled = prefs.getBoolean(KEY_AUTO_SYNC, true),
            lastSyncedAt = prefs.getLong(KEY_LAST_SYNCED_AT, 0L),
        )
    )
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private fun getOrGenerateSyncKey(): String {
        var key = prefs.getString(KEY_SYNC_KEY, null)
        if (key.isNullOrBlank()) {
            key = generateSyncKey()
            prefs.edit().putString(KEY_SYNC_KEY, key).apply()
        }
        return key
    }

    fun generateNewKey(): String {
        val newKey = generateSyncKey()
        prefs.edit().putString(KEY_SYNC_KEY, newKey).apply()
        _syncState.value = _syncState.value.copy(
            syncKey = newKey,
            syncMessage = "새 동기화 키가 발급되었습니다.",
            isSuccess = true,
        )
        return newKey
    }

    fun updateSyncKey(newKey: String) {
        val trimmed = newKey.trim()
        if (trimmed.isNotBlank()) {
            prefs.edit().putString(KEY_SYNC_KEY, trimmed).apply()
            _syncState.value = _syncState.value.copy(
                syncKey = trimmed,
                syncMessage = "동기화 키가 설정되었습니다.",
                isSuccess = true,
            )
        }
    }

    fun updateServerUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotBlank()) {
            prefs.edit().putString(KEY_SERVER_URL, trimmed).apply()
            _syncState.value = _syncState.value.copy(serverUrl = trimmed)
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
        _syncState.value = _syncState.value.copy(autoSyncEnabled = enabled)
    }

    fun triggerDebouncedPush() {
        if (!_syncState.value.autoSyncEnabled) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(2500)
            syncDeltaInternal(isSilent = true)
        }
    }

    suspend fun syncPull(): SyncResult = withContext(Dispatchers.IO) {
        syncDeltaInternal(isSilent = false)
    }

    suspend fun syncPush(): SyncResult = withContext(Dispatchers.IO) {
        syncPushInternal(isSilent = false)
    }

    suspend fun syncFull(): SyncResult = withContext(Dispatchers.IO) {
        val pullRes = syncPullInternal(isSilent = true)
        if (!pullRes.success && !pullRes.message.contains("데이터가 없습니다")) {
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                isSuccess = false,
                syncMessage = pullRes.message,
            )
            return@withContext pullRes
        }
        val pushRes = syncPushInternal(isSilent = false)
        pushRes
    }

    /**
     * Incremental (Delta) Sync: Exchanges only changes since lastSyncedAt.
     */
    suspend fun syncDeltaInternal(isSilent: Boolean): SyncResult = withContext(Dispatchers.IO) {
        val state = _syncState.value
        val baseUrl = state.serverUrl.trimEnd('/')
        val deltaUrl = if (baseUrl.endsWith("/sync")) "$baseUrl/delta" else "$baseUrl/sync/delta"
        val key = state.syncKey.trim()
        if (key.isBlank()) return@withContext SyncResult(false, "동기화 키가 비어있습니다.")

        val since = prefs.getLong(KEY_LAST_SYNCED_AT, 0L)
        if (since == 0L) {
            // First time sync: perform full pull, then push local
            return@withContext syncFull()
        }

        if (!isSilent) {
            _syncState.value = _syncState.value.copy(isSyncing = true, syncMessage = null)
        }

        try {
            // 1. Gather local changes since lastSyncedAt
            val localFavs = database.favoriteDao().getSince(since)
            val localHist = database.readHistoryDao().getSince(since)
            val localEps = database.readEpisodeDao().getSince(since)
            val localSettings = database.readerSettingDao().getSince(since)
            val localTombs = database.tombstoneDao().getSince(since)

            val changesObj = JSONObject().apply {
                val favArr = JSONArray()
                localFavs.forEach { f ->
                    favArr.put(JSONObject().apply {
                        put("id", f.id)
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
                        put("hasNew", h.hasNew)
                    })
                }
                put("history", histArr)

                val epArr = JSONArray()
                localEps.forEach { ep ->
                    epArr.put(JSONObject().apply {
                        put("toonId", ep.toonId)
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
                        put("viewMode", s.viewMode)
                        put("readDirection", s.readDirection)
                        put("updatedAt", s.updatedAt)
                    })
                }
                put("readerSettings", setArr)

                val tombArr = JSONArray()
                localTombs.forEach { t ->
                    tombArr.put(JSONObject().apply {
                        put("entityType", t.entityType)
                        put("entityId", t.entityId)
                        put("deletedAt", t.deletedAt)
                    })
                }
                put("tombstones", tombArr)
            }

            val reqObj = JSONObject().apply {
                put("since", since)
                put("clientTime", System.currentTimeMillis())
                put("changes", changesObj)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val reqBody = reqObj.toString().toRequestBody(mediaType)
            val req = Request.Builder()
                .url(deltaUrl)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("X-Sync-Key", key)
                .post(reqBody)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                // Fallback to legacy full sync if delta endpoint 404
                if (resp.code == 404) {
                    return@withContext syncPullInternal(isSilent)
                }
                val err = "증분 동기화 서버 오류 (${resp.code})"
                if (!isSilent) {
                    _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
                }
                return@withContext SyncResult(false, err)
            }

            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val serverTime = json.optLong("serverTime", System.currentTimeMillis())
            val serverChanges = json.optJSONObject("changes") ?: JSONObject()

            // Apply received tombstones
            val serverTombs = serverChanges.optJSONArray("tombstones") ?: JSONArray()
            for (i in 0 until serverTombs.length()) {
                val t = serverTombs.getJSONObject(i)
                val type = t.optString("entityType", "")
                val id = t.optString("entityId", "")
                if (type == "FAVORITE") database.favoriteDao().delete(id)
                else if (type == "HISTORY") database.readHistoryDao().delete(id)
                else if (type == "EPISODE") database.readEpisodeDao().deleteByToonId(id)
            }

            // Apply received favorites
            val serverFavs = serverChanges.optJSONArray("favorites") ?: JSONArray()
            val favList = mutableListOf<FavoriteToon>()
            for (i in 0 until serverFavs.length()) {
                val f = serverFavs.getJSONObject(i)
                favList.add(
                    FavoriteToon(
                        id = f.getString("id"),
                        title = f.getString("title"),
                        thumbUrl = f.optString("thumbUrl", ""),
                        href = f.optString("href", ""),
                        genre = f.optString("genre", ""),
                        updatedAt = if (f.isNull("updatedAt")) null else f.getString("updatedAt"),
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
                histList.add(
                    ReadHistory(
                        toonId = h.getString("toonId"),
                        toonTitle = h.getString("toonTitle"),
                        toonThumbUrl = h.optString("toonThumbUrl", ""),
                        toonHref = h.optString("toonHref", ""),
                        lastWrId = h.getString("lastWrId"),
                        lastEpisodeTitle = h.getString("lastEpisodeTitle"),
                        lastEpisodeHref = h.optString("lastEpisodeHref", ""),
                        lastReadOrder = h.optInt("lastReadOrder", 1),
                        totalEpisodes = h.optInt("totalEpisodes", 1),
                        lastReadAt = h.optLong("lastReadAt", serverTime),
                        nextWrId = if (h.isNull("nextWrId")) null else h.getString("nextWrId"),
                        nextEpisodeTitle = if (h.isNull("nextEpisodeTitle")) null else h.getString("nextEpisodeTitle"),
                        nextEpisodeHref = if (h.isNull("nextEpisodeHref")) null else h.getString("nextEpisodeHref"),
                        hasNew = h.optBoolean("hasNew", false),
                    )
                )
            }
            if (histList.isNotEmpty()) database.readHistoryDao().upsertAll(histList)

            // Apply received episodes
            val serverEps = serverChanges.optJSONArray("readEpisodes") ?: JSONArray()
            val epList = mutableListOf<ReadEpisode>()
            for (i in 0 until serverEps.length()) {
                val ep = serverEps.getJSONObject(i)
                epList.add(
                    ReadEpisode(
                        toonId = ep.getString("toonId"),
                        wrId = ep.getString("wrId"),
                        readAt = ep.optLong("readAt", serverTime),
                        lastPage = ep.optInt("lastPage", 0),
                    )
                )
            }
            if (epList.isNotEmpty()) database.readEpisodeDao().markReadAll(epList)

            // Apply received settings
            val serverSettings = serverChanges.optJSONArray("readerSettings") ?: JSONArray()
            val settingList = mutableListOf<ReaderSetting>()
            for (i in 0 until serverSettings.length()) {
                val s = serverSettings.getJSONObject(i)
                settingList.add(
                    ReaderSetting(
                        toonId = s.getString("toonId"),
                        viewMode = s.optString("viewMode", "SCROLL"),
                        readDirection = s.optString("readDirection", "RIGHT_TO_LEFT"),
                        updatedAt = s.optLong("updatedAt", serverTime),
                    )
                )
            }
            if (settingList.isNotEmpty()) database.readerSettingDao().saveAll(settingList)

            // Cleanup old tombstones
            database.tombstoneDao().deleteOlderThan(System.currentTimeMillis() - 30L * 86400000L)

            prefs.edit().putLong(KEY_LAST_SYNCED_AT, serverTime).apply()

            val msg = "증분 동기화 완료: 수신 (즐겨찾기 ${favList.size}개, 기록 ${histList.size}개)"
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                isSuccess = true,
                lastSyncedAt = serverTime,
                syncMessage = msg,
            )
            return@withContext SyncResult(true, msg, favList.size, histList.size)
        } catch (e: Exception) {
            val err = "증분 동기화 실패: ${e.localizedMessage ?: "서버 연결 오류"}"
            if (!isSilent) {
                _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
            }
            return@withContext SyncResult(false, err)
        }
    }

    private suspend fun syncPullInternal(isSilent: Boolean): SyncResult {
        val state = _syncState.value
        val url = state.serverUrl.trimEnd('/')
        val key = state.syncKey.trim()
        if (key.isBlank()) return SyncResult(false, "동기화 키가 비어있습니다.")

        if (!isSilent) {
            _syncState.value = _syncState.value.copy(isSyncing = true, syncMessage = null)
        }

        try {
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("X-Sync-Key", key)
                .get()
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                val err = "서버 응답 오류 (${resp.code})"
                if (!isSilent) {
                    _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
                }
                return SyncResult(false, err)
            }

            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                val err = "빈 응답을 받았습니다."
                if (!isSilent) {
                    _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
                }
                return SyncResult(false, err)
            }

            val json = JSONObject(body)
            if (json.optBoolean("empty", false)) {
                val msg = "서버에 저장된 데이터가 없습니다."
                if (!isSilent) {
                    _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = true, syncMessage = msg)
                }
                return SyncResult(true, msg)
            }

            val restoreResult = BackupManager.restoreBackupJson(database, body)
            val nowMs = System.currentTimeMillis()
            prefs.edit().putLong(KEY_LAST_SYNCED_AT, nowMs).apply()

            val successMsg = "동기화 완료: 즐겨찾기 ${restoreResult.favoriteCount}개, 기록 ${restoreResult.historyCount}개"
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                isSuccess = true,
                lastSyncedAt = nowMs,
                syncMessage = successMsg,
            )
            return SyncResult(true, successMsg, restoreResult.favoriteCount, restoreResult.historyCount)
        } catch (e: Exception) {
            val err = "동기화 실패: ${e.localizedMessage ?: "서버 연결 오류"}"
            if (!isSilent) {
                _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
            }
            return SyncResult(false, err)
        }
    }

    private suspend fun syncPushInternal(isSilent: Boolean): SyncResult {
        val state = _syncState.value
        val url = state.serverUrl.trimEnd('/')
        val key = state.syncKey.trim()
        if (key.isBlank()) return SyncResult(false, "동기화 키가 비어있습니다.")

        if (!isSilent) {
            _syncState.value = _syncState.value.copy(isSyncing = true, syncMessage = null)
        }

        try {
            val backupJson = BackupManager.createBackupJson(database)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val reqBody = backupJson.toRequestBody(mediaType)

            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("X-Sync-Key", key)
                .post(reqBody)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                val err = "서버 업로드 오류 (${resp.code})"
                if (!isSilent) {
                    _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
                }
                return SyncResult(false, err)
            }

            val nowMs = System.currentTimeMillis()
            prefs.edit().putLong(KEY_LAST_SYNCED_AT, nowMs).apply()

            val stats = BackupManager.getStats(database)
            val successMsg = "서버 동기화 완료: 즐겨찾기 ${stats.favoriteCount}개, 기록 ${stats.historyCount}개"
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                isSuccess = true,
                lastSyncedAt = nowMs,
                syncMessage = successMsg,
            )
            return SyncResult(true, successMsg, stats.favoriteCount, stats.historyCount)
        } catch (e: Exception) {
            val err = "업로드 실패: ${e.localizedMessage ?: "서버 연결 오류"}"
            if (!isSilent) {
                _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
            }
            return SyncResult(false, err)
        }
    }

    companion object {
        const val KEY_SYNC_KEY = com.comics8.core.sync.SyncConstants.KEY_SYNC_KEY
        const val KEY_SERVER_URL = com.comics8.core.sync.SyncConstants.KEY_SERVER_URL
        const val KEY_AUTO_SYNC = com.comics8.core.sync.SyncConstants.KEY_AUTO_SYNC
        const val KEY_LAST_SYNCED_AT = com.comics8.core.sync.SyncConstants.KEY_LAST_SYNCED_AT
        const val DEFAULT_SERVER_URL = com.comics8.core.sync.SyncConstants.DEFAULT_SERVER_URL

        fun generateSyncKey(): String = com.comics8.core.sync.SyncConstants.generateSyncKey()
    }
}
