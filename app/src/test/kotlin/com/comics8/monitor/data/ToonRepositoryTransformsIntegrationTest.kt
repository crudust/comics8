package com.comics8.monitor.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ViewMode
import com.comics8.core.network.ToonClient
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.data.local.ReaderSetting
import com.comics8.monitor.data.local.ToonCatalogEntry
import com.comics8.monitor.data.local.get
import com.comics8.monitor.data.local.upsertMonotonic
import com.comics8.monitor.data.local.upsertAllMonotonic
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ToonRepositoryTransformsIntegrationTest {
    @Test
    fun getReaderSettingUsesSharedLegacyFallbacks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val registry = SourceRegistry()
            val repository = ToonRepository(
                client = ToonClient(sources = { registry }),
                seenDao = database.seenDao(),
                favoriteDao = database.favoriteDao(),
                readerSettingDao = database.readerSettingDao(),
                readEpisodeDao = database.readEpisodeDao(),
                readHistoryDao = database.readHistoryDao(),
                database = database,
                sources = registry,
            )
            val workId = WorkId("eleven", "reader-setting-test")
            database.readerSettingDao().saveSetting(
                ReaderSetting(
                    sourceId = workId.sourceId,
                    toonId = workId.toonId,
                    viewMode = "SINGLE",
                    readDirection = "invalid",
                    splitMode = "invalid",
                    updatedAt = 1L,
                ),
            )

            val setting = repository.getReaderSetting(workId)

            assertThat(setting?.viewMode).isEqualTo(ViewMode.PAGE)
            assertThat(setting?.readDirection).isEqualTo(ReadDirection.RIGHT_TO_LEFT)
            assertThat(setting?.splitMode).isEqualTo(SplitMode.FIT)
        } finally {
            database.close()
        }
    }

    @Test
    fun refreshProgressUsesCatalogTotalForUnreadItems() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val registry = SourceRegistry()
            val repository = ToonRepository(
                client = ToonClient(sources = { registry }),
                seenDao = database.seenDao(),
                favoriteDao = database.favoriteDao(),
                readerSettingDao = database.readerSettingDao(),
                readEpisodeDao = database.readEpisodeDao(),
                readHistoryDao = database.readHistoryDao(),
                database = database,
                sources = registry,
            )
            val workId = WorkId("eleven", "unread-catalog-test")
            repository.saveCatalogTotal(workId, 104)

            val item = com.comics8.core.model.ToonItem(
                id = workId.toonId,
                title = "Unread Catalog Comic",
                thumbUrl = "thumb",
                href = "href",
                sourceId = workId.sourceId,
            )

            val refreshed = repository.refreshProgress(listOf(item))
            assertThat(refreshed.first().readProgress).isEqualTo("104화")
        } finally {
            database.close()
        }
    }

    @Test
    fun toonCatalogMonotonicUpsertDoesNotDowngradeTotalEpisodesOrUpdatedAt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = database.toonCatalogDao()
            val workId = WorkId("source1", "toon1")

            // 1. Initial insert: 50 episodes at t=500
            dao.upsertMonotonic(workId, totalEpisodes = 50, updatedAt = 500L)
            var entry = dao.get(workId)
            assertThat(entry).isNotNull()
            assertThat(entry?.totalEpisodes).isEqualTo(50)
            assertThat(entry?.updatedAt).isEqualTo(500L)

            // 2. Attempt downgrade: 30 episodes at t=100 -> should be rejected/ignored
            dao.upsertMonotonic(workId, totalEpisodes = 30, updatedAt = 100L)
            entry = dao.get(workId)
            assertThat(entry?.totalEpisodes).isEqualTo(50)
            assertThat(entry?.updatedAt).isEqualTo(500L)

            // 3. Upgrade: 60 episodes at t=600 -> should be accepted
            dao.upsertMonotonic(workId, totalEpisodes = 60, updatedAt = 600L)
            entry = dao.get(workId)
            assertThat(entry?.totalEpisodes).isEqualTo(60)
            assertThat(entry?.updatedAt).isEqualTo(600L)

            // 4. Batch upsertAllMonotonic: downgrade should be ignored, upgrade should apply
            val batchEntries = listOf(
                ToonCatalogEntry("source1", "toon1", 40, 700L),
                ToonCatalogEntry("source1", "toon2", 20, 200L),
            )
            dao.upsertAllMonotonic(batchEntries)

            entry = dao.get(workId)
            assertThat(entry?.totalEpisodes).isEqualTo(60)
            assertThat(entry?.updatedAt).isEqualTo(600L)

            val entry2 = dao.get(WorkId("source1", "toon2"))
            assertThat(entry2).isNotNull()
            assertThat(entry2?.totalEpisodes).isEqualTo(20)
            assertThat(entry2?.updatedAt).isEqualTo(200L)
        } finally {
            database.close()
        }
    }

    private class FakeSource : com.comics8.core.source.ComicSource {
        override val id: String = "test_source"
        override val displayName: String = "Test Source"
        override val origin: String = "https://test.example.com"
        override val catalogs: List<com.comics8.core.source.SourceCatalog> = listOf(
            com.comics8.core.source.SourceCatalog("LATEST", "최신", paginated = true)
        )
        override val defaultPolicy: com.comics8.core.source.RequestPolicy = com.comics8.core.source.RequestPolicy(userAgent = "test")
        override val episodePageSize: Int = 10

        override suspend fun loadListing(catalogId: String, page: Int, http: com.comics8.core.source.SourceHttp): com.comics8.core.model.ListingPage =
            com.comics8.core.model.ListingPage(emptyList(), 1, 1)

        override suspend fun search(query: com.comics8.core.source.SearchQuery, http: com.comics8.core.source.SourceHttp): List<com.comics8.core.model.ToonItem> = emptyList()

        override suspend fun loadEpisodes(item: com.comics8.core.model.ToonItem, page: Int, http: com.comics8.core.source.SourceHttp): com.comics8.core.model.EpisodePage {
            val total = 15
            val pageSize = episodePageSize
            val lastPage = (total + pageSize - 1) / pageSize
            val startOrder = (page - 1) * pageSize + 1
            val endOrder = minOf(page * pageSize, total)
            val items = (startOrder..endOrder).map { order ->
                com.comics8.core.model.EpisodeItem(
                    wrId = "$order",
                    title = "Episode $order",
                    date = "08.26",
                    thumbUrl = "thumb",
                    href = "href",
                )
            }
            return com.comics8.core.model.EpisodePage(items, page, lastPage)
        }

        override suspend fun resolveImages(episode: com.comics8.core.model.EpisodeItem, toon: com.comics8.core.model.ToonItem, http: com.comics8.core.source.SourceHttp): List<String> = emptyList()
    }

    @Test
    fun syncEpisodeCountsPopulatesCatalogForUnreadBatchItemsWhenMissing() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val source = FakeSource()
            val registry = SourceRegistry(listOf(source))
            val repository = ToonRepository(
                client = ToonClient(sources = { registry }),
                seenDao = database.seenDao(),
                favoriteDao = database.favoriteDao(),
                readerSettingDao = database.readerSettingDao(),
                readEpisodeDao = database.readEpisodeDao(),
                readHistoryDao = database.readHistoryDao(),
                database = database,
                sources = registry,
                installedIds = { setOf("test_source") },
            )

            val items = (1..3).map { idx ->
                com.comics8.core.model.ToonItem(
                    id = "explore_toon_$idx",
                    title = "Explore Toon $idx",
                    thumbUrl = "thumb",
                    href = "href",
                    sourceId = "test_source",
                )
            }

            var callbackCount = 0
            repository.syncEpisodeCounts(items) { _, total, progress ->
                callbackCount++
                assertThat(total).isEqualTo(15)
                assertThat(progress).isEqualTo("15화")
            }

            var attempts = 0
            while (callbackCount < 3 && attempts < 100) {
                kotlinx.coroutines.delay(50)
                attempts++
            }

            assertThat(callbackCount).isEqualTo(3)
            for (item in items) {
                val entry = database.toonCatalogDao().get(item.workId())
                assertThat(entry).isNotNull()
                assertThat(entry?.totalEpisodes).isEqualTo(15)
            }

            // Once populated, re-running syncEpisodeCounts should skip since needsCatalog is now false
            var secondCallbackCount = 0
            repository.syncEpisodeCounts(items) { _, _, _ ->
                secondCallbackCount++
            }
            kotlinx.coroutines.delay(200)
            assertThat(secondCallbackCount).isEqualTo(0)
        } finally {
            database.close()
        }
    }
}
