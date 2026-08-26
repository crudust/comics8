package com.comics8.desktop.data

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ToonClient
import com.comics8.core.source.ComicSource
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class DesktopToonRepositorySyncTest {
    private lateinit var dbFile: File
    private lateinit var db: DesktopDatabase
    private lateinit var repository: DesktopToonRepository

    private class FakeSource : ComicSource {
        override val id: String = "test_source"
        override val displayName: String = "Test Source"
        override val origin: String = "https://test.example.com"
        override val catalogs: List<SourceCatalog> = listOf(SourceCatalog("LATEST", "최신", paginated = true))
        override val defaultPolicy: RequestPolicy = RequestPolicy(userAgent = "test")
        override val episodePageSize: Int = 10

        var episodeReturnCount: Int = 15

        override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp): ListingPage =
            ListingPage(emptyList(), 1, 1)

        override suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem> =
            emptyList()

        override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp): EpisodePage {
            val total = episodeReturnCount
            val pageSize = episodePageSize
            val lastPage = (total + pageSize - 1) / pageSize
            val startOrder = (page - 1) * pageSize + 1
            val endOrder = minOf(page * pageSize, total)
            val items = (startOrder..endOrder).map { order ->
                EpisodeItem(
                    wrId = "$order",
                    title = "Episode $order",
                    date = "08.26",
                    thumbUrl = "thumb",
                    href = "href",
                )
            }
            return EpisodePage(items, page, lastPage)
        }

        override suspend fun resolveImages(episode: EpisodeItem, toon: ToonItem, http: SourceHttp): List<String> =
            emptyList()
    }

    @Before
    fun setUp() {
        dbFile = File.createTempFile("desktop-repo-test", ".db")
        db = DesktopDatabase(dbFile)
        val source = FakeSource()
        val registry = SourceRegistry(listOf(source))
        repository = DesktopToonRepository(
            client = ToonClient(sources = { registry }),
            database = db,
            syncManager = null,
            downloadManager = null,
            now = { System.currentTimeMillis() },
            sources = registry,
            isSourceEnabled = { true },
            installedIds = { setOf("test_source") },
        )
    }

    @After
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun syncEpisodeCountsUpdatesReadHistoryAndCallsCallback() = runBlocking {
        val workId = WorkId("test_source", "toon1")
        // 이미 10화까지 읽은 기록 저장 (총회차 10)
        db.saveHistory(
            ReadHistoryRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                toonTitle = "Test Toon",
                toonThumbUrl = "thumb",
                toonHref = "href",
                lastWrId = "5",
                lastEpisodeTitle = "Episode 5",
                lastEpisodeHref = "href/5",
                lastReadOrder = 5,
                totalEpisodes = 10,
                lastReadAt = 1000L,
                hasNew = false,
            )
        )

        // 새로운 회차 업데이트가 있는 아이템 (isNew = true, 새 날짜)
        val item = ToonItem(
            id = "toon1",
            title = "Test Toon",
            thumbUrl = "thumb",
            href = "href",
            sourceId = "test_source",
            updatedAt = "08.26",
            isNew = true,
            readProgress = "5 / 10",
        )

        var updatedTotal = 0
        var updatedProgress: String? = null

        repository.syncEpisodeCounts(listOf(item)) { id, total, progress ->
            if (id == workId) {
                updatedTotal = total
                updatedProgress = progress
            }
        }

        // 백그라운드 코루틴 실행 대기
        var attempts = 0
        while (updatedTotal == 0 && attempts < 50) {
            delay(50)
            attempts++
        }

        assertThat(updatedTotal).isEqualTo(15)
        assertThat(updatedProgress).contains("15")

        val historyInDb = db.getHistory(workId)
        assertThat(historyInDb).isNotNull()
        assertThat(historyInDb?.totalEpisodes).isEqualTo(15)
        assertThat(historyInDb?.hasNew).isTrue()

        // refreshProgress로 플래그 재합성 확인
        val refreshed = repository.refreshProgress(listOf(item))
        assertThat(refreshed.first().readProgress).contains("15")
    }

    @Test
    fun refreshProgressUsesReadCountWhenConfigured() = runBlocking {
        val workId = WorkId("test_source", "toon2")
        DesktopSourcePrefs.setProgressDisplayMode("test_source", com.comics8.core.model.ProgressDisplayMode.READ_COUNT)

        // 3 episodes read out of 20
        db.saveHistory(
            ReadHistoryRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                toonTitle = "Test Toon 2",
                toonThumbUrl = "thumb",
                toonHref = "href",
                lastWrId = "3",
                lastEpisodeTitle = "Episode 3",
                lastEpisodeHref = "href/3",
                lastReadOrder = 3,
                totalEpisodes = 20,
                lastReadAt = 1000L,
                hasNew = false,
            )
        )
        repository.markEpisodeRead(workId, "1")
        repository.markEpisodeRead(workId, "2")
        repository.markEpisodeRead(workId, "3")

        val item = ToonItem(
            id = "toon2",
            title = "Test Toon 2",
            thumbUrl = "thumb",
            href = "href",
            sourceId = "test_source",
        )

        val refreshed = repository.refreshProgress(listOf(item))
        assertThat(refreshed.first().readProgress).isEqualTo("3/20")
    }
}
