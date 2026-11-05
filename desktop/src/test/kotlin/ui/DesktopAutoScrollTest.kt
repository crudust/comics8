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

    private fun series(id: String) = ToonItem(
        id = id,
        title = "Test Toon $id",
        thumbUrl = "thumb",
        href = "href",
        sourceId = "local",
    )

    @Test
    fun autoModeSwitchesWebtoonToScrollAndRestoresAutoOnNewEpisode() = runBlocking(Dispatchers.Main) {
        val series = series("auto_toon")
        val ep1 = EpisodeItem(wrId = "ep1", title = "1화", date = "2026-01-01", thumbUrl = null, href = "href1")
        val ep2 = EpisodeItem(wrId = "ep2", title = "2화", date = "2026-01-02", thumbUrl = null, href = "href2")

        viewModel.openSeries(series)
        viewModel.updateViewportSize(400, 900)
        viewModel.setViewMode(ViewMode.AUTO)

        viewModel.openEpisode(ep1)
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.AUTO)
        // A standard comic book page renders single-page while AUTO is undecided.
        assertThat(viewModel.state.value.effectiveViewMode).isEqualTo(ViewMode.PAGE)

        // First image arrives with 800 x 5000 (ratio 6.25 >= 2.0) -> webtoon
        viewModel.recordImageAspectRatio(0, 800, 5000)

        // AUTO switches to SCROLL and surfaces a toast (copy is locale-dependent, so only the
        // presence of the notification is asserted here).
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.SCROLL)
        assertThat(viewModel.state.value.readerToastMessage).isNotNull()

        // The explicit switch belongs to this episode only: the next one starts from AUTO again.
        viewModel.openEpisode(ep2)
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.AUTO)
        assertThat(viewModel.state.value.readerToastMessage).isNull()

        // Normal manga image (1000 x 1400 -> ratio 1.4 < 2.0) keeps the AUTO resolution.
        viewModel.recordImageAspectRatio(0, 1000, 1400)
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.AUTO)
        assertThat(viewModel.state.value.effectiveViewMode).isEqualTo(ViewMode.PAGE)
        assertThat(viewModel.state.value.readerToastMessage).isNull()
    }

    @Test
    fun explicitPageModeIsNeverAutoSwitchedForWebtoons() = runBlocking(Dispatchers.Main) {
        val ep1 = EpisodeItem(wrId = "ep1", title = "1화", date = "2026-01-01", thumbUrl = null, href = "href1")

        viewModel.openSeries(series("explicit_page"))
        viewModel.updateViewportSize(400, 900)
        viewModel.setViewMode(ViewMode.PAGE)
        viewModel.openEpisode(ep1)

        viewModel.recordImageAspectRatio(0, 800, 5000)

        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.PAGE)
        assertThat(viewModel.state.value.effectiveViewMode).isEqualTo(ViewMode.PAGE)
        assertThat(viewModel.state.value.readerToastMessage).isNull()
    }

    @Test
    fun explicitDualModeIsNeverAutoSwitchedForWebtoons() = runBlocking(Dispatchers.Main) {
        val ep1 = EpisodeItem(wrId = "ep1", title = "1화", date = "2026-01-01", thumbUrl = null, href = "href1")

        viewModel.openSeries(series("explicit_dual"))
        viewModel.updateViewportSize(1280, 800)
        viewModel.setViewMode(ViewMode.DUAL)
        viewModel.openEpisode(ep1)

        viewModel.recordImageAspectRatio(0, 800, 5000)

        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.DUAL)
        assertThat(viewModel.state.value.effectiveViewMode).isEqualTo(ViewMode.DUAL)
        assertThat(viewModel.state.value.readerToastMessage).isNull()
    }

    @Test
    fun autoModeResolvesDualOnWideViewport() = runBlocking(Dispatchers.Main) {
        viewModel.openSeries(series("auto_wide"))
        viewModel.updateViewportSize(1280, 800)
        viewModel.setViewMode(ViewMode.AUTO)

        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.AUTO)
        assertThat(viewModel.state.value.effectiveViewMode).isEqualTo(ViewMode.DUAL)
    }

    @Test
    fun autoModeSwitchesWhenSecondPageIsWebtoon() = runBlocking(Dispatchers.Main) {
        val ep1 = EpisodeItem(wrId = "ep1", title = "1화", date = "2026-01-01", thumbUrl = null, href = "href1")

        viewModel.openSeries(series("auto_second"))
        viewModel.updateViewportSize(400, 900)
        viewModel.setViewMode(ViewMode.AUTO)
        viewModel.openEpisode(ep1)

        // First page is a normal cover/notice image (1000 x 1000, ratio 1.0 < 2.0)
        viewModel.recordImageAspectRatio(0, 1000, 1000)
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.AUTO)

        // Second page (index 1) is a tall webtoon strip (800 x 4000, ratio 5.0 >= 2.0)
        viewModel.recordImageAspectRatio(1, 800, 4000)

        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.SCROLL)
        assertThat(viewModel.state.value.readerToastMessage).isNotNull()
    }

    @Test
    fun doesNotAutoSwitchWhenOnlyThirdPageIsWebtoon() = runBlocking(Dispatchers.Main) {
        val ep1 = EpisodeItem(wrId = "ep1", title = "1화", date = "2026-01-01", thumbUrl = null, href = "href1")

        viewModel.openSeries(series("auto_third"))
        viewModel.updateViewportSize(400, 900)
        viewModel.setViewMode(ViewMode.AUTO)
        viewModel.openEpisode(ep1)

        // First and second pages are normal manga
        viewModel.recordImageAspectRatio(0, 1000, 1400)
        viewModel.recordImageAspectRatio(1, 1000, 1400)
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.AUTO)

        // Third page (index 2) is tall, but only the first 2 pages (index 0, 1) are candidates
        viewModel.recordImageAspectRatio(2, 800, 4000)
        assertThat(viewModel.state.value.viewMode).isEqualTo(ViewMode.AUTO)
        assertThat(viewModel.state.value.readerToastMessage).isNull()
    }
}
