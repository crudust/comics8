package com.comics8.core.app

import com.comics8.core.data.InMemoryComicLocalStore
import com.comics8.core.data.InMemoryKeyValuePrefs
import com.comics8.core.data.SharedToonRepository
import com.comics8.core.i18n.AppLanguage
import com.comics8.core.i18n.AppStrings
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.model.AppUpdateState
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
import com.comics8.core.source.SourcePreferenceStore
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.StoredSourceSettings
import com.comics8.core.source.WorkId
import com.comics8.core.source.js.JsPackStore
import com.comics8.core.source.network.NetworkSourceStore
import com.comics8.core.sync.BaseSyncManager
import com.comics8.core.sync.StoreSyncStorageAdapter
import com.comics8.core.sync.SyncConstants
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory

class SharedAppControllerTest {
    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: InMemoryComicLocalStore
    private lateinit var prefs: InMemoryKeyValuePrefs
    private lateinit var sourceSettings: StoredSourceSettings
    private lateinit var registry: SourceRegistry
    private lateinit var controller: SharedAppController

    @Before
    fun setUp() {
        tempDir = createTempDirectory("shared-ctrl").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        store = InMemoryComicLocalStore()
        prefs = InMemoryKeyValuePrefs()
        sourceSettings = StoredSourceSettings(MapSourcePreferenceStore())
        sourceSettings.setInstalledIds(setOf(WorkId.LOCAL_SOURCE, "stub"))
        sourceSettings.setActiveSourceId("stub")

        registry = SourceRegistry(listOf(StubSource()))
        val repo = SharedToonRepository(
            client = ToonClient(sources = { registry }),
            store = store,
            sourceSettings = sourceSettings,
            sources = registry,
            isSourceEnabled = { sourceSettings.isEnabled(it) },
            installedIds = { sourceSettings.installedIds() },
        )
        val platform = object : PlatformHost {
            override val prefs = this@SharedAppControllerTest.prefs
            override val sourceSettings = this@SharedAppControllerTest.sourceSettings
            override val localLibraryIndexFile = File(tempDir, "local.json")
            override fun materializeLocalRoots() = emptyList<File>()
            override fun clearImageMemory(role: ImageCacheRole) {}
            override fun evictReaderImage(url: String) {}
            override val appVersionName = "1.0.0"
            override val appVersionCode = 1
            override suspend fun checkForAppUpdate(serverUrl: String, strings: AppStrings) =
                AppUpdateState(currentVersion = "1.0.0", latestVersion = "1.0.0")
            override suspend fun downloadAndInstallUpdate(downloadUrl: String, onProgress: (Float) -> Unit) =
                Result.success(Unit)
        }
        controller = SharedAppController(
            scope = scope,
            repository = repo,
            jsPackStore = JsPackStore(File(tempDir, "js")),
            networkSourceStore = NetworkSourceStore(File(tempDir, "net.json")),
            platform = platform,
        )
    }

    @After
    fun tearDown() {
        controller.close()
        scope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun navigationAndReaderAutoSwitchWork() = runBlocking {
        assertThat(controller.state.value.activeSourceId).isEqualTo("stub")
        val loaded = withTimeout(5_000) { controller.state.first { it.items.isNotEmpty() } }
        assertThat(loaded.items).hasSize(1)

        val series = loaded.items.first()
        controller.openSeries(series)
        assertThat(controller.state.value.screen).isEqualTo(AppScreen.Series)
        val withEps = withTimeout(5_000) { controller.state.first { it.episodes.size == 2 } }
        assertThat(withEps.episodes).hasSize(2)

        val ep = withEps.episodes.first()
        controller.openEpisode(ep)
        assertThat(controller.state.value.screen).isEqualTo(AppScreen.Reader)
        val withImages = withTimeout(5_000) { controller.state.first { it.readerImages.isNotEmpty() } }
        assertThat(withImages.readerImages).containsExactly("https://example.com/1.jpg")

        // Wide webtoon strip triggers auto switch from AUTO -> SCROLL
        controller.recordImageAspectRatio(0, 800, 3000)
        assertThat(controller.state.value.viewMode).isEqualTo(ViewMode.SCROLL)

        // goBack steps Reader -> Series -> Browse
        assertThat(controller.goBack()).isTrue()
        assertThat(controller.state.value.screen).isEqualTo(AppScreen.Series)
        assertThat(controller.goBack()).isTrue()
        assertThat(controller.state.value.screen).isEqualTo(AppScreen.Browse)
    }

    @Test
    fun languageAndSettingsNavigationUpdateState() {
        controller.setAppLanguage(AppLanguage.EN)
        assertThat(controller.appLanguage).isEqualTo(AppLanguage.EN)
        assertThat(controller.state.value.appLanguage).isEqualTo(AppLanguage.EN)

        controller.openSettings(SettingsCategory.READER)
        assertThat(controller.state.value.screen).isEqualTo(AppScreen.Settings)
        assertThat(controller.state.value.selectedSettingsCategory).isEqualTo(SettingsCategory.READER)

        assertThat(controller.goBack()).isTrue()
        assertThat(controller.state.value.screen).isEqualTo(AppScreen.Settings)
        assertThat(controller.state.value.selectedSettingsCategory).isNull()

        assertThat(controller.goBack()).isTrue()
        assertThat(controller.state.value.screen).isEqualTo(AppScreen.Browse)
    }

    @Test
    fun readerSettingsPersistPerWork() = runBlocking {
        val loaded = withTimeout(5_000) { controller.state.first { it.items.isNotEmpty() } }
        val series = loaded.items.first()
        controller.openSeries(series)
        controller.setViewMode(ViewMode.DUAL)
        controller.setReadDirection(ReadDirection.LEFT_TO_RIGHT)
        controller.setSplitMode(SplitMode.SLICE)

        withTimeout(5_000) {
            while (controller.repository.getReaderSetting(series.workId())?.splitMode != SplitMode.SLICE) {
                delay(20)
            }
        }
        val saved = controller.repository.getReaderSetting(series.workId())
        assertThat(saved?.viewMode).isEqualTo(ViewMode.DUAL)
        assertThat(saved?.readDirection).isEqualTo(ReadDirection.LEFT_TO_RIGHT)
        assertThat(saved?.splitMode).isEqualTo(SplitMode.SLICE)
    }

    @Test
    fun favoriteToggleAndHistoryLifecycle() = runBlocking {
        val loaded = withTimeout(5_000) { controller.state.first { it.items.isNotEmpty() } }
        val series = loaded.items.first()

        controller.openSeries(series)
        val withEps = withTimeout(5_000) { controller.state.first { it.episodes.size == 2 } }
        controller.toggleFavorite()
        withTimeout(5_000) { controller.state.first { it.seriesFavorited } }
        assertThat(store.hasFavorite(series.workId())).isTrue()

        controller.openEpisode(withEps.episodes.first())
        withTimeout(5_000) { controller.state.first { it.readerImages.isNotEmpty() } }
        withTimeout(5_000) {
            while (store.history.isEmpty()) delay(20)
        }

        controller.closeReader()
        controller.openHistory()
        val withHist = withTimeout(5_000) { controller.state.first { !it.historyLoading && it.historyItems.isNotEmpty() } }
        assertThat(withHist.historyItems).hasSize(1)

        controller.deleteHistory(series.workId())
        withTimeout(5_000) { controller.state.first { it.historyItems.isEmpty() } }

        controller.openEpisode(withEps.episodes.first())
        withTimeout(5_000) {
            while (store.history.isEmpty()) delay(20)
        }
        controller.openHistory()
        withTimeout(5_000) { controller.state.first { it.historyItems.isNotEmpty() } }
        controller.clearAllHistory()
        withTimeout(5_000) { controller.state.first { it.historyItems.isEmpty() } }
        assertThat(store.history).isEmpty()
    }

    @Test
    fun episodeNavigationPrevAndNext() = runBlocking {
        val loaded = withTimeout(5_000) { controller.state.first { it.items.isNotEmpty() } }
        val series = loaded.items.first()
        controller.openSeries(series)
        val withEps = withTimeout(5_000) { controller.state.first { it.episodes.size == 2 } }
        val ep1 = withEps.episodes.first { it.wrId == "ep1" }

        controller.openEpisode(ep1)
        val ep1Images = withTimeout(5_000) { controller.state.first { it.readerImages.size == 3 } }
        assertThat(ep1Images.currentEpisode?.wrId).isEqualTo("ep1")

        controller.savePage(1)
        controller.flushPendingPageSave()
        withTimeout(5_000) {
            while (store.getReadEpisode(series.workId(), "ep1")?.lastPage != 1) delay(20)
        }

        controller.openNextEpisode()
        val ep2State = withTimeout(5_000) {
            controller.state.first { it.currentEpisode?.wrId == "ep2" && it.readerImages.size == 1 }
        }
        assertThat(ep2State.currentEpisode?.wrId).isEqualTo("ep2")

        controller.openPrevEpisode()
        val backEp1 = withTimeout(5_000) {
            controller.state.first { it.currentEpisode?.wrId == "ep1" && it.readerImages.size == 3 }
        }
        assertThat(backEp1.currentEpisode?.wrId).isEqualTo("ep1")
    }

    @Test
    fun sourceEnableDisableAndReorder() {
        registry.add(StubSource(sourceId = "stub2"))
        sourceSettings.setInstalledIds(setOf(WorkId.LOCAL_SOURCE, "stub", "stub2"))
        controller.setSourceEnabled("stub2", true)

        assertThat(controller.state.value.installedSources.map { it.id })
            .containsAtLeast(WorkId.LOCAL_SOURCE, "stub", "stub2")

        controller.reorderOnlineSources(0, 1)
        assertThat(controller.state.value.installedSources.filter { it.id != WorkId.LOCAL_SOURCE }.map { it.id })
            .containsExactly("stub2", "stub")
            .inOrder()

        controller.setSourceEnabled("stub", false)
        assertThat(controller.state.value.activeSourceId).isEqualTo(WorkId.LOCAL_SOURCE)
        assertThat(controller.state.value.installedSources.map { it.id }).doesNotContain("stub")
    }

    @Test
    fun searchSubmitAndBackNavigation() = runBlocking {
        withTimeout(5_000) { controller.state.first { it.items.isNotEmpty() } }

        controller.toggleSearchBar()
        assertThat(controller.state.value.searchExpanded).isTrue()

        controller.updateSearchInput("Series")
        controller.submitSearch()

        val searchDone = withTimeout(5_000) {
            controller.state.first { it.isSearch && !it.loading && it.items.isNotEmpty() }
        }
        assertThat(searchDone.searchQuery).isEqualTo("Series")
        assertThat(searchDone.items.single().id).isEqualTo("search-s1")

        assertThat(controller.goBack()).isTrue()
        assertThat(controller.state.value.isSearch).isFalse()
        assertThat(controller.state.value.screen).isEqualTo(AppScreen.Browse)
    }

    @Test
    fun backupExportAndImportRoundTrip() = runBlocking {
        val loaded = withTimeout(5_000) { controller.state.first { it.items.isNotEmpty() } }
        val series = loaded.items.first()
        controller.openSeries(series)
        controller.toggleFavorite()
        controller.setViewMode(ViewMode.DUAL)
        controller.setSplitMode(SplitMode.SLICE)

        withTimeout(5_000) {
            while (!store.hasFavorite(series.workId()) ||
                controller.repository.getReaderSetting(series.workId())?.splitMode != SplitMode.SLICE
            ) {
                delay(20)
            }
        }

        var exportedJson: String? = null
        controller.exportBackupWithWriter(
            writeJson = { json -> exportedJson = json },
            onResult = { ok, _ -> assertThat(ok).isTrue() },
        )
        withTimeout(5_000) {
            while (exportedJson == null) delay(20)
        }

        store.favorites.clear()
        store.settings.clear()
        assertThat(store.hasFavorite(series.workId())).isFalse()

        var importOk: Boolean? = null
        controller.importBackupWithReader(
            readJson = { exportedJson!! },
            onResult = { ok, _ -> importOk = ok },
        )
        withTimeout(5_000) {
            while (importOk == null) delay(20)
        }
        assertThat(importOk).isTrue()
        assertThat(store.hasFavorite(series.workId())).isTrue()
        assertThat(controller.repository.getReaderSetting(series.workId())?.viewMode).isEqualTo(ViewMode.DUAL)
        assertThat(controller.repository.getReaderSetting(series.workId())?.splitMode).isEqualTo(SplitMode.SLICE)
    }

    @Test
    fun closeReaderUpdatesEpisodeListLastReadPageForReopen() = runBlocking {
        val loaded = withTimeout(5_000) { controller.state.first { it.items.isNotEmpty() } }
        val series = loaded.items.first()
        controller.openSeries(series)
        val withEps = withTimeout(5_000) { controller.state.first { it.episodes.size == 2 } }
        val ep1 = withEps.episodes.first { it.wrId == "ep1" }

        controller.openEpisode(ep1)
        withTimeout(5_000) { controller.state.first { it.readerImages.size == 3 } }

        controller.savePage(1)
        controller.closeReader()

        withTimeout(5_000) {
            while (store.getReadEpisode(series.workId(), "ep1")?.lastPage != 1) delay(20)
        }
        val updatedEp1 = controller.state.value.episodes.first { it.wrId == "ep1" }
        assertThat(updatedEp1.lastReadPage).isEqualTo(1)

        // Re-opening from the in-memory series episode list preserves page 1 in state and DB
        controller.openEpisode(updatedEp1)
        withTimeout(5_000) { controller.state.first { it.readerImages.size == 3 } }
        assertThat(controller.state.value.currentEpisode?.lastReadPage).isEqualTo(1)
        assertThat(store.getReadEpisode(series.workId(), "ep1")?.lastPage).isEqualTo(1)
    }

    @Test
    fun readerDwellSyncRespectsDwellCooldownAndBackgroundFlush() = runBlocking {
        val deltaPayloads = CopyOnWriteArrayList<JSONObject>()
        val okClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                val bodyJson = if (req.method == "POST") {
                    val buf = okio.Buffer()
                    req.body?.writeTo(buf)
                    JSONObject(buf.readUtf8())
                } else {
                    JSONObject()
                }
                if (path.endsWith("/sync/delta")) {
                    deltaPayloads.add(bodyJson)
                    val resp = JSONObject()
                        .put("status", "success")
                        .put("serverTime", System.currentTimeMillis())
                        .put("changes", JSONObject())
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(resp.toString().toResponseBody("application/json".toMediaType()))
                        .build()
                } else {
                    val resp = JSONObject()
                        .put("version", 2)
                        .put("exportedAt", System.currentTimeMillis())
                        .put("favorites", org.json.JSONArray())
                        .put("history", org.json.JSONArray())
                        .put("readEpisodes", org.json.JSONArray())
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(resp.toString().toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val syncPrefs = InMemoryKeyValuePrefs().apply {
            putString(SyncConstants.KEY_SERVER_URL, "https://sync.example.com")
            putString(SyncConstants.KEY_AUTO_SYNC, "true")
            putString(SyncConstants.KEY_LAST_SYNCED_AT, "1")
            putString(SyncConstants.KEY_LAST_LOCAL_SYNC_AT, "1")
        }
        val syncManager = BaseSyncManager(
            storage = StoreSyncStorageAdapter(store, syncPrefs),
            client = okClient,
        )
        val syncRepo = SharedToonRepository(
            client = ToonClient(sources = { registry }),
            store = store,
            sourceSettings = sourceSettings,
            sources = registry,
            syncManager = syncManager,
            isSourceEnabled = { sourceSettings.isEnabled(it) },
            installedIds = { sourceSettings.installedIds() },
        )
        val syncPlatform = object : PlatformHost {
            override val prefs = this@SharedAppControllerTest.prefs
            override val sourceSettings = this@SharedAppControllerTest.sourceSettings
            override val localLibraryIndexFile = File(tempDir, "local-sync.json")
            override fun materializeLocalRoots() = emptyList<File>()
            override fun clearImageMemory(role: ImageCacheRole) {}
            override fun evictReaderImage(url: String) {}
            override val appVersionName = "1.0.0"
            override val appVersionCode = 1
            override suspend fun checkForAppUpdate(serverUrl: String, strings: AppStrings) =
                AppUpdateState(currentVersion = "1.0.0", latestVersion = "1.0.0")
            override suspend fun downloadAndInstallUpdate(downloadUrl: String, onProgress: (Float) -> Unit) =
                Result.success(Unit)
        }
        val syncController = SharedAppController(
            scope = scope,
            repository = syncRepo,
            jsPackStore = JsPackStore(File(tempDir, "js-sync")),
            networkSourceStore = NetworkSourceStore(File(tempDir, "net-sync.json")),
            platform = syncPlatform,
        )
        try {
            var fakeNow = 1_000_000L
            val coordinator = ReaderSessionCoordinator(
                controller = syncController,
                pageSaveDebounceMs = 20L,
                pageDwellSyncDelayMs = 80L,
                pageDwellSyncCooldownMs = 300L,
                nowMs = { fakeNow },
            )
            val loaded = withTimeout(5_000) { syncController.state.first { it.items.isNotEmpty() } }
            val series = loaded.items.first()
            syncController.openSeries(series)
            val withEps = withTimeout(5_000) { syncController.state.first { it.episodes.size == 2 } }
            val ep1 = withEps.episodes.first { it.wrId == "ep1" }

            // Wait for initial launch delta sync to settle, then clear recorded payloads
            withTimeout(5_000) { while (deltaPayloads.isEmpty()) delay(20) }
            deltaPayloads.clear()

            // Avoid openEpisode's 2.5s debounced push racing the short dwell window in this test
            syncManager.setAutoSyncEnabled(false)
            coordinator.openEpisode(ep1)
            withTimeout(5_000) { syncController.state.first { it.readerImages.size == 3 } }
            fakeNow += 500L
            syncManager.setAutoSyncEnabled(true)
            deltaPayloads.clear()

            // Rapid page flip within dwell delay: page 0 -> page 1 immediately, no dwell sync before 80ms
            coordinator.savePage(0)
            delay(30)
            coordinator.savePage(1)
            delay(30)
            assertThat(deltaPayloads).isEmpty()

            // Stay on page 1 past dwell delay (80ms) -> 1 dwell sync with lastPage == 1
            withTimeout(5_000) { while (deltaPayloads.isEmpty()) delay(20) }
            assertThat(deltaPayloads).hasSize(1)
            val firstEpisodes = deltaPayloads.last().getJSONObject("changes").getJSONArray("readEpisodes")
            assertThat(firstEpisodes.getJSONObject(0).getInt("lastPage")).isEqualTo(1)

            // Immediate background flush on page 2 saves locally and syncs immediately
            deltaPayloads.clear()
            coordinator.savePage(2)
            coordinator.flushAndSyncOnBackground()
            withTimeout(5_000) { while (deltaPayloads.isEmpty()) delay(20) }
            val bgEpisodes = deltaPayloads.last().getJSONObject("changes").getJSONArray("readEpisodes")
            assertThat(bgEpisodes.getJSONObject(0).getInt("lastPage")).isEqualTo(-3)

            coordinator.close()
        } finally {
            syncController.close()
            syncManager.close()
        }
    }

    @Test
    fun setViewModeSplitPersistsSplitAndSyncsSlice() = runBlocking {
        val loaded = withTimeout(5_000) { controller.state.first { it.items.isNotEmpty() } }
        val series = loaded.items.first()
        controller.openSeries(series)
        controller.setViewMode(ViewMode.SPLIT)

        withTimeout(5_000) {
            while (controller.repository.getReaderSetting(series.workId())?.viewMode != ViewMode.SPLIT) {
                delay(20)
            }
        }
        val saved = controller.repository.getReaderSetting(series.workId())
        assertThat(saved?.viewMode).isEqualTo(ViewMode.SPLIT)
        assertThat(saved?.splitMode).isEqualTo(SplitMode.SLICE)
    }

    @Test
    fun updateViewportSizeDoesNotClobberExplicitViewModeWhenPersistingGlobalDefaults() = runBlocking {
        val repo = SharedToonRepository(
            client = ToonClient(sources = { registry }),
            store = store,
            sourceSettings = sourceSettings,
            sources = registry,
            isSourceEnabled = { sourceSettings.isEnabled(it) },
            installedIds = { sourceSettings.installedIds() },
        )
        val persistentPlatform = object : PlatformHost {
            override val prefs = this@SharedAppControllerTest.prefs
            override val sourceSettings = this@SharedAppControllerTest.sourceSettings
            override val localLibraryIndexFile = File(tempDir, "local-persist.json")
            override fun materializeLocalRoots() = emptyList<File>()
            override fun clearImageMemory(role: ImageCacheRole) {}
            override fun evictReaderImage(url: String) {}
            override val persistGlobalReaderDefaults = true
            override val appVersionName = "1.0.0"
            override val appVersionCode = 1
            override suspend fun checkForAppUpdate(serverUrl: String, strings: AppStrings) =
                AppUpdateState(currentVersion = "1.0.0", latestVersion = "1.0.0")
            override suspend fun downloadAndInstallUpdate(downloadUrl: String, onProgress: (Float) -> Unit) =
                Result.success(Unit)
        }
        val persistentController = SharedAppController(
            scope = scope,
            repository = repo,
            jsPackStore = JsPackStore(File(tempDir, "js-persist")),
            networkSourceStore = NetworkSourceStore(File(tempDir, "net-persist.json")),
            platform = persistentPlatform,
        )
        try {
            val loaded = withTimeout(5_000) { persistentController.state.first { it.items.isNotEmpty() } }
            persistentController.openSeries(loaded.items.first())
            persistentController.setViewMode(ViewMode.SPLIT)
            persistentController.updateViewportSize(800, 420)

            assertThat(persistentController.state.value.viewMode).isEqualTo(ViewMode.SPLIT)
            assertThat(persistentController.state.value.viewportWidthDp).isEqualTo(800)
            assertThat(persistentController.state.value.viewportHeightDp).isEqualTo(420)
        } finally {
            persistentController.close()
        }
    }

    private class StubSource(
        private val sourceId: String = "stub",
    ) : ComicSource {
        override val id = sourceId
        override val displayName = sourceId
        override val origin = "https://example.com"
        override val catalogs = listOf(SourceCatalog("LATEST", "Latest", paginated = true))
        override val defaultPolicy = RequestPolicy("test")

        override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp) =
            ListingPage(
                items = listOf(
                    ToonItem(
                        id = "s1",
                        title = "Series 1",
                        thumbUrl = "https://example.com/t.jpg",
                        href = "https://example.com/s1",
                        sourceId = sourceId,
                    ),
                ),
                currentPage = 1,
                lastPage = 1,
            )

        override suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem> =
            if (query.text.contains("Series", ignoreCase = true)) {
                listOf(
                    ToonItem(
                        id = "search-s1",
                        title = "Series 1",
                        thumbUrl = "https://example.com/t.jpg",
                        href = "https://example.com/s1",
                        sourceId = sourceId,
                    ),
                )
            } else {
                emptyList()
            }

        override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp) =
            EpisodePage(
                items = listOf(
                    EpisodeItem("ep2", "2화", null, null, "https://example.com/ep2"),
                    EpisodeItem("ep1", "1화", null, null, "https://example.com/ep1"),
                ),
                currentPage = 1,
                lastPage = 1,
            )

        override suspend fun resolveImages(episode: EpisodeItem, item: ToonItem, http: SourceHttp) =
            if (episode.wrId == "ep1") {
                listOf(
                    "https://example.com/1.jpg",
                    "https://example.com/2.jpg",
                    "https://example.com/3.jpg",
                )
            } else {
                listOf("https://example.com/1.jpg")
            }
    }

    private class MapSourcePreferenceStore : SourcePreferenceStore {
        private val strings = linkedMapOf<String, String>()
        private val booleans = linkedMapOf<String, Boolean>()
        override fun contains(key: String) = key in strings || key in booleans
        override fun getString(key: String) = strings[key]
        override fun putString(key: String, value: String) { strings[key] = value }
        override fun remove(key: String) {
            strings.remove(key)
            booleans.remove(key)
        }
        override fun getBoolean(key: String, default: Boolean) = booleans[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { booleans[key] = value }
    }
}
