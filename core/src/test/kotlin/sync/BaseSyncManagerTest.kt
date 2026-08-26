package com.comics8.core.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

class BaseSyncManagerTest {

    private class FakeStorageAdapter : SyncStorageAdapter {
        val prefs = mutableMapOf<String, String>()
        var fullSnapshotJson = JSONObject().put("favorites", org.json.JSONArray()).put("history", org.json.JSONArray())
        var appliedFullSnapshotCount = Pair(0, 0)
        var appliedRemoteChangesCount = Pair(0, 0)

        override suspend fun getLocalChangesSince(since: Long): JSONObject = JSONObject().put("favorites", org.json.JSONArray())
        override suspend fun getFullSnapshot(): JSONObject = fullSnapshotJson
        override suspend fun applyRemoteChanges(serverChanges: JSONObject, serverTime: Long): Pair<Int, Int> = appliedRemoteChangesCount
        override suspend fun applyFullSnapshot(snapshot: JSONObject, serverTime: Long): Pair<Int, Int> = appliedFullSnapshotCount
        override suspend fun deleteOldTombstones(cutoff: Long) {}
        override fun getPreference(key: String, defaultValue: String?): String? = prefs[key] ?: defaultValue
        override fun setPreference(key: String, value: String?) {
            if (value == null) prefs.remove(key) else prefs[key] = value
        }
    }

    private lateinit var storage: FakeStorageAdapter

    @Before
    fun setUp() {
        storage = FakeStorageAdapter()
    }

    @Test
    fun updateSyncKeyResetsLastSyncedAtTimestamp() {
        storage.setPreference(SyncConstants.KEY_LAST_SYNCED_AT, "1700000000000")
        val manager = BaseSyncManager(storage = storage)

        manager.updateSyncKey("C8-NEW1-KEY2-TEST")

        assertThat(manager.syncState.value.syncKey).isEqualTo("C8-NEW1-KEY2-TEST")
        assertThat(manager.syncState.value.lastSyncedAt).isEqualTo(0L)
        assertThat(storage.getPreference(SyncConstants.KEY_LAST_SYNCED_AT, null)).isEqualTo("0")
    }

    @Test
    fun generateNewKeyResetsLastSyncedAtTimestamp() {
        storage.setPreference(SyncConstants.KEY_LAST_SYNCED_AT, "1700000000000")
        val manager = BaseSyncManager(storage = storage)

        val newKey = manager.generateNewKey()

        assertThat(manager.syncState.value.syncKey).isEqualTo(newKey)
        assertThat(manager.syncState.value.lastSyncedAt).isEqualTo(0L)
        assertThat(storage.getPreference(SyncConstants.KEY_LAST_SYNCED_AT, null)).isEqualTo("0")
    }

    @Test
    fun syncFullExecutesPullAndPushSequentiallyWithoutLockConflict() = runBlocking {
        var pullExecuted = false
        var pushExecuted = false

        val okClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                if (req.method == "GET" && url.endsWith("/sync")) {
                    pullExecuted = true
                    val respBody = JSONObject()
                        .put("version", 2)
                        .put("exportedAt", 1700000001000L)
                        .put("favorites", org.json.JSONArray())
                        .put("history", org.json.JSONArray())
                        .toString()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(respBody.toResponseBody("application/json".toMediaType()))
                        .build()
                } else if (req.method == "POST" && url.endsWith("/sync")) {
                    pushExecuted = true
                    val respBody = JSONObject()
                        .put("status", "success")
                        .put("favorites", 0)
                        .put("history", 0)
                        .toString()
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(respBody.toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("{}".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val manager = BaseSyncManager(storage = storage, client = okClient)
        val result = manager.syncFull()

        assertThat(result.success).isTrue()
        assertThat(pullExecuted).isTrue()
        assertThat(pushExecuted).isTrue()
    }

    @Test
    fun restoreAccountSetsKeyResetsTimestampAndPullsSnapshot() = runBlocking {
        storage.setPreference(SyncConstants.KEY_LAST_SYNCED_AT, "999999999")
        storage.appliedFullSnapshotCount = Pair(5, 12)

        val okClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val respBody = JSONObject()
                    .put("version", 2)
                    .put("exportedAt", 1700000002000L)
                    .put("favorites", org.json.JSONArray())
                    .put("history", org.json.JSONArray())
                    .toString()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(respBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val manager = BaseSyncManager(storage = storage, client = okClient)
        val result = manager.restoreAccount("C8-REST-ORED-KEY1")

        assertThat(result.success).isTrue()
        assertThat(manager.syncState.value.syncKey).isEqualTo("C8-REST-ORED-KEY1")
        assertThat(manager.syncState.value.lastSyncedAt).isEqualTo(1700000002000L)
        assertThat(result.favoritesCount).isEqualTo(5)
        assertThat(result.historyCount).isEqualTo(12)
    }
}
