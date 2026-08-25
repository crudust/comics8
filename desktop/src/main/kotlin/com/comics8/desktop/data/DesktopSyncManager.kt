package com.comics8.desktop.data

import com.comics8.core.network.ToonClient
import com.comics8.core.source.WorkId
import com.comics8.core.sync.SyncConstants
import com.comics8.core.sync.SyncWire
import com.comics8.core.sync.addComics8SyncHeaders
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
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class DesktopSyncState(
    val syncKey: String = "",
    val serverUrl: String = DesktopSyncManager.DEFAULT_SERVER_URL,
    val autoSyncEnabled: Boolean = true,
    val useServerProxy: Boolean = true,
    val isSyncing: Boolean = false,
    val lastSyncedAt: Long = 0L,
    val syncMessage: String? = null,
    val isSuccess: Boolean? = null,
)

data class DesktopSyncResult(
    val success: Boolean,
    val message: String,
    val favoritesCount: Int = 0,
    val historyCount: Int = 0,
)

class DesktopSyncManager(
    private val database: DesktopDatabase,
    private val toonClient: ToonClient? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(com.comics8.core.network.FallbackDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {

    private val configFile = File(System.getProperty("user.home"), ".comics8/sync.properties")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var debounceJob: Job? = null
    private val inFlight = AtomicBoolean(false)

    private suspend fun <T> exclusiveSync(busy: T, block: suspend () -> T): T {
        if (!inFlight.compareAndSet(false, true)) return busy
        return try {
            block()
        } finally {
            inFlight.set(false)
        }
    }

    private val properties = Properties().apply {
        if (configFile.exists()) {
            try {
                configFile.inputStream().use { load(it) }
            } catch (_: Exception) {}
        }
    }

    private val _syncState = MutableStateFlow(
        DesktopSyncState(
            syncKey = getOrGenerateSyncKey(),
            serverUrl = SyncConstants.DEFAULT_SERVER_URL,
            autoSyncEnabled = properties.getProperty(KEY_AUTO_SYNC, "true").toBoolean(),
            useServerProxy = properties.getProperty(KEY_USE_SERVER_PROXY, "true").toBoolean(),
            lastSyncedAt = properties.getProperty(KEY_LAST_SYNCED_AT, "0").toLongOrNull() ?: 0L,
        )
    )
    val syncState: StateFlow<DesktopSyncState> = _syncState.asStateFlow()

    init {
        val serverUrl = _syncState.value.serverUrl
        val useProxy = _syncState.value.useServerProxy
        toonClient?.proxyBaseUrl = SyncConstants.proxyBaseUrl(serverUrl)
        toonClient?.isProxyEnabled = useProxy
    }

    private fun saveProperties() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.outputStream().use { properties.store(it, "Comics8 Sync Configuration") }
        } catch (_: Exception) {}
    }

    private fun getOrGenerateSyncKey(): String {
        var key = properties.getProperty(KEY_SYNC_KEY)
        if (key.isNullOrBlank()) {
            key = generateSyncKey()
            properties.setProperty(KEY_SYNC_KEY, key)
            saveProperties()
        }
        return key
    }

    fun generateNewKey(): String {
        val newKey = generateSyncKey()
        properties.setProperty(KEY_SYNC_KEY, newKey)
        saveProperties()
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
            properties.setProperty(KEY_SYNC_KEY, trimmed)
            saveProperties()
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
            properties.setProperty(KEY_SERVER_URL, trimmed)
            saveProperties()
            toonClient?.proxyBaseUrl = SyncConstants.proxyBaseUrl(trimmed)
            _syncState.value = _syncState.value.copy(serverUrl = trimmed)
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        properties.setProperty(KEY_AUTO_SYNC, enabled.toString())
        saveProperties()
        _syncState.value = _syncState.value.copy(autoSyncEnabled = enabled)
    }

    fun setUseServerProxy(enabled: Boolean) {
        properties.setProperty(KEY_USE_SERVER_PROXY, enabled.toString())
        saveProperties()
        toonClient?.isProxyEnabled = enabled
        _syncState.value = _syncState.value.copy(useServerProxy = enabled)
    }

    suspend fun requestPairingCode(): com.comics8.core.model.PairRequestResult = withContext(Dispatchers.IO) {
        val state = _syncState.value
        val key = state.syncKey.trim()
        if (key.isBlank()) return@withContext com.comics8.core.model.PairRequestResult(false, message = "동기화 키가 없습니다.")
        val pairUrl = SyncConstants.pairRequestUrl(state.serverUrl)
        try {
            val reqBody = JSONObject().apply {
                put("clientTime", System.currentTimeMillis())
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url(pairUrl)
                .addComics8SyncHeaders(key)
                .post(reqBody)
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                return@withContext com.comics8.core.model.PairRequestResult(
                    success = false,
                    message = if (resp.code == 404) "서버에 페어링 기능이 지원되지 않습니다. 마스터 복구 키를 이용해주세요." else "페어링 코드 발급 실패 (${resp.code})"
                )
            }
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val code = json.optString("code", "")
            val expiresIn = json.optInt("expiresIn", 300)
            if (code.isBlank()) {
                return@withContext com.comics8.core.model.PairRequestResult(false, message = "서버 응답 오류")
            }
            com.comics8.core.model.PairRequestResult(true, code = code, expiresInSeconds = expiresIn)
        } catch (e: Exception) {
            com.comics8.core.model.PairRequestResult(false, message = e.localizedMessage ?: "서버 연결 오류")
        }
    }

    suspend fun confirmPairingCode(code: String): com.comics8.core.model.PairConfirmResult = withContext(Dispatchers.IO) {
        val trimmedCode = code.trim().replace(" ", "").replace("-", "")
        if (trimmedCode.length != 6) {
            return@withContext com.comics8.core.model.PairConfirmResult(false, message = "6자리 코드를 입력해주세요.")
        }
        val state = _syncState.value
        val confirmUrl = SyncConstants.pairConfirmUrl(state.serverUrl)
        try {
            val reqBody = JSONObject().apply {
                put("code", trimmedCode)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url(confirmUrl)
                .post(reqBody)
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                return@withContext com.comics8.core.model.PairConfirmResult(
                    success = false,
                    message = if (resp.code == 404) "유효하지 않거나 만료된 코드입니다." else "인증 실패 (${resp.code})"
                )
            }
            val body = resp.body?.string().orEmpty()
            val json = JSONObject(body)
            val syncKey = json.optString("syncKey", "")
            if (syncKey.isBlank()) {
                return@withContext com.comics8.core.model.PairConfirmResult(false, message = json.optString("message", "인증 실패"))
            }
            updateSyncKey(syncKey)
            // Immediately perform full sync to restore existing data
            syncPull()
            com.comics8.core.model.PairConfirmResult(true, syncKey = syncKey, message = "기기 연결 및 데이터 가져오기 완료")
        } catch (e: Exception) {
            com.comics8.core.model.PairConfirmResult(false, message = e.localizedMessage ?: "서버 연결 오류")
        }
    }

    fun triggerDebouncedPush() {
        if (!_syncState.value.autoSyncEnabled) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(2500)
            exclusiveSync(Unit) {
                syncDeltaInternal(isSilent = true)
                Unit
            }
        }
    }

    suspend fun syncPull(): DesktopSyncResult = withContext(Dispatchers.IO) {
        exclusiveSync(DesktopSyncResult(false, "이미 동기화 중입니다.")) {
            syncDeltaInternal(isSilent = false)
        }
    }

    suspend fun syncPush(): DesktopSyncResult = withContext(Dispatchers.IO) {
        exclusiveSync(DesktopSyncResult(false, "이미 동기화 중입니다.")) {
            syncPushInternal(isSilent = false)
        }
    }

    suspend fun syncFull(): DesktopSyncResult = withContext(Dispatchers.IO) {
        exclusiveSync(DesktopSyncResult(false, "이미 동기화 중입니다.")) {
            doFullSync()
        }
    }

    /** Silent delta if no other sync is running; null when skipped. */
    suspend fun syncIfIdle(): DesktopSyncResult? = withContext(Dispatchers.IO) {
        exclusiveSync(null) {
            syncDeltaInternal(isSilent = true)
        }
    }

    private suspend fun doFullSync(): DesktopSyncResult {
        val pullRes = syncPullInternal(isSilent = true)
        if (!pullRes.success && !pullRes.message.contains("데이터가 없습니다")) {
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                isSuccess = false,
                syncMessage = pullRes.message,
            )
            return pullRes
        }
        return syncPushInternal(isSilent = false)
    }

    /**
     * Incremental (Delta) Sync: Exchanges only changes since lastSyncedAt.
     */
    suspend fun syncDeltaInternal(isSilent: Boolean): DesktopSyncResult = withContext(Dispatchers.IO) {
        val state = _syncState.value
        val baseUrl = state.serverUrl.trimEnd('/')
        val deltaUrl = if (baseUrl.endsWith("/sync")) "$baseUrl/delta" else "$baseUrl/sync/delta"
        val key = state.syncKey.trim()
        if (key.isBlank()) return@withContext DesktopSyncResult(false, "동기화 키가 비어있습니다.")

        val since = properties.getProperty(KEY_LAST_SYNCED_AT, "0").toLongOrNull() ?: 0L
        if (since == 0L) {
            // First time sync: perform full sync
            return@withContext doFullSync()
        }

        if (!isSilent) {
            _syncState.value = _syncState.value.copy(isSyncing = true, syncMessage = null)
        }

        try {
            val localFavs = database.getFavoritesSince(since).filter { SyncWire.isSyncableSource(it.sourceId) }
            val localHist = database.getHistorySince(since).filter { SyncWire.isSyncableSource(it.sourceId) }
            val localEps = database.getReadEpisodesSince(since).filter { SyncWire.isSyncableSource(it.sourceId) }
            val localSettings = database.getReaderSettingsSince(since).filter { SyncWire.isSyncableSource(it.sourceId) }
            val localTombs = database.getTombstonesSince(since).filter { SyncWire.isSyncableSource(WorkId.parse(it.entityId).sourceId) }

            val changesObj = JSONObject().apply {
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
                        put("hasNew", h.hasNew)
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

            val reqObj = JSONObject().apply {
                put("since", since)
                put("clientTime", System.currentTimeMillis())
                put("version", 2)
                put("changes", changesObj)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val reqBody = reqObj.toString().toRequestBody(mediaType)
            val req = Request.Builder()
                .url(deltaUrl)
                .addComics8SyncHeaders(key)
                .post(reqBody)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                if (resp.code == 404) {
                    return@withContext syncPullInternal(isSilent)
                }
                val err = "증분 동기화 서버 오류 (${resp.code})"
                if (!isSilent) {
                    _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
                }
                return@withContext DesktopSyncResult(false, err)
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
                val workId = WorkId.parse(id)
                if (type == "FAVORITE") database.deleteFavorite(workId)
                else if (type == "HISTORY") database.deleteHistory(workId)
                else if (type == "EPISODE") database.deleteReadEpisodesByToon(workId)
            }

            // Apply received favorites
            val serverFavs = serverChanges.optJSONArray("favorites") ?: JSONArray()
            val favList = mutableListOf<FavoriteRecord>()
            for (i in 0 until serverFavs.length()) {
                val f = serverFavs.getJSONObject(i)
                val workId = DesktopBackupJson.workId(f, preferId = true) ?: continue
                favList.add(
                    FavoriteRecord(
                        sourceId = workId.sourceId,
                        id = workId.toonId,
                        title = f.optString("title", ""),
                        thumbUrl = f.optString("thumbUrl", ""),
                        href = f.optString("href", ""),
                        genre = f.optString("genre", ""),
                        updatedAt = if (f.isNull("updatedAt")) null else f.optString("updatedAt", "").ifBlank { null },
                        savedAt = f.optLong("savedAt", serverTime),
                    )
                )
            }
            if (favList.isNotEmpty()) database.saveAllFavorites(favList)

            // Apply received history
            val serverHist = serverChanges.optJSONArray("history") ?: JSONArray()
            val histList = mutableListOf<ReadHistoryRecord>()
            for (i in 0 until serverHist.length()) {
                val h = serverHist.getJSONObject(i)
                val workId = DesktopBackupJson.workId(h, preferId = false) ?: continue
                histList.add(
                    ReadHistoryRecord(
                        sourceId = workId.sourceId,
                        toonId = workId.toonId,
                        toonTitle = h.optString("toonTitle", ""),
                        toonThumbUrl = h.optString("toonThumbUrl", ""),
                        toonHref = h.optString("toonHref", ""),
                        lastWrId = h.optString("lastWrId", ""),
                        lastEpisodeTitle = h.optString("lastEpisodeTitle", ""),
                        lastEpisodeHref = h.optString("lastEpisodeHref", ""),
                        lastReadOrder = h.optInt("lastReadOrder", 1),
                        totalEpisodes = h.optInt("totalEpisodes", 1),
                        lastReadAt = h.optLong("lastReadAt", serverTime),
                        nextWrId = if (h.isNull("nextWrId")) null else h.optString("nextWrId", "").ifBlank { null },
                        nextEpisodeTitle = if (h.isNull("nextEpisodeTitle")) null else h.optString("nextEpisodeTitle", "").ifBlank { null },
                        nextEpisodeHref = if (h.isNull("nextEpisodeHref")) null else h.optString("nextEpisodeHref", "").ifBlank { null },
                        hasNew = h.optBoolean("hasNew", false),
                    )
                )
            }
            if (histList.isNotEmpty()) database.saveAllHistory(histList)

            // Apply received episodes
            val serverEps = serverChanges.optJSONArray("readEpisodes") ?: JSONArray()
            val epList = mutableListOf<ReadEpisodeRecord>()
            for (i in 0 until serverEps.length()) {
                val ep = serverEps.getJSONObject(i)
                val workId = DesktopBackupJson.workId(ep, preferId = false) ?: continue
                epList.add(
                    ReadEpisodeRecord(
                        sourceId = workId.sourceId,
                        toonId = workId.toonId,
                        wrId = ep.optString("wrId", ""),
                        readAt = ep.optLong("readAt", serverTime),
                        lastPage = ep.optInt("lastPage", 0),
                    )
                )
            }
            if (epList.isNotEmpty()) database.markAllEpisodesRead(epList)

            // Apply received settings
            val serverSettings = serverChanges.optJSONArray("readerSettings") ?: JSONArray()
            val settingList = mutableListOf<ReaderSettingRecord>()
            for (i in 0 until serverSettings.length()) {
                val s = serverSettings.getJSONObject(i)
                val workId = DesktopBackupJson.workId(s, preferId = false) ?: continue
                settingList.add(
                    ReaderSettingRecord(
                        sourceId = workId.sourceId,
                        toonId = workId.toonId,
                        viewMode = s.optString("viewMode", "SINGLE"),
                        readDirection = s.optString("readDirection", "RIGHT_TO_LEFT"),
                        splitMode = s.optString("splitMode", "FIT"),
                        updatedAt = s.optLong("updatedAt", serverTime),
                    )
                )
            }
            if (settingList.isNotEmpty()) database.saveAllReaderSettings(settingList)

            // Cleanup old tombstones
            database.deleteTombstonesOlderThan(System.currentTimeMillis() - 30L * 86400000L)

            properties.setProperty(KEY_LAST_SYNCED_AT, serverTime.toString())
            saveProperties()

            val msg = "증분 동기화 완료: 수신 (즐겨찾기 ${favList.size}개, 기록 ${histList.size}개)"
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                isSuccess = true,
                lastSyncedAt = serverTime,
                syncMessage = msg,
            )
            return@withContext DesktopSyncResult(true, msg, favList.size, histList.size)
        } catch (e: Exception) {
            val err = "증분 동기화 실패: ${e.localizedMessage ?: "서버 연결 오류"}"
            if (!isSilent) {
                _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
            }
            return@withContext DesktopSyncResult(false, err)
        }
    }

    private suspend fun syncPullInternal(isSilent: Boolean): DesktopSyncResult {
        val state = _syncState.value
        val url = state.serverUrl.trimEnd('/')
        val key = state.syncKey.trim()
        if (key.isBlank()) return DesktopSyncResult(false, "동기화 키가 비어있습니다.")

        if (!isSilent) {
            _syncState.value = _syncState.value.copy(isSyncing = true, syncMessage = null)
        }

        try {
            val req = Request.Builder()
                .url(url)
                .addComics8SyncHeaders(key)
                .get()
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                val err = "서버 응답 오류 (${resp.code})"
                if (!isSilent) {
                    _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
                }
                return DesktopSyncResult(false, err)
            }

            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                val err = "빈 응답을 받았습니다."
                if (!isSilent) {
                    _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
                }
                return DesktopSyncResult(false, err)
            }

            val json = JSONObject(body)
            if (json.optBoolean("empty", false)) {
                val msg = "서버에 저장된 데이터가 없습니다."
                if (!isSilent) {
                    _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = true, syncMessage = msg)
                }
                return DesktopSyncResult(true, msg)
            }

            val restoreResult = DesktopBackupManager.restoreBackupJson(database, body)
            val nowMs = System.currentTimeMillis()
            properties.setProperty(KEY_LAST_SYNCED_AT, nowMs.toString())
            saveProperties()

            val successMsg = "동기화 완료: 즐겨찾기 ${restoreResult.favoriteCount}개, 기록 ${restoreResult.historyCount}개"
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                isSuccess = true,
                lastSyncedAt = nowMs,
                syncMessage = successMsg,
            )
            return DesktopSyncResult(true, successMsg, restoreResult.favoriteCount, restoreResult.historyCount)
        } catch (e: Exception) {
            val err = "동기화 실패: ${e.localizedMessage ?: "서버 연결 오류"}"
            if (!isSilent) {
                _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
            }
            return DesktopSyncResult(false, err)
        }
    }

    private suspend fun syncPushInternal(isSilent: Boolean): DesktopSyncResult {
        val state = _syncState.value
        val url = state.serverUrl.trimEnd('/')
        val key = state.syncKey.trim()
        if (key.isBlank()) return DesktopSyncResult(false, "동기화 키가 비어있습니다.")

        if (!isSilent) {
            _syncState.value = _syncState.value.copy(isSyncing = true, syncMessage = null)
        }

        try {
            val backupJson = DesktopBackupManager.createBackupJson(database)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val reqBody = backupJson.toRequestBody(mediaType)

            val req = Request.Builder()
                .url(url)
                .addComics8SyncHeaders(key)
                .post(reqBody)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                val err = "서버 업로드 오류 (${resp.code})"
                if (!isSilent) {
                    _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
                }
                return DesktopSyncResult(false, err)
            }

            val nowMs = System.currentTimeMillis()
            properties.setProperty(KEY_LAST_SYNCED_AT, nowMs.toString())
            saveProperties()

            val stats = DesktopBackupManager.getStats(database)
            val successMsg = "서버 동기화 완료: 즐겨찾기 ${stats.favoriteCount}개, 기록 ${stats.historyCount}개"
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                isSuccess = true,
                lastSyncedAt = nowMs,
                syncMessage = successMsg,
            )
            return DesktopSyncResult(true, successMsg, stats.favoriteCount, stats.historyCount)
        } catch (e: Exception) {
            val err = "업로드 실패: ${e.localizedMessage ?: "서버 연결 오류"}"
            if (!isSilent) {
                _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
            }
            return DesktopSyncResult(false, err)
        }
    }

    companion object {
        const val KEY_SYNC_KEY = com.comics8.core.sync.SyncConstants.KEY_SYNC_KEY
        const val KEY_SERVER_URL = com.comics8.core.sync.SyncConstants.KEY_SERVER_URL
        const val KEY_AUTO_SYNC = com.comics8.core.sync.SyncConstants.KEY_AUTO_SYNC
        const val KEY_LAST_SYNCED_AT = com.comics8.core.sync.SyncConstants.KEY_LAST_SYNCED_AT
        const val KEY_USE_SERVER_PROXY = com.comics8.core.sync.SyncConstants.KEY_USE_SERVER_PROXY
        const val DEFAULT_SERVER_URL = com.comics8.core.sync.SyncConstants.DEFAULT_SERVER_URL

        fun generateSyncKey(): String = com.comics8.core.sync.SyncConstants.generateSyncKey()
    }
}
