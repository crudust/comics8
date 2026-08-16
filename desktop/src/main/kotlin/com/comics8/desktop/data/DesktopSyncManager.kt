package com.comics8.desktop.data

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
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

data class DesktopSyncState(
    val syncKey: String = "",
    val serverUrl: String = DesktopSyncManager.DEFAULT_SERVER_URL,
    val autoSyncEnabled: Boolean = true,
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
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
) {
    private val configFile = File(System.getProperty("user.home"), ".comics8/sync.properties")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var debounceJob: Job? = null

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
            serverUrl = properties.getProperty(KEY_SERVER_URL, DEFAULT_SERVER_URL),
            autoSyncEnabled = properties.getProperty(KEY_AUTO_SYNC, "true").toBoolean(),
            lastSyncedAt = properties.getProperty(KEY_LAST_SYNCED_AT, "0").toLongOrNull() ?: 0L,
        )
    )
    val syncState: StateFlow<DesktopSyncState> = _syncState.asStateFlow()

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
            _syncState.value = _syncState.value.copy(serverUrl = trimmed)
        }
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        properties.setProperty(KEY_AUTO_SYNC, enabled.toString())
        saveProperties()
        _syncState.value = _syncState.value.copy(autoSyncEnabled = enabled)
    }

    fun triggerDebouncedPush() {
        if (!_syncState.value.autoSyncEnabled) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(2500)
            syncPushInternal(isSilent = true)
        }
    }

    suspend fun syncPull(): DesktopSyncResult = withContext(Dispatchers.IO) {
        syncPullInternal(isSilent = false)
    }

    suspend fun syncPush(): DesktopSyncResult = withContext(Dispatchers.IO) {
        syncPushInternal(isSilent = false)
    }

    suspend fun syncFull(): DesktopSyncResult = withContext(Dispatchers.IO) {
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
        const val DEFAULT_SERVER_URL = com.comics8.core.sync.SyncConstants.DEFAULT_SERVER_URL

        fun generateSyncKey(): String = com.comics8.core.sync.SyncConstants.generateSyncKey()
    }
}
