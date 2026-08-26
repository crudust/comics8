package com.comics8.core.sync

import com.comics8.core.source.WorkId
import com.google.common.truth.Truth.assertThat
import okhttp3.Request
import org.json.JSONObject
import org.junit.Test

class SyncWireTest {

    @Test
    fun workIdParsesElevenByDefaultWhenSourceIdMissing() {
        val fav = JSONObject().put("id", "12345").put("title", "원피스")
        assertThat(SyncWire.workId(fav, preferId = true)).isEqualTo(WorkId.eleven("12345"))

        val hist = JSONObject()
            .put("toonId", "12345")
            .put("toonTitle", "원피스")
            .put("lastWrId", "9")
        assertThat(SyncWire.workId(hist, preferId = false)).isEqualTo(WorkId.eleven("12345"))

        val ep = JSONObject().put("toonId", "12345").put("wrId", "9")
        assertThat(SyncWire.workId(ep, preferId = false)).isEqualTo(WorkId.eleven("12345"))

        val setting = JSONObject().put("toonId", "12345").put("viewMode", "SCROLL")
        assertThat(SyncWire.workId(setting, preferId = false)).isEqualTo(WorkId.eleven("12345"))
    }

    @Test
    fun workIdParsesExplicitSourceId() {
        val hitomi = JSONObject().put("id", "99").put("sourceId", "hitomi")
        assertThat(SyncWire.workId(hitomi, preferId = true)).isEqualTo(WorkId("hitomi", "99"))

        val network = JSONObject().put("toonId", "abc").put("sourceId", "network-123")
        assertThat(SyncWire.workId(network, preferId = false)).isEqualTo(WorkId("network-123", "abc"))
    }

    @Test
    fun localSourceIsExcludedFromSync() {
        val local = JSONObject().put("id", "local_comic").put("sourceId", WorkId.LOCAL_SOURCE)
        assertThat(SyncWire.workId(local, preferId = true)).isNull()
        assertThat(SyncWire.isSyncableSource(WorkId.LOCAL_SOURCE)).isFalse()
        assertThat(SyncWire.isSyncableSource("eleven")).isTrue()
        assertThat(SyncWire.isSyncableSource("hitomi")).isTrue()
        assertThat(SyncWire.isSyncableSource("network-123")).isTrue()
    }

    @Test
    fun tombstoneEntityIdFormatsStorageKey() {
        assertThat(SyncWire.tombstoneEntityId("12345")).isEqualTo("eleven:12345")
        assertThat(SyncWire.tombstoneEntityId("eleven:12345")).isEqualTo("eleven:12345")
        assertThat(SyncWire.tombstoneEntityId("hitomi:gallery:999")).isEqualTo("hitomi:gallery:999")
    }

    @Test
    fun addComics8SyncHeadersAppendsAuthAndKey() {
        val req = Request.Builder()
            .url("https://example.com/sync")
            .addComics8SyncHeaders("TEST_KEY")
            .build()

        assertThat(req.header("Authorization")).isEqualTo("Bearer TEST_KEY")
        assertThat(req.header("X-Sync-Key")).isEqualTo("TEST_KEY")
    }

    @Test
    fun generateSyncKeyProducesSecureMasterFormat() {
        val key = SyncConstants.generateSyncKey()
        assertThat(key).matches("""^C8-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$""")
        val anotherKey = SyncConstants.generateSyncKey()
        assertThat(key).isNotEqualTo(anotherKey)
    }

    @Test
    fun pairingUrlsAreConstructedCorrectly() {
        val baseUrl = "https://sync.comics8.example.com"
        val reqUrl = SyncConstants.pairRequestUrl(baseUrl)
        val confUrl = SyncConstants.pairConfirmUrl(baseUrl)

        assertThat(reqUrl).isEqualTo("https://sync.comics8.example.com/pair/request")
        assertThat(confUrl).isEqualTo("https://sync.comics8.example.com/pair/confirm")
    }
}

