package com.comics8.desktop.data

import com.comics8.core.model.EpisodeListingTotal
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ToonItem
import com.comics8.core.model.ViewMode
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
        override var episodePageSize: Int = 10

        var episodeReturnCount: Int = 15
        val requestedPages = java.util.Collections.synchronizedList(mutableListOf<Int>())

        override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp): ListingPage =
            ListingPage(emptyList(), 1, 1)

        override suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem> =
            emptyList()

        override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp): EpisodePage {
            requestedPages.add(page)
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

        var updatedTotal: EpisodeListingTotal? = null
        var updatedProgress: String? = null

        repository.syncEpisodeCounts(listOf(item)) { id, total, progress ->
            if (id == workId) {
                updatedTotal = total
                updatedProgress = progress
            }
        }

        // 백그라운드 코루틴 실행 대기
        var attempts = 0
        while (updatedTotal == null && attempts < 50) {
            delay(50)
            attempts++
        }

        assertThat(updatedTotal?.count).isEqualTo(15)
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
        // The history row carries a count, but a count is only a total when a sweep produced it, so
        // nothing is shown until the listing is counted.
        assertThat(refreshed.first().readProgress).isNull()
    }

    @Test
    fun getReaderSettingUsesSharedLegacyFallbacks() = runBlocking {
        val workId = WorkId("test_source", "reader-setting-test")
        db.saveReaderSetting(
            ReaderSettingRecord(
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
    }

    @Test
    fun applyRemoteChangesRejectsStaleHistoryOrderAndCountsEpisodes() = runBlocking {
        val adapter = DesktopSyncStorageAdapter(db)
        val workId = WorkId("test_source", "toon-sync-test")

        // 로컬에 이미 104화 읽음 기록 (order = 104)
        db.saveHistory(
            ReadHistoryRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                toonTitle = "Test Toon Sync",
                toonThumbUrl = "thumb",
                toonHref = "href",
                lastWrId = "104",
                lastEpisodeTitle = "Episode 104",
                lastEpisodeHref = "href/104",
                lastReadOrder = 104,
                totalEpisodes = 104,
                lastReadAt = 2000L,
                hasNew = false,
            )
        )

        // 원격에서 과거/하위 회차 (order = 103, 타임스탬프가 더 높더라도 회차 순번이 낮음)가 들어온 경우
        val payload = org.json.JSONObject().apply {
            put("favorites", org.json.JSONArray())
            put("history", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("sourceId", workId.sourceId)
                    put("toonId", workId.toonId)
                    put("toonTitle", "Test Toon Sync")
                    put("toonThumbUrl", "thumb")
                    put("toonHref", "href")
                    put("lastWrId", "103")
                    put("lastEpisodeTitle", "Episode 103")
                    put("lastEpisodeHref", "href/103")
                    put("lastReadOrder", 103)
                    put("totalEpisodes", 104)
                    put("lastReadAt", 3000L) // 타임스탬프는 더 늦지만 order는 낮음
                    put("hasNew", 1)
                })
            })
            put("readEpisodes", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("sourceId", workId.sourceId)
                    put("toonId", workId.toonId)
                    put("wrId", "104")
                    put("readAt", 2000L)
                    put("lastPage", 1)
                })
            })
        }

        val counts = adapter.applyRemoteChanges(payload, 3000L)

        // stale한 103은 기각되어 history 카운트는 0, readEpisodes 카운트는 1이어야 함
        assertThat(counts.history).isEqualTo(0)
        assertThat(counts.episodes).isEqualTo(1)

        val hist = db.getHistory(workId)
        assertThat(hist?.lastReadOrder).isEqualTo(104)
        assertThat(hist?.lastWrId).isEqualTo("104")
    }

    @Test
    fun applyRemoteChangesAcceptsHigherHistoryOrder() = runBlocking {
        val adapter = DesktopSyncStorageAdapter(db)
        val workId = WorkId("test_source", "toon-sync-elevate")

        // 로컬에 103화 읽음 기록 (order = 103)
        db.saveHistory(
            ReadHistoryRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                toonTitle = "Test Elevate",
                toonThumbUrl = "thumb",
                toonHref = "href",
                lastWrId = "103",
                lastEpisodeTitle = "Episode 103",
                lastEpisodeHref = "href/103",
                lastReadOrder = 103,
                totalEpisodes = 104,
                lastReadAt = 1000L,
                hasNew = true,
            )
        )

        // 원격에서 최신 회차 (order = 104)가 수신됨
        val payload = org.json.JSONObject().apply {
            put("favorites", org.json.JSONArray())
            put("history", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("sourceId", workId.sourceId)
                    put("toonId", workId.toonId)
                    put("toonTitle", "Test Elevate")
                    put("toonThumbUrl", "thumb")
                    put("toonHref", "href")
                    put("lastWrId", "104")
                    put("lastEpisodeTitle", "Episode 104")
                    put("lastEpisodeHref", "href/104")
                    put("lastReadOrder", 104)
                    put("totalEpisodes", 104)
                    put("lastReadAt", 2000L)
                    put("hasNew", 0)
                })
            })
            put("readEpisodes", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("sourceId", workId.sourceId)
                    put("toonId", workId.toonId)
                    put("wrId", "104")
                    put("readAt", 2000L)
                    put("lastPage", 1)
                })
            })
        }

        val counts = adapter.applyRemoteChanges(payload, 2000L)

        // 최신 104는 수용되어 history 1, episodes 1이어야 함
        assertThat(counts.history).isEqualTo(1)
        assertThat(counts.episodes).isEqualTo(1)

        val hist = db.getHistory(workId)
        assertThat(hist?.lastReadOrder).isEqualTo(104)
        assertThat(hist?.lastWrId).isEqualTo("104")
        assertThat(hist?.hasNew).isFalse()
    }

    @Test
    fun syncEpisodeCountsWithUnreadItemUpdatesCatalogAndProgress() = runBlocking {
        val workId = WorkId("test_source", "unread_toon")
        // No history exists for this toon
        assertThat(db.getHistory(workId)).isNull()
        assertThat(db.getCatalog(workId)).isNull()

        val item = ToonItem(
            id = "unread_toon",
            title = "Unread Toon",
            thumbUrl = "thumb",
            href = "href",
            sourceId = "test_source",
        )

        var updatedTotal: EpisodeListingTotal? = null
        var updatedProgress: String? = null

        repository.syncEpisodeCounts(listOf(item)) { id, total, progress ->
            if (id == workId) {
                updatedTotal = total
                updatedProgress = progress
            }
        }

        var attempts = 0
        while (updatedTotal == null && attempts < 50) {
            delay(50)
            attempts++
        }

        assertThat(updatedTotal?.count).isEqualTo(15) // TestSource has 15 episodes
        assertThat(updatedProgress).isEqualTo("15화")

        // Catalog record must be saved in database
        val catalogRecord = db.getCatalog(workId)
        assertThat(catalogRecord).isNotNull()
        assertThat(catalogRecord?.totalEpisodes).isEqualTo(15)

        // Reading history should NOT have been falsely created
        assertThat(db.getHistory(workId)).isNull()

        // Listing refreshProgress should display "15화" using catalog
        val refreshed = repository.refreshProgress(listOf(item))
        assertThat(refreshed.first().readProgress).isEqualTo("15화")
    }

    @Test
    fun catalogStoresOnlyCountedTotalsAndNeverErasures() = runBlocking {
        val workId = WorkId("test_source", "toon-counted")
        val counted = EpisodeListingTotal(count = 50, lastPage = 1, pageSize = 100, delta = -50)

        db.recordCatalogTotal(workId, counted, 500L)
        var record = db.getCatalog(workId)
        assertThat(record?.total()).isEqualTo(counted)
        assertThat(record?.updatedAt).isEqualTo(500L)

        // A fresh sweep may lower the count: episodes disappear from listings too.
        val shrunk = EpisodeListingTotal(count = 30, lastPage = 1, pageSize = 100, delta = -70)
        db.recordCatalogTotal(workId, shrunk, 100L)
        record = db.getCatalog(workId)
        assertThat(record?.total()).isEqualTo(shrunk)

        // A null observation is "not counted", never "erase the count that was taken".
        db.recordCatalogTotal(workId, null, 700L)
        record = db.getCatalog(workId)
        assertThat(record?.total()).isEqualTo(shrunk)

        // Batch save goes through the same single entry point.
        db.saveAllCatalog(
            listOf(
                ToonCatalogRecord.of(
                    WorkId("test_source", "toon-batch-new"),
                    EpisodeListingTotal(count = 20, lastPage = 2, pageSize = 10, delta = 0),
                    200L,
                ),
            ),
        )
        assertThat(db.getCatalog(WorkId("test_source", "toon-batch-new"))?.total()?.count).isEqualTo(20)
    }

    @Test
    fun episodeTotalIsCountedBySweepingAndThenRefreshedFromOnePage(): Unit = runBlocking {
        // 199 episodes over 2 pages of 100: the tail page holds 99, so naive math would say 200.
        val source = FakeSource().apply {
            episodePageSize = 100
            episodeReturnCount = 199
        }
        val registry = SourceRegistry(listOf(source))
        val repo = DesktopToonRepository(
            client = ToonClient(sources = { registry }),
            database = db,
            syncManager = null,
            downloadManager = null,
            now = { System.currentTimeMillis() },
            sources = registry,
            isSourceEnabled = { true },
            installedIds = { setOf("test_source") },
        )
        val item = ToonItem(
            id = "toon_two_pages",
            title = "Two Pages",
            thumbUrl = "thumb",
            href = "href",
            sourceId = "test_source",
        )

        // First resolution counts the listing: every page is read exactly once.
        val first = repo.fetchEpisodeMetadata(item)
        // naive = one full page + 99 on the tail = 199, which is the counted total: delta = 0.
        assertThat(first.total).isEqualTo(EpisodeListingTotal(count = 199, lastPage = 2, pageSize = 100, delta = 0))
        repo.saveCatalogTotal(item.workId(), first.total)
        assertThat(source.requestedPages).containsExactly(1, 2).inOrder()

        // Every later refresh reads exactly one page — the tail page, whose item count is the only
        // value the correction does not cache — and still reports 199.
        source.requestedPages.clear()
        val second = repo.fetchEpisodeMetadata(item)
        assertThat(second.total).isEqualTo(EpisodeListingTotal(count = 199, lastPage = 2, pageSize = 100, delta = 0))
        assertThat(source.requestedPages).containsExactly(2)
    }

    @Test
    fun changedPageCountTriggersExactlyOneRecount(): Unit = runBlocking {
        val source = FakeSource().apply {
            episodePageSize = 100
            episodeReturnCount = 199
        }
        val registry = SourceRegistry(listOf(source))
        val repo = DesktopToonRepository(
            client = ToonClient(sources = { registry }),
            database = db,
            syncManager = null,
            downloadManager = null,
            now = { System.currentTimeMillis() },
            sources = registry,
            isSourceEnabled = { true },
            installedIds = { setOf("test_source") },
        )
        val item = ToonItem(
            id = "toon_growing",
            title = "Growing",
            thumbUrl = "thumb",
            href = "href",
            sourceId = "test_source",
        )
        val first = repo.fetchEpisodeMetadata(item)
        repo.saveCatalogTotal(item.workId(), first.total)

        // A new episode appears: the listing grows to 3 pages, so the cached correction is obsolete.
        source.episodeReturnCount = 201
        source.requestedPages.clear()
        val grown = repo.fetchEpisodeMetadata(item)

        // Recounting must cover every page including the old tail: it became a middle page, so
        // trusting it would resurrect the stale total.
        assertThat(source.requestedPages).containsExactly(1, 2, 3)
        // naive = 2 full pages + 1 on the tail = 201, so the correction stays 0.
        assertThat(grown.total).isEqualTo(EpisodeListingTotal(count = 201, lastPage = 3, pageSize = 100, delta = 0))
    }

    @Test
    fun aNewEpisodeOnTheSamePageCountMovesTheTotalByOnePageRequest(): Unit = runBlocking {
        val source = FakeSource().apply {
            episodePageSize = 100
            episodeReturnCount = 199
        }
        val registry = SourceRegistry(listOf(source))
        val repo = DesktopToonRepository(
            client = ToonClient(sources = { registry }),
            database = db,
            syncManager = null,
            downloadManager = null,
            now = { System.currentTimeMillis() },
            sources = registry,
            isSourceEnabled = { true },
            installedIds = { setOf("test_source") },
        )
        val item = ToonItem(
            id = "toon_same_pages",
            title = "Same Pages",
            thumbUrl = "thumb",
            href = "href",
            sourceId = "test_source",
        )
        val first = repo.fetchEpisodeMetadata(item)
        repo.saveCatalogTotal(item.workId(), first.total)
        assertThat(first.total?.count).isEqualTo(199)

        // One new episode is published but the listing is still 2 pages. Nothing is recounted: the
        // tail page alone shows 100 items instead of 99, so the total must move to 200.
        source.episodeReturnCount = 200
        source.requestedPages.clear()
        val refreshed = repo.fetchEpisodeMetadata(item)

        assertThat(source.requestedPages).containsExactly(2)
        assertThat(refreshed.total?.count).isEqualTo(200)
        assertThat(refreshed.total?.lastPage).isEqualTo(2)
    }

    @Test
    fun aRemovedEpisodeOnTheSamePageCountMovesTheTotalDown(): Unit = runBlocking {
        val source = FakeSource().apply {
            episodePageSize = 100
            episodeReturnCount = 200
        }
        val registry = SourceRegistry(listOf(source))
        val repo = DesktopToonRepository(
            client = ToonClient(sources = { registry }),
            database = db,
            syncManager = null,
            downloadManager = null,
            now = { System.currentTimeMillis() },
            sources = registry,
            isSourceEnabled = { true },
            installedIds = { setOf("test_source") },
        )
        val item = ToonItem(
            id = "toon_shrinking",
            title = "Shrinking",
            thumbUrl = "thumb",
            href = "href",
            sourceId = "test_source",
        )
        repo.saveCatalogTotal(item.workId(), repo.fetchEpisodeMetadata(item).total)

        source.episodeReturnCount = 199
        source.requestedPages.clear()
        val refreshed = repo.fetchEpisodeMetadata(item)

        assertThat(source.requestedPages).containsExactly(2)
        assertThat(refreshed.total?.count).isEqualTo(199)
    }

    @Test
    fun syncEpisodeCountsPopulatesCatalogForUnreadBatchItemsWhenMissing() = runBlocking {
        // Multiple unread, non-favorite items missing from catalog (e.g. explore screen)
        val items = (1..3).map { idx ->
            ToonItem(
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
        while (callbackCount < 3 && attempts < 50) {
            delay(50)
            attempts++
        }

        assertThat(callbackCount).isEqualTo(3)
        for (item in items) {
            val record = db.getCatalog(item.workId())
            assertThat(record).isNotNull()
            assertThat(record?.totalEpisodes).isEqualTo(15)
        }

        // The works are still unread (nothing marked read), so they stay refresh candidates: the
        // filter is about unread episodes, not about whether a total is stored.
        val secondCallbackCount = java.util.concurrent.atomic.AtomicInteger()
        repository.syncEpisodeCounts(items) { _, _, _ ->
            secondCallbackCount.incrementAndGet()
        }
        var secondAttempts = 0
        while (secondCallbackCount.get() < 3 && secondAttempts < 100) {
            delay(50)
            secondAttempts++
        }
        assertThat(secondCallbackCount.get()).isEqualTo(3)
    }

    @Test
    fun syncEpisodeCountsQueriesServerBatchAndPopulatesCatalog() = runBlocking {
        val okClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val respJson = org.json.JSONObject().apply {
                    put("catalog", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("sourceId", "test_source")
                            put("toonId", "server_toon_1")
                            put("totalEpisodes", 77)
                            put("updatedAt", 1700000000000L)
                        })
                    })
                }
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(respJson.toString().toByteArray().toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val mockSyncManager = DesktopSyncManager(
            database = db,
            client = okClient,
        )

        val source = FakeSource()
        val registry = SourceRegistry(listOf(source))
        val repoWithSync = DesktopToonRepository(
            client = ToonClient(sources = { registry }),
            database = db,
            syncManager = mockSyncManager,
            downloadManager = null,
            now = { System.currentTimeMillis() },
            sources = registry,
            isSourceEnabled = { true },
            installedIds = { setOf("test_source") },
        )

        val item = ToonItem(
            id = "server_toon_1",
            title = "Server Toon",
            thumbUrl = "thumb",
            href = "href",
            sourceId = "test_source",
        )

        var updatedTotal: EpisodeListingTotal? = null
        var updatedProgress: String? = null
        repoWithSync.syncEpisodeCounts(listOf(item)) { _, total, progress ->
            updatedTotal = total
            updatedProgress = progress
        }

        var attempts = 0
        while (updatedTotal == null && attempts < 50) {
            delay(50)
            attempts++
        }

        // The server row carried no listing shape, so it was not adopted as a total; the source's own
        // listing was counted instead, and that count is what reaches the callback.
        assertThat(updatedTotal?.count).isEqualTo(15)
        assertThat(updatedProgress).isEqualTo("15화")
        val inDb = db.getCatalog(item.workId())
        assertThat(inDb).isNotNull()
        assertThat(inDb?.totalEpisodes).isEqualTo(15)

        repoWithSync.close()
    }

    @Test
    fun syncEpisodeCountsDoesNotDowngradeLocalHistoryWhenServerIsLower() = runBlocking {
        val reported = java.util.Collections.synchronizedList(mutableListOf<String>())
        val okClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.url.encodedPath.contains("/catalog/batch")) {
                    val respJson = org.json.JSONObject().apply {
                        put("catalog", org.json.JSONArray().apply {
                            put(org.json.JSONObject().apply {
                                put("sourceId", "test_source")
                                put("toonId", "toon_higher_local")
                                put("totalEpisodes", 50)
                                put("updatedAt", 1000L)
                            })
                        })
                    }
                    okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(respJson.toString().toByteArray().toResponseBody("application/json".toMediaType()))
                        .build()
                } else if (req.url.encodedPath.contains("/catalog/report")) {
                    reported.add(req.body?.let {
                        val buffer = okio.Buffer()
                        it.writeTo(buffer)
                        buffer.readUtf8()
                    }.orEmpty())
                    okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{\"status\":\"ok\"}".toByteArray().toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    okhttp3.Response.Builder()
                        .request(req)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("{}".toByteArray().toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val mockSyncManager = DesktopSyncManager(database = db, client = okClient)
        // The listing really holds 80 episodes; only the server catalog is stale at 50.
        val source = FakeSource().apply { episodeReturnCount = 80 }
        val registry = SourceRegistry(listOf(source))
        val repoWithSync = DesktopToonRepository(
            client = ToonClient(sources = { registry }),
            database = db,
            syncManager = mockSyncManager,
            downloadManager = null,
            now = { System.currentTimeMillis() },
            sources = registry,
            isSourceEnabled = { true },
            installedIds = { setOf("test_source") },
        )

        val workId = WorkId("test_source", "toon_higher_local")
        // Local history has 80 episodes read to order 80 (saved in DB directly without catalog record)
        db.saveHistory(
            ReadHistoryRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                toonTitle = "Title",
                toonThumbUrl = "thumb",
                toonHref = "href",
                lastWrId = "80",
                lastEpisodeTitle = "Ep 80",
                lastEpisodeHref = "href/80",
                lastReadOrder = 80,
                totalEpisodes = 80,
                lastReadAt = 2000L,
                hasNew = false,
            )
        )

        val item = ToonItem(
            id = workId.toonId,
            title = "Title",
            thumbUrl = "thumb",
            href = "href",
            sourceId = workId.sourceId,
        )

        var updatedTotal: EpisodeListingTotal? = null
        repoWithSync.syncEpisodeCounts(listOf(item)) { _, total, _ ->
            updatedTotal = total
        }

        var attempts = 0
        while (updatedTotal == null && attempts < 50) {
            delay(50)
            attempts++
        }

        // A shapeless server row (50) is not a total, so the local listing is counted instead: 80.
        assertThat(updatedTotal?.count).isEqualTo(80)
        val historyInDb = db.getHistory(workId)
        assertThat(historyInDb?.totalEpisodes).isEqualTo(80)

        // And our higher count 80 should have been reported back to server
        assertThat(reported).isNotEmpty()
        assertThat(reported.first()).contains("80")

        repoWithSync.close()
    }

    @Test
    fun anUncountedRowCannotEraseOrReplaceACountedTotal() = runBlocking {
        val workId = WorkId("test_source", "toon-inflated")
        val counted = EpisodeListingTotal(count = 199, lastPage = 2, pageSize = 100, delta = -1)
        db.recordCatalogTotal(workId, counted, 1000L)

        // A row without a shape is not a count, so it neither lowers nor erases what was counted.
        db.recordCatalogTotal(workId, null, 2000L)
        assertThat(db.getCatalog(workId)?.total()).isEqualTo(counted)

        // An older verified count also keeps the newer one intact.
        val older = EpisodeListingTotal(count = 180, lastPage = 2, pageSize = 100, delta = -20)
        db.recordCatalogTotal(workId, older, 3000L)
        assertThat(db.getCatalog(workId)?.total()).isEqualTo(older)
    }

    @Test
    fun getHistoryEnrichesTotalEpisodesAndHasNewFromCatalog() = runBlocking {
        val workId = WorkId("test_source", "toon_enrich")
        // History record has older count: read 10 of 10 (hasNew = false)
        db.saveHistory(
            ReadHistoryRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                toonTitle = "Title",
                toonThumbUrl = "thumb",
                toonHref = "href",
                lastWrId = "10",
                lastEpisodeTitle = "Ep 10",
                lastEpisodeHref = "href/10",
                lastReadOrder = 10,
                totalEpisodes = 10,
                lastReadAt = 1000L,
                hasNew = false,
            )
        )

        // Catalog has discovered new episodes: 25 total
        db.recordCatalogTotal(
            workId,
            EpisodeListingTotal(count = 25, lastPage = 1, pageSize = 100, delta = -75),
        )

        // getHistory(workId) should reflect 25 and hasNew = true (10 < 25)
        val single = repository.getHistory(workId)
        assertThat(single).isNotNull()
        assertThat(single?.totalEpisodes).isEqualTo(25)
        assertThat(single?.hasNew).isTrue()

        // getHistory(sourceId) should also reflect 25 and hasNew = true
        val list = repository.getHistory(workId.sourceId)
        assertThat(list).isNotEmpty()
        val fromList = list.first { it.workId() == workId }
        assertThat(fromList.totalEpisodes).isEqualTo(25)
        assertThat(fromList.hasNew).isTrue()

        // getCatalogTotal returns the counted total, never a history-derived number.
        assertThat(repository.getCatalogTotal(workId))
            .isEqualTo(EpisodeListingTotal(count = 25, lastPage = 1, pageSize = 100, delta = -75))
    }

    @Test
    fun toggleFavoriteEnrichesThumbnailFromHistoryWhenBlank() = runBlocking {
        val workId = WorkId("test_source", "toon_fav_thumb")
        db.saveHistory(
            ReadHistoryRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                toonTitle = "Title",
                toonThumbUrl = "https://example.com/hist-thumb.webp",
                toonHref = "href",
                lastWrId = "1",
                lastEpisodeTitle = "Ep 1",
                lastEpisodeHref = "href/1",
                lastReadOrder = 1,
                totalEpisodes = 1,
                lastReadAt = 1000L,
                hasNew = false,
            )
        )

        val itemWithBlankThumb = ToonItem(
            id = workId.toonId,
            title = "Title",
            thumbUrl = "",
            href = "href",
            sourceId = workId.sourceId,
        )

        val result = repository.toggleFavorite(itemWithBlankThumb)
        assertThat(result).isTrue()

        val saved = db.getFavorite(workId)
        assertThat(saved).isNotNull()
        assertThat(saved?.thumbUrl).isEqualTo("https://example.com/hist-thumb.webp")
    }
}
