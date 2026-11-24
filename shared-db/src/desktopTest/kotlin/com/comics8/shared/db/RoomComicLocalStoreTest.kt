package com.comics8.shared.db

import com.comics8.core.model.EpisodeListingTotal
import com.comics8.core.source.WorkId
import com.comics8.core.storage.DownloadedEpisodeRecord
import com.comics8.core.storage.FavoriteRecord
import com.comics8.core.storage.ReadEpisodeRecord
import com.comics8.core.storage.ReadHistoryRecord
import com.comics8.core.storage.ReaderSettingRecord
import com.comics8.core.storage.SeenRecord
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class RoomComicLocalStoreTest {
    @Test
    fun roundTripsAllEightTablesThroughComicLocalStore() = runBlocking {
        val db = openInMemoryDesktopDatabase()
        val store = RoomComicLocalStore(db)
        try {
            val workId = WorkId("eleven", "w1")

            store.upsertFavorite(FavoriteRecord("eleven", "w1", "Title", "thumb", "href", "action", "10.01", 100L))
            assertThat(store.countFavorites()).isEqualTo(1)
            assertThat(store.hasFavorite(workId)).isTrue()

            store.upsertSeenAll(listOf(SeenRecord("eleven", "w1", "Title", "10.01", 10L, 20L, "eleven:w1|10.01")))
            assertThat(store.countSeen()).isEqualTo(1)

            store.upsertReaderSetting(ReaderSettingRecord("eleven", "w1", "PAGE", "RTL", "LEFT_TO_RIGHT", 30L))
            assertThat(store.getReaderSetting(workId)?.splitMode).isEqualTo("LEFT_TO_RIGHT")

            store.upsertReadEpisode(ReadEpisodeRecord("eleven", "w1", "ep1", 40L, 3))
            store.updateReadEpisodeLastPage(workId, "ep1", 7, 50L)
            assertThat(store.getReadEpisode(workId, "ep1")?.lastPage).isEqualTo(7)

            store.upsertHistory(
                ReadHistoryRecord(
                    sourceId = "eleven",
                    toonId = "w1",
                    toonTitle = "Title",
                    toonThumbUrl = "thumb",
                    toonHref = "href",
                    lastWrId = "ep1",
                    lastEpisodeTitle = "1화",
                    lastEpisodeHref = "e1",
                    lastReadOrder = 1,
                    totalEpisodes = 10,
                    lastReadAt = 60L,
                    hasNew = true,
                ),
            )
            assertThat(store.getHistory(workId)?.hasNew).isTrue()

            store.upsertDownloadedEpisode(
                DownloadedEpisodeRecord("eleven", "w1", "ep1", "Title", "thumb", "href", "1화", "e1", 5, 1024L, 70L, "/tmp/ep1"),
            )
            assertThat(store.getDownloadedEpisode(workId, "ep1")?.imageCount).isEqualTo(5)

            store.recordTombstone("FAVORITE", "eleven:del", 80L)
            assertThat(store.getTombstonesSince(0L)).hasSize(1)

            val counted = EpisodeListingTotal(count = 25, lastPage = 2, pageSize = 20, delta = -15)
            assertThat(store.recordCatalogTotal(workId, counted, 90L)).isTrue()
            assertThat(store.getCatalogEntry(workId)?.total()).isEqualTo(counted)
        } finally {
            db.close()
        }
    }

    @Test
    fun inTransactionCommitsNestedAndRollsBackOnException() = runBlocking {
        val db = openInMemoryDesktopDatabase()
        val store = RoomComicLocalStore(db)
        try {
            store.inTransaction {
                store.upsertFavorite(FavoriteRecord("eleven", "ok1", "OK", "", "", "", null, 1L))
                store.inTransaction {
                    store.upsertFavorite(FavoriteRecord("eleven", "ok2", "OK2", "", "", "", null, 2L))
                }
            }
            assertThat(store.countFavorites()).isEqualTo(2)

            runCatching {
                store.inTransaction {
                    store.upsertFavorite(FavoriteRecord("eleven", "rolled_back", "RB", "", "", "", null, 3L))
                    error("boom")
                }
            }
            assertThat(store.countFavorites()).isEqualTo(2)
            assertThat(store.hasFavorite(WorkId("eleven", "rolled_back"))).isFalse()
        } finally {
            db.close()
        }
    }
}
