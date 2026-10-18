package com.comics8.desktop.ui

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem
import com.comics8.core.model.ViewMode
import com.comics8.core.network.ToonClient
import com.comics8.core.source.ComicSource
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.js.JsPackStore
import com.comics8.core.source.network.NetworkSourceStore
import com.comics8.desktop.data.DesktopDatabase
import com.comics8.desktop.data.DesktopSourcePrefs
import com.comics8.desktop.data.DesktopToonRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DesktopAutoScrollTest {
    private lateinit var tempDir: File
    private lateinit var db: DesktopDatabase
    private lateinit var repository: DesktopToonRepository
    private lateinit var viewModel: DesktopViewModel

    private class FakeSource : ComicSource {
        override val id: String = "local"
        override val displayName: String = "Local"
        override val origin: String = "https://local"
        override val catalogs: List<SourceCatalog> = emptyList()
        override val defaultPolicy: RequestPolicy = RequestPolicy(userAgent = "test")
        override val episodePageSize: Int = 10
        override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp): ListingPage =
            ListingPage(emptyList(), 1, 1)
        override suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem> = emptyList()
        override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp): EpisodePage =
            EpisodePage(emptyList(), 1, 1)
        override suspend fun resolveImages(episode: EpisodeItem, toon: ToonItem, http: SourceHttp): List<String> =
            emptyList()
    }

    @Before
    fun setUp() {
        tempDir = createTempDirectory("desk-autoscroll").toFile()
        val dbFile = File(tempDir, "test.db")
        db = DesktopDatabase(dbFile)
        val registry = SourceRegistry(listOf(FakeSource()))
        DesktopSourcePrefs.registry = registry
        repository = DesktopToonRepository(
            client = ToonClient(sources = { registry }),
            database = db,
            syncManager = null,
            downloadManager = null,
            now = { System.currentTimeMillis() },
            sources = registry,
            isSourceEnabled = { true },
            installedIds = { setOf("local") },
        )
        val jsStore = JsPackStore(File(tempDir, "js"))
        val netStore = NetworkSourceStore(File(tempDir, "net.json"))
        viewModel = DesktopViewModel(repository, jsStore, netStore)
    }

    @After
    fun tearDown() {
        viewModel.close()
        db.close()
        tempDir.deleteRecursively()
    }

    @Test
    fun autoSwitchesToScrollForWebtoonAndRestoresOnNewEpisode() = runBlocking(Dispatchers.Main) {
        val series = ToonItem(
            id = "test_toon",
            title = "Test Toon",
            thumbUrl = "thumb",
            href = "href",
            sourceId = "local",
        )
        val ep1 = EpisodeItem(wrId = "ep1", title = "1화", date = "2026-01-01", thumbUrl = null, href = "href1")
        val ep2 = EpisodeItem(wrId = "ep2", title = "2화", date = "2026-01-02", thumbUrl = null, href = "href2")

        viewModel.openSeries(series)
        // User starts with PAGE view mode
        viewModel.setViewMode(ViewMode.PAGE)
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.PAGE)

        // Open ep1
        viewModel.openEpisode(ep1)
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.PAGE)

        // First image arrives with 800 x 5000 (ratio 6.25 >= 2.0)
        viewModel.recordImageAspectRatio(0, 800, 5000)

        // Should automatically switch to SCROLL mode with toast message
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.SCROLL)
        assertThat(viewModel.state.value.readerToastMessage).isEqualTo("웹툰 형식 감지: 스크롤 모드로 전환되었습니다")

        // Now open ep2
        viewModel.openEpisode(ep2)

        // ViewMode should restore back to PAGE! Toast message should be cleared!
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.PAGE)
        assertThat(viewModel.state.value.readerToastMessage).isNull()

        // If ep2 has a normal manga image (1000 x 1400 -> ratio 1.4 < 2.0)
        viewModel.recordImageAspectRatio(0, 1000, 1400)
        // Remains PAGE mode
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.PAGE)
        assertThat(viewModel.state.value.readerToastMessage).isNull()
    }

    @Test
    fun autoSwitchesToScrollWhenSecondPageIsWebtoon() = runBlocking(Dispatchers.Main) {
        val series = ToonItem(
            id = "test_toon2",
            title = "Test Toon 2",
            thumbUrl = "thumb",
            href = "href",
            sourceId = "local",
        )
        val ep1 = EpisodeItem(wrId = "ep1", title = "1화", date = "2026-01-01", thumbUrl = null, href = "href1")

        viewModel.openSeries(series)
        viewModel.setViewMode(ViewMode.PAGE)

        viewModel.openEpisode(ep1)
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.PAGE)

        // First page is a normal cover/notice image (1000 x 1000, ratio 1.0 < 2.0)
        viewModel.recordImageAspectRatio(0, 1000, 1000)
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.PAGE)

        // Second page (index 1) is a tall webtoon strip (800 x 4000, ratio 5.0 >= 2.0)
        viewModel.recordImageAspectRatio(1, 800, 4000)

        // Should switch to SCROLL!
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.SCROLL)
        assertThat(viewModel.state.value.readerToastMessage).isEqualTo("웹툰 형식 감지: 스크롤 모드로 전환되었습니다")
    }

    @Test
    fun doesNotAutoSwitchWhenOnlyThirdPageIsWebtoon() = runBlocking(Dispatchers.Main) {
        val series = ToonItem(
            id = "test_toon3",
            title = "Test Toon 3",
            thumbUrl = "thumb",
            href = "href",
            sourceId = "local",
        )
        val ep1 = EpisodeItem(wrId = "ep1", title = "1화", date = "2026-01-01", thumbUrl = null, href = "href1")

        viewModel.openSeries(series)
        viewModel.setViewMode(ViewMode.PAGE)

        viewModel.openEpisode(ep1)

        // First and second pages are normal manga
        viewModel.recordImageAspectRatio(0, 1000, 1400)
        viewModel.recordImageAspectRatio(1, 1000, 1400)
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.PAGE)

        // Third page (index 2) is tall, but candidate is only first 2 pages (index 0, 1)
        viewModel.recordImageAspectRatio(2, 800, 4000)
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.PAGE)
        assertThat(viewModel.state.value.readerToastMessage).isNull()
    }
}
