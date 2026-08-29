package com.comics8.core.sync

import com.comics8.core.model.PairConfirmResult
import com.comics8.core.model.PairRequestResult
import com.comics8.core.model.SyncResult
import com.comics8.core.model.SyncState
import com.comics8.core.network.ToonClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

open class BaseSyncManager(
    protected val storage: SyncStorageAdapter,
    protected val toonClient: ToonClient? = null,
    protected val client: OkHttpClient = OkHttpClient.Builder()
        .dns(com.comics8.core.network.FallbackDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) : AutoCloseable {
    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var debounceJob: Job? = null
    private val inFlight = AtomicBoolean(false)

    protected suspend fun <T> exclusiveSync(busy: T, block: suspend () -> T): T {
        if (!inFlight.compareAndSet(false, true)) return busy
        return try {
            block()
        } finally {
            inFlight.set(false)
        }
    }

    protected val _syncState: MutableStateFlow<SyncState>

    init {
        val initialKey = getOrGenerateSyncKey()
        val initialServerUrl = storage.getPreference(SyncConstants.KEY_SERVER_URL, SyncConstants.DEFAULT_SERVER_URL)
            ?: SyncConstants.DEFAULT_SERVER_URL
        val initialAutoSync = storage.getPreference(SyncConstants.KEY_AUTO_SYNC, "true")?.toBoolean() ?: true
        val initialProxy = storage.getPreference(SyncConstants.KEY_USE_SERVER_PROXY, "false")?.toBoolean() ?: false
        val initialLastSyncedAt = storage.getPreference(SyncConstants.KEY_LAST_SYNCED_AT, "0")?.toLongOrNull() ?: 0L

        _syncState = MutableStateFlow(
            SyncState(
                syncKey = initialKey,
                serverUrl = initialServerUrl,
                autoSyncEnabled = initialAutoSync,
                useServerProxy = initialProxy,
                lastSyncedAt = initialLastSyncedAt,
            )
        )

        applyProxyConfig(initialServerUrl, initialProxy)
    }

    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    override fun close() {
        debounceJob?.cancel()
        scope.cancel()
    }

    private fun applyProxyConfig(serverUrl: String, useProxy: Boolean) {
        toonClient?.proxyBaseUrl = SyncConstants.proxyBaseUrl(serverUrl)
        toonClient?.isProxyEnabled = useProxy
    }

    private fun getOrGenerateSyncKey(): String {
        var key = storage.getPreference(SyncConstants.KEY_SYNC_KEY, null)
        if (key.isNullOrBlank()) {
            key = SyncConstants.generateSyncKey()
            storage.setPreference(SyncConstants.KEY_SYNC_KEY, key)
        }
        return key
    }

    fun generateNewKey(): String {
        val newKey = SyncConstants.generateSyncKey()
        storage.setPreference(SyncConstants.KEY_SYNC_KEY, newKey)
        storage.setPreference(SyncConstants.KEY_LAST_SYNCED_AT, "0")
        _syncState.value = _syncState.value.copy(
            syncKey = newKey,
            lastSyncedAt = 0L,
            syncMessage = "새 동기화 키가 발급되었습니다.",
            isSuccess = true,
        )
        return newKey
    }

    fun updateSyncKey(newKey: String) {
        val trimmed = newKey.trim()
        if (trimmed.isNotBlank()) {
            storage.setPreference(SyncConstants.KEY_SYNC_KEY, trimmed)
            storage.setPreference(SyncConstants.KEY_LAST_SYNCED_AT, "0")
            _syncState.value = _syncState.value.copy(
                syncKey = trimmed,
                lastSyncedAt = 0L,
                syncMessage = "동기화 키가 설정되었습니다.",
                isSuccess = true,
            )
        }
    }

    suspend fun restoreAccount(key: String): SyncResult = exclusiveSync(SyncResult(false, "이미 동기화 진행 중입니다.")) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) return@exclusiveSync SyncResult(false, "동기화 키가 올바르지 않습니다.")
        storage.setPreference(SyncConstants.KEY_SYNC_KEY, trimmed)
        storage.setPreference(SyncConstants.KEY_LAST_SYNCED_AT, "0")
        _syncState.value = _syncState.value.copy(
            syncKey = trimmed,
            lastSyncedAt = 0L,
            syncMessage = "계정 데이터 복구 중...",
        )
        syncPullInternal(isSilent = false)
    }

    fun updateServerUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotBlank()) {
            storage.setPreference(SyncConstants.KEY_SERVER_URL, trimmed)
            toonClient?.proxyBaseUrl = SyncConstants.proxyBaseUrl(trimmed)
            _syncState.value = _syncState.value.copy(serverUrl = trimmed)
        }
    }

    fun updateServerUrlIfChanged(newUrl: String) {
        val trimmed = newUrl.trim()
        val current = _syncState.value.serverUrl
        if (trimmed.isNotBlank() && current != trimmed) {
            updateServerUrl(trimmed)
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        storage.setPreference(SyncConstants.KEY_AUTO_SYNC, enabled.toString())
        _syncState.value = _syncState.value.copy(autoSyncEnabled = enabled)
    }

    fun setUseServerProxy(enabled: Boolean) {
        storage.setPreference(SyncConstants.KEY_USE_SERVER_PROXY, enabled.toString())
        toonClient?.isProxyEnabled = enabled
        _syncState.value = _syncState.value.copy(useServerProxy = enabled)
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

    suspend fun requestPairingCode(): PairRequestResult = withContext(Dispatchers.IO) {
        val state = _syncState.value
        val reqUrl = SyncConstants.pairRequestUrl(state.serverUrl)
        val syncKey = state.syncKey.trim()
        if (syncKey.isBlank()) {
            return@withContext PairRequestResult(false, message = "동기화 키가 설정되지 않았습니다.")
        }
        try {
            val req = Request.Builder()
                .url(reqUrl)
                .addComics8SyncHeaders(syncKey)
                .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext PairRequestResult(
                        false,
                        message = if (resp.code == 404) "서버에서 페어링 기능이 지원되지 않습니다. 마스터 복구 키를 이용해주세요." else "페어링 코드 발급 실패 (${resp.code})"
                    )
                }
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val code = json.optString("code", "")
                val expiresIn = if (json.has("expiresInSeconds")) {
                    json.optInt("expiresInSeconds", 300)
                } else {
                    json.optInt("expiresIn", 300)
                }
                if (code.isBlank()) {
                    return@withContext PairRequestResult(false, message = "서버 응답 오류")
                }
                PairRequestResult(true, code = code, expiresInSeconds = expiresIn)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PairRequestResult(false, message = e.localizedMessage ?: "서버 연결 오류")
        }
    }

    suspend fun confirmPairingCode(code: String): PairConfirmResult = withContext(Dispatchers.IO) {
        val trimmedCode = code.trim().replace(" ", "").replace("-", "")
        if (trimmedCode.length != 6) {
            return@withContext PairConfirmResult(false, message = "6자리 코드를 입력해주세요.")
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
            val syncKey = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext PairConfirmResult(
                        success = false,
                        message = if (resp.code == 400 || resp.code == 404) "유효하지 않거나 만료된 코드입니다." else "인증 실패 (${resp.code})"
                    )
                }
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                json.optString("syncKey", "").also {
                    if (it.isBlank()) {
                        return@withContext PairConfirmResult(false, message = json.optString("message", "인증 실패"))
                    }
                }
            }
            storage.setPreference(SyncConstants.KEY_SYNC_KEY, syncKey)
            storage.setPreference(SyncConstants.KEY_LAST_SYNCED_AT, "0")
            _syncState.value = _syncState.value.copy(syncKey = syncKey, lastSyncedAt = 0L, syncMessage = "기기 연결 중...")
            val pullRes = syncPullInternal(isSilent = false)
            PairConfirmResult(pullRes.success, syncKey = syncKey, message = if (pullRes.success) "기기 연결 및 데이터 가져오기 완료" else pullRes.message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PairConfirmResult(false, message = e.localizedMessage ?: "서버 연결 오류")
        }
    }

    suspend fun syncPush(): SyncResult = exclusiveSync(SyncResult(false, "이미 동기화 진행 중입니다.")) {
        syncPushInternal()
    }

    suspend fun syncPull(): SyncResult = exclusiveSync(SyncResult(false, "이미 동기화 진행 중입니다.")) {
        syncPullInternal(isSilent = false)
    }

    suspend fun syncDelta(): SyncResult = exclusiveSync(SyncResult(false, "이미 동기화 진행 중입니다.")) {
        syncDeltaInternal(isSilent = false)
    }

    suspend fun syncFull(): SyncResult = exclusiveSync(SyncResult(false, "이미 동기화 진행 중입니다.")) {
        val pullRes = syncPullInternal(isSilent = false)
        if (!pullRes.success && !pullRes.message.contains("데이터가 없습니다")) {
            return@exclusiveSync pullRes
        }
        syncPushInternal()
    }

    /** Silent delta if no other sync is running; null when skipped. */
    suspend fun syncIfIdle(): SyncResult? = withContext(Dispatchers.IO) {
        exclusiveSync(null) {
            syncDeltaInternal(isSilent = true)
        }
    }

    private suspend fun syncDeltaInternal(isSilent: Boolean): SyncResult = withContext(Dispatchers.IO) {
        val state = _syncState.value
        val url = SyncConstants.apiRoot(state.serverUrl) + "/sync/delta"
        val key = state.syncKey.trim()
        if (key.isBlank()) return@withContext SyncResult(false, "동기화 키가 비어있습니다.")

        if (!isSilent) {
            _syncState.value = _syncState.value.copy(isSyncing = true, syncMessage = null)
        }

        try {
            val since = state.lastSyncedAt
            val localChanges = storage.getLocalChangesSince(since)

            val payload = JSONObject().apply {
                put("since", since)
                put("clientTime", System.currentTimeMillis())
                put("version", 2)
                put("changes", localChanges)
            }

            val reqBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url(url)
                .addComics8SyncHeaders(key)
                .post(reqBody)
                .build()

            val json = client.newCall(req).execute().use { resp ->
                // Fallback to legacy syncPull if delta is not supported
                if (resp.code == 404) return@use null

                if (!resp.isSuccessful) {
                    val err = "서버 응답 오류 (${resp.code})"
                    if (!isSilent) {
                        _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
                    }
                    return@withContext SyncResult(false, err)
                }
                JSONObject(resp.body?.string().orEmpty())
            }
            if (json == null) return@withContext syncPullInternal(isSilent)

            val serverTime = json.optLong("serverTime", System.currentTimeMillis())
            val serverChanges = json.optJSONObject("changes") ?: JSONObject()
            val isPro = json.optBoolean("isPro", false)

            val (favCount, histCount) = storage.applyRemoteChanges(serverChanges, serverTime)

            // Cleanup tombstones older than 30 days
            val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            storage.deleteOldTombstones(thirtyDaysAgo)

            // Update sync timestamp
            storage.setPreference(SyncConstants.KEY_LAST_SYNCED_AT, serverTime.toString())

            val msg = "증분 동기화 완료 (즐겨찾기 $favCount, 기록 $histCount)"
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                isSuccess = true,
                isPro = isPro,
                lastSyncedAt = serverTime,
                syncMessage = msg,
            )
            SyncResult(true, msg, favCount, histCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = "증분 동기화 실패: ${e.localizedMessage ?: "서버 연결 오류"}"
            if (!isSilent) {
                _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
            }
            SyncResult(false, err)
        }
    }

    private suspend fun syncPullInternal(isSilent: Boolean): SyncResult = withContext(Dispatchers.IO) {
        val state = _syncState.value
        val url = SyncConstants.apiRoot(state.serverUrl) + "/sync"
        val key = state.syncKey.trim()
        if (key.isBlank()) return@withContext SyncResult(false, "동기화 키가 비어있습니다.")

        if (!isSilent) {
            _syncState.value = _syncState.value.copy(isSyncing = true, syncMessage = null)
        }

        try {
            val req = Request.Builder()
                .url(url)
                .addComics8SyncHeaders(key)
                .get()
                .build()

            val json = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = "서버 응답 오류 (${resp.code})"
                    if (!isSilent) {
                        _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
                    }
                    return@withContext SyncResult(false, err)
                }
                JSONObject(resp.body?.string().orEmpty())
            }

            val serverTime = json.optLong("exportedAt", System.currentTimeMillis())
            val isPro = json.optBoolean("isPro", false)

            val (favCount, histCount) = storage.applyFullSnapshot(json, serverTime)

            // Update sync timestamp
            storage.setPreference(SyncConstants.KEY_LAST_SYNCED_AT, serverTime.toString())

            val msg = "가져오기 완료 (즐겨찾기 $favCount, 기록 $histCount)"
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                isSuccess = true,
                isPro = isPro,
                lastSyncedAt = serverTime,
                syncMessage = msg,
            )
            SyncResult(true, msg, favCount, histCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = "가져오기 실패: ${e.localizedMessage ?: "서버 연결 오류"}"
            if (!isSilent) {
                _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
            }
            SyncResult(false, err)
        }
    }

    private suspend fun syncPushInternal(): SyncResult = withContext(Dispatchers.IO) {
        val state = _syncState.value
        val url = SyncConstants.apiRoot(state.serverUrl) + "/sync"
        val key = state.syncKey.trim()
        if (key.isBlank()) return@withContext SyncResult(false, "동기화 키가 비어있습니다.")

        _syncState.value = _syncState.value.copy(isSyncing = true, syncMessage = null)

        try {
            val payload = storage.getFullSnapshot()
            val reqBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url(url)
                .addComics8SyncHeaders(key)
                .post(reqBody)
                .build()

            val json = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = "서버 응답 오류 (${resp.code})"
                    _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
                    return@withContext SyncResult(false, err)
                }
                JSONObject(resp.body?.string().orEmpty())
            }

            val isPro = json.optBoolean("isPro", false)
            val favCount = json.optInt("favorites", 0)
            val histCount = json.optInt("history", 0)
            val serverTime = System.currentTimeMillis()

            storage.setPreference(SyncConstants.KEY_LAST_SYNCED_AT, serverTime.toString())

            val msg = "올리기 완료 (즐겨찾기 $favCount, 기록 $histCount)"
            _syncState.value = _syncState.value.copy(
                isSyncing = false,
                isSuccess = true,
                isPro = isPro,
                lastSyncedAt = serverTime,
                syncMessage = msg,
            )
            SyncResult(true, msg, favCount, histCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = "올리기 실패: ${e.localizedMessage ?: "서버 연결 오류"}"
            _syncState.value = _syncState.value.copy(isSyncing = false, isSuccess = false, syncMessage = err)
            SyncResult(false, err)
        }
    }
}
