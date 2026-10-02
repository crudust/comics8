package com.comics8.core.sync

import com.comics8.core.backup.BackupHistoryWire
import com.comics8.core.backup.BackupReaderSettingWire
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class SyncPayloadCodecTest {
    @Test
    fun deltaEncodingUsesNumericHasNewAndCanonicalTombstoneId() {
        val encoded = SyncPayloadCodec.encode(
            SyncPayload(
                history = listOf(
                    BackupHistoryWire(
                        sourceId = " source ", toonId = "toon", toonTitle = "Title",
                        toonThumbUrl = "", toonHref = "", lastWrId = "1",
                        lastEpisodeTitle = "One", lastEpisodeHref = "", lastReadOrder = 1,
                        totalEpisodes = 2, lastReadAt = 3, nextWrId = null,
                        nextEpisodeTitle = null, nextEpisodeHref = null, hasNew = true,
                    ),
                ),
                tombstones = listOf(SyncTombstoneWire("history", "source:toon", 9)),
            ),
        )

        assertThat(encoded.has("version")).isFalse()
        assertThat(encoded.getJSONArray("history").getJSONObject(0).getInt("hasNew")).isEqualTo(1)
        assertThat(encoded.getJSONArray("tombstones").getJSONObject(0).getString("entityId"))
            .isEqualTo("source:toon")
    }

    @Test
    fun decodingUsesSyncDefaultsAndServerTime() {
        val decoded = SyncPayloadCodec.decode(
            JSONObject(
                """{"readerSettings":[{"sourceId":"local","toonId":"a"}],"history":[{"toonId":"a"}]}""",
            ),
            serverTime = 42,
        )

        assertThat(decoded.readerSettings.single()).isEqualTo(
            BackupReaderSettingWire("local", "a", null, "SCROLL", "TOP_TO_BOTTOM", "NONE", 42),
        )
        assertThat(decoded.history.single().lastReadOrder).isEqualTo(0)
        assertThat(decoded.history.single().lastReadAt).isEqualTo(42)
    }

    @Test
    fun catalogEncodingAndDecodingPreservesTotals() {
        val payload = SyncPayload(
            catalog = listOf(
                SyncCatalogWire(sourceId = "eleven", toonId = "comic1", totalEpisodes = 104, updatedAt = 1000L),
            ),
        )
        val encoded = SyncPayloadCodec.encode(payload)
        assertThat(encoded.has("catalog")).isTrue()
        val decoded = SyncPayloadCodec.decode(encoded, serverTime = 2000L)
        assertThat(decoded.catalog).hasSize(1)
        assertThat(decoded.catalog.first().toonId).isEqualTo("comic1")
        assertThat(decoded.catalog.first().totalEpisodes).isEqualTo(104)
        assertThat(decoded.catalog.first().updatedAt).isEqualTo(1000L)
    }
}
