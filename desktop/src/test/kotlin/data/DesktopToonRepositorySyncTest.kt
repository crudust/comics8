package com.comics8.desktop.data

import com.comics8.core.model.EpisodeListingSnapshot
import com.comics8.core.model.EpisodeTotal
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
        // History only stores a count, so the denominator is an honest lower bound until the last
        // page of the listing is actually inspected.
        assertThat(refreshed.first().readProgress).isEqualTo("3/20+")
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

        var updatedTotal = 0
        var updatedProgress: String? = null

        repository.syncEpisodeCounts(listOf(item)) { id, total, progress ->
            if (id == workId) {
                updatedTotal = total
                updatedProgress = progress
            }
        }

        var attempts = 0
        while (updatedTotal == 0 && attempts < 50) {
            delay(50)
            attempts++
        }

        assertThat(updatedTotal).isEqualTo(15) // TestSource has 15 episodes
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
    fun desktopCatalogMonotonicUpsertDoesNotDowngrade() = runBlocking {
        val workId = WorkId("test_source", "toon-monotonic")

        db.recordCatalogTotal(workId, EpisodeTotal.AtLeast(50), 500L)
        var record = db.getCatalog(workId)
        assertThat(record?.totalEpisodes).isEqualTo(50)
        assertThat(record?.updatedAt).isEqualTo(500L)

        // Downgrade attempt: 30 episodes at t=100 -> rejected
        db.recordCatalogTotal(workId, EpisodeTotal.AtLeast(30), 100L)
        record = db.getCatalog(workId)
        assertThat(record?.totalEpisodes).isEqualTo(50)
        assertThat(record?.updatedAt).isEqualTo(500L)

        // Upgrade attempt: 60 episodes at t=600 -> accepted
        db.recordCatalogTotal(workId, EpisodeTotal.AtLeast(60), 600L)
        record = db.getCatalog(workId)
        assertThat(record?.totalEpisodes).isEqualTo(60)
        assertThat(record?.updatedAt).isEqualTo(600L)

        // Batch saveAllCatalog test
        db.saveAllCatalog(
            listOf(
                ToonCatalogRecord("test_source", "toon-monotonic", 40, 700L, exact = false),
                ToonCatalogRecord("test_source", "toon-batch-new", 20, 200L, exact = false),
            ),
        )
        record = db.getCatalog(workId)
        assertThat(record?.totalEpisodes).isEqualTo(60)
        assertThat(record?.updatedAt).isEqualTo(600L)

        val newRecord = db.getCatalog(WorkId("test_source", "toon-batch-new"))
        assertThat(newRecord?.totalEpisodes).isEqualTo(20)
        assertThat(newRecord?.updatedAt).isEqualTo(200L)
    }

    @Test
    fun episodeTotalIsProvenOnceAndThenDerivedFromASinglePage(): Unit = runBlocking {
        // 199 episodes over 2 pages of 100: the tail page holds 99.
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

        // First resolution proves the shape: page 1 + page 2.
        val first = repo.fetchEpisodeMetadata(item)
        assertThat(first.total).isEqualTo(EpisodeTotal.Exact(199))
        assertThat(first.snapshot).isEqualTo(EpisodeListingSnapshot(lastPage = 2, lastPageItemCount = 99))
        repo.saveCatalogTotal(item.workId(), first.total, snapshot = first.snapshot)
        assertThat(source.requestedPages).containsExactly(1, 2).inOrder()

        // Every later refresh asks for one page only and still reports the exact total.
        source.requestedPages.clear()
        val second = repo.fetchEpisodeMetadata(item)
        assertThat(second.total).isEqualTo(EpisodeTotal.Exact(199))
        assertThat(source.requestedPages).containsExactly(1)
    }

    @Test
    fun changedPageCountTriggersExactlyOneReVerification(): Unit = runBlocking {
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
        repo.saveCatalogTotal(item.workId(), first.total, snapshot = first.snapshot)

        // A new episode appears: the listing grows to 3 pages, so the cached tail is obsolete.
        source.episodeReturnCount = 201
        source.requestedPages.clear()
        val grown = repo.fetchEpisodeMetadata(item)

        // Re-verification must re-read the old tail page too: it became a middle page and its item
        // count changed (99 -> 100), so trusting it would resurrect the stale total.
        assertThat(source.requestedPages).containsExactly(1, 2, 3).inOrder()
        assertThat(grown.total).isEqualTo(EpisodeTotal.Exact(201))
        assertThat(grown.snapshot).isEqualTo(EpisodeListingSnapshot(lastPage = 3, lastPageItemCount = 1))
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
        var secondCallbackCount = 0
        repository.syncEpisodeCounts(items) { _, _, _ ->
            secondCallbackCount++
        }
        delay(200)
        assertThat(secondCallbackCount).isEqualTo(3)
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

        var updatedTotal = 0
        var updatedProgress: String? = null
        repoWithSync.syncEpisodeCounts(listOf(item)) { _, total, progress ->
            updatedTotal = total
            updatedProgress = progress
        }

        var attempts = 0
        while (updatedTotal == 0 && attempts < 50) {
            delay(50)
            attempts++
        }

        // The server answered, then the local listing was verified on top of it: the source serves
        // 15 episodes, and a verified sweep outranks the unverified crowdsourced number.
        assertThat(updatedTotal).isEqualTo(15)
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

        var updatedTotal = 0
        repoWithSync.syncEpisodeCounts(listOf(item)) { _, total, _ ->
            updatedTotal = total
        }

        var attempts = 0
        while (updatedTotal == 0 && attempts < 50) {
            delay(50)
            attempts++
        }

        // A stale server catalog (50) must never downgrade the verified local total (80).
        assertThat(updatedTotal).isEqualTo(80)
        val historyInDb = db.getHistory(workId)
        assertThat(historyInDb?.totalEpisodes).isEqualTo(80)

        // And our higher count 80 should have been reported back to server
        assertThat(reported).isNotEmpty()
        assertThat(reported.first()).contains("80")

        repoWithSync.close()
    }

    @Test
    fun catalogExactObservationCorrectsAnInflatedTotalDownward() = runBlocking {
        val workId = WorkId("test_source", "toon-inflated")
        // An old page-rounded estimate froze the catalog at 200 for a 199 episode work.
        db.recordCatalogTotal(workId, EpisodeTotal.AtLeast(200), 1000L)
        assertThat(db.getCatalog(workId)?.totalEpisodes).isEqualTo(200)

        // A lower bound cannot lower it...
        db.recordCatalogTotal(workId, EpisodeTotal.AtLeast(199), 2000L)
        assertThat(db.getCatalog(workId)?.totalEpisodes).isEqualTo(200)

        // ...but an exact last-page observation can, and it flips the row to exact.
        db.recordCatalogTotal(workId, EpisodeTotal.Exact(199), 3000L)
        val corrected = db.getCatalog(workId)
        assertThat(corrected?.totalEpisodes).isEqualTo(199)
        assertThat(corrected?.exact).isTrue()
        assertThat(corrected?.updatedAt).isEqualTo(3000L)

        // Records saved from history never downgrade exactness back to a lower bound.
        db.saveAllCatalog(listOf(ToonCatalogRecord("test_source", "toon-inflated", 199, 4000L, exact = false)))
        assertThat(db.getCatalog(workId)?.exact).isTrue()
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
        db.recordCatalogTotal(workId, EpisodeTotal.AtLeast(25))

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

        // getCatalogTotal should return 25 as a lower bound
        assertThat(repository.getCatalogTotal(workId)).isEqualTo(EpisodeTotal.AtLeast(25))
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
