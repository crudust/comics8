package com.comics8.monitor.data

import android.content.Context
import android.content.SharedPreferences
import com.comics8.monitor.data.local.AppDatabase
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
            syncPushInternal(isSilent = true)
        }
    }

    suspend fun syncPull(): SyncResult = withContext(Dispatchers.IO) {
        syncPullInternal(isSilent = false)
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
        const val KEY_SYNC_KEY = "sync_key"
        const val KEY_SERVER_URL = "sync_server_url"
        const val KEY_AUTO_SYNC = "sync_auto_enabled"
        const val KEY_LAST_SYNCED_AT = "sync_last_at"
        const val DEFAULT_SERVER_URL = "https://homeassistant.tail1946af.ts.net/api/comics8/sync"

        fun generateSyncKey(): String {
            val chars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
            return (1..4).map { chars.random() }.joinToString("")
        }
    }
}
