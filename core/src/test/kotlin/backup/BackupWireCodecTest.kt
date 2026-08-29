package com.comics8.core.backup

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class BackupWireCodecTest {
    @Test
    fun encodePreservesV2KeysAndExplicitNullableFields() {
        val json = BackupWireCodec.encode(
            BackupWire(
                exportedAt = 123L,
                favorites = listOf(
                    BackupFavoriteWire(
                        sourceId = "hitomi",
                        id = "gallery:1",
                        title = "Title",
                        thumbUrl = "thumb",
                        href = "href",
                        genre = "genre",
                        updatedAt = null,
                        savedAt = 10L,
                    )
                ),
                history = listOf(
                    BackupHistoryWire(
                        sourceId = "hitomi",
                        toonId = "gallery:1",
                        toonTitle = "Title",
                        toonThumbUrl = "thumb",
                        toonHref = "href",
                        lastWrId = "episode:1",
                        lastEpisodeTitle = "Episode",
                        lastEpisodeHref = "episodeHref",
                        lastReadOrder = 1,
                        totalEpisodes = 2,
                        lastReadAt = 20L,
                        nextWrId = null,
                        nextEpisodeTitle = null,
                        nextEpisodeHref = null,
                        hasNew = false,
                    )
                ),
            )
        )

        val root = JSONObject(json)
        assertThat(root.getInt("version")).isEqualTo(2)
        assertThat(root.getString("appName")).isEqualTo("Comics8")
        assertThat(root.getLong("exportedAt")).isEqualTo(123L)
        assertThat(root.getJSONArray("favorites").getJSONObject(0).isNull("updatedAt")).isTrue()
        assertThat(root.getJSONArray("history").getJSONObject(0).isNull("nextWrId")).isTrue()
        assertThat(root.has("readEpisodes")).isTrue()
        assertThat(root.has("readerSettings")).isTrue()
    }

    @Test
    fun decodeAcceptsV1MissingSourceAndUsesLegacyDefaults() {
        val backup = BackupWireCodec.decode(
            """
            {
              "version": 1,
              "favorites": [{"id":"123", "title":"Title"}],
              "history": [{"toonId":"123", "toonTitle":"Title", "lastWrId":"9", "lastEpisodeTitle":"Episode"}],
              "readEpisodes": [{"toonId":"123", "wrId":"9"}],
              "readerSettings": [{"toonId":"123"}]
            }
            """.trimIndent(),
            nowMillis = { 999L },
        )

        assertThat(BackupWireCodec.workId(backup.favorites[0].sourceId, backup.favorites[0].id, backup.favorites[0].toonId, true)?.sourceId)
            .isEqualTo("eleven")
        assertThat(backup.favorites[0].savedAt).isEqualTo(999L)
        assertThat(backup.history[0].lastReadOrder).isEqualTo(1)
        assertThat(backup.history[0].totalEpisodes).isEqualTo(1)
        assertThat(backup.readEpisodes[0].lastPage).isEqualTo(0)
        assertThat(backup.readerSettings[0].viewMode).isEqualTo("SINGLE")
        assertThat(backup.readerSettings[0].readDirection).isEqualTo("RIGHT_TO_LEFT")
        assertThat(backup.readerSettings[0].splitMode).isEqualTo("FIT")
    }

    @Test
    fun roundTripPreservesAllCollections() {
        val original = BackupWire(
            exportedAt = 42L,
            readEpisodes = listOf(BackupEpisodeWire("eleven", "1", wrId = "2", readAt = 3L, lastPage = 4)),
            readerSettings = listOf(BackupReaderSettingWire("eleven", "1", viewMode = "DUAL", readDirection = "LEFT_TO_RIGHT", splitMode = "ALWAYS", updatedAt = 5L)),
        )

        val decoded = BackupWireCodec.decode(BackupWireCodec.encode(original))

        assertThat(decoded).isEqualTo(original)
    }
}
