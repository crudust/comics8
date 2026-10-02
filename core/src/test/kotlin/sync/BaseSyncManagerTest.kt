package com.comics8.core.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
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
        var appliedFullSnapshotCount = SyncApplyCounts(0, 0, 0)
        var appliedRemoteChangesCount = SyncApplyCounts(0, 0, 0)

        override suspend fun getLocalChangesSince(since: Long): JSONObject = JSONObject().put("favorites", org.json.JSONArray())
        override suspend fun getFullSnapshot(): JSONObject = fullSnapshotJson
        override suspend fun applyRemoteChanges(serverChanges: JSONObject, serverTime: Long): SyncApplyCounts = appliedRemoteChangesCount
        override suspend fun applyFullSnapshot(snapshot: JSONObject, serverTime: Long): SyncApplyCounts = appliedFullSnapshotCount
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
        storage.appliedFullSnapshotCount = SyncApplyCounts(5, 12, 0)

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

    @Test
    fun pairingUsesExpiresInSecondsField() = runBlocking {
        val okClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        JSONObject().put("code", "123456").put("expiresInSeconds", 90).toString()
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            }
            .build()

        val result = BaseSyncManager(storage = storage, client = okClient).requestPairingCode()

        assertThat(result.success).isTrue()
        assertThat(result.expiresInSeconds).isEqualTo(90)
    }

    @Test(expected = CancellationException::class)
    fun pairingDoesNotSwallowCancellation() = runBlocking<Unit> {
        val okClient = OkHttpClient.Builder()
            .addInterceptor { throw CancellationException("cancelled") }
            .build()

        BaseSyncManager(storage = storage, client = okClient).requestPairingCode()
    }

    @Test
    fun fetchCatalogBatchParsesServerResponse() = runBlocking {
        val okClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val respJson = JSONObject().apply {
                    put("catalog", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("sourceId", "test_source")
                            put("toonId", "toon1")
                            put("totalEpisodes", 42)
                            put("updatedAt", 1700000000000L)
                        })
                    })
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(respJson.toString().toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val manager = BaseSyncManager(storage = storage, client = okClient)
        val result = manager.fetchCatalogBatch(listOf(com.comics8.core.source.WorkId("test_source", "toon1")))

        assertThat(result).hasSize(1)
        assertThat(result[0].sourceId).isEqualTo("test_source")
        assertThat(result[0].toonId).isEqualTo("toon1")
        assertThat(result[0].totalEpisodes).isEqualTo(42)
    }

    @Test
    fun reportCatalogSendsUpdatesPayload() = runBlocking {
        var requestedUrl: String? = null
        var requestedBody: String? = null
        val okClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                requestedUrl = req.url.toString()
                val buffer = okio.Buffer()
                req.body?.writeTo(buffer)
                requestedBody = buffer.readUtf8()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val manager = BaseSyncManager(storage = storage, client = okClient)
        manager.reportCatalog(listOf(SyncCatalogWire("test_source", "toon1", 50, 1000L)))

        assertThat(requestedUrl).contains("/catalog/report")
        assertThat(requestedBody).contains("test_source")
        assertThat(requestedBody).contains("toon1")
        assertThat(requestedBody).contains("50")
    }
}
