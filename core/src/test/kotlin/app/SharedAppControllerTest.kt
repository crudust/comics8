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
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SharedAppControllerTest {
    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var store: InMemoryComicLocalStore
    private lateinit var prefs: InMemoryKeyValuePrefs
    private lateinit var sourceSettings: StoredSourceSettings
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

        val registry = SourceRegistry(listOf(StubSource()))
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

    private class StubSource : ComicSource {
        override val id = "stub"
        override val displayName = "Stub"
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
                        sourceId = "stub",
                    ),
                ),
                currentPage = 1,
                lastPage = 1,
            )

        override suspend fun search(query: SearchQuery, http: SourceHttp) = emptyList<ToonItem>()

        override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp) =
            EpisodePage(
                items = listOf(
                    EpisodeItem("ep2", "2화", null, null, "https://example.com/ep2"),
                    EpisodeItem("ep1", "1화", null, null, "https://example.com/ep1"),
                ),
                currentPage = 1,
                lastPage = 1,
            )

        override suspend fun resolveImages(episode: EpisodeItem, toon: ToonItem, http: SourceHttp) =
            listOf("https://example.com/1.jpg")
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
