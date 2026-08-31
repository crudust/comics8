package com.comics8.core.source.local

import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.LocalImageUri
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceKind
import com.comics8.core.source.WorkId
import com.comics8.core.sync.SyncWire
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LocalSourceTest {
    @Test
    fun listingStampsSourceIdLocalAndOmitsSync() = runBlocking<Unit> {
        val root = fixture("local/library")
        val source = sourceFor(listOf(root))
        val page = source.loadListing("LIBRARY", 1, ThrowingHttp)
        assertThat(page.items).isNotEmpty()
        assertThat(page.items.map { it.title }).containsExactly("imgdir", "series", "standalone").inOrder()
        for (item in page.items) {
            assertThat(item.sourceId).isEqualTo(WorkId.LOCAL_SOURCE)
            assertThat(item.sourceId).isNotEmpty()
            assertThat(SyncWire.isSyncableSource(item.sourceId)).isFalse()
        }
        assertThat(source.syncParticipates).isFalse()
        assertThat(source.writesDownloads).isFalse()
        assertThat(source.requiresHttp).isFalse()
        assertThat(source.kind).isEqualTo(SourceKind.LOCAL)
        assertThat(source.emptyListingOk).isTrue()
        assertThat(source.emptyEpisodesOk).isTrue()
        assertThat(source.useProxy("http://example.com")).isFalse()
    }

    @Test
    fun scanResolvesZipAndFolderImageUris() = runBlocking<Unit> {
        val root = fixture("local/library")
        val source = sourceFor(listOf(root))
        val items = source.loadListing("LIBRARY", 1, ThrowingHttp).items

        val zipItem = items.first { it.title == "standalone" }
        val zipPreview = checkNotNull(LocalPreviewUri.parse(zipItem.thumbUrl))
        assertThat(zipPreview.thumbnailPx).isEqualTo(320)
        assertThat(LocalPreviewUri.open(zipPreview).use { it.readBytes() }).isNotEmpty()
        val zipEpisode = source.loadEpisodes(zipItem, 1, ThrowingHttp).items.single()
        assertThat(zipEpisode.mtime).isNotNull()
        assertThat(zipEpisode.mtime).isGreaterThan(0L)
        assertThat(zipEpisode.date).isNotNull()
        assertThat(LocalPreviewUri.parse(checkNotNull(zipEpisode.thumbUrl))?.thumbnailPx).isEqualTo(192)
        val zipImages = source.resolveImages(zipEpisode, zipItem, ThrowingHttp)
        assertThat(zipImages).isNotEmpty()
        for (url in zipImages) {
            assertThat(url).startsWith("${ZipImageUri.SCHEME}:")
            assertThat(ZipImageUri.parse(url)).isNotNull()
        }

        val dirItem = items.first { it.title == "imgdir" }
        val dirPreview = checkNotNull(LocalPreviewUri.parse(dirItem.thumbUrl))
        assertThat(LocalPreviewUri.open(dirPreview).use { it.readBytes() }).isNotEmpty()
        val dirEpisode = source.loadEpisodes(dirItem, 1, ThrowingHttp).items.single()
        val dirImages = source.resolveImages(dirEpisode, dirItem, ThrowingHttp)
        assertThat(dirImages.map { LocalImageUri.toFile(it)!!.name })
            .containsExactly("1.jpg", "2.jpg", "10.jpg")
            .inOrder()
        assertThat(dirImages.all { it.startsWith("file:") }).isTrue()

        val seriesItem = items.first { it.title == "series" }
        val seriesEpisodes = source.loadEpisodes(seriesItem, 1, ThrowingHttp).items
        assertThat(seriesEpisodes.map { it.title }).containsExactly("vol1", "vol2").inOrder()
        val vol1 = source.resolveImages(seriesEpisodes[0], seriesItem, ThrowingHttp)
        assertThat(vol1).isNotEmpty()
        assertThat(vol1.all { it.startsWith("${ZipImageUri.SCHEME}:") }).isTrue()
        val vol2 = source.resolveImages(seriesEpisodes[1], seriesItem, ThrowingHttp)
        assertThat(vol2).isNotEmpty()
        assertThat(vol2.all { it.startsWith("file:") }).isTrue()
    }

    @Test
    fun emptyZipEpisodeListIsAllowed() = runBlocking<Unit> {
        val root = createTempDirectory("local-empty-zip").toFile()
        root.deleteOnExit()
        val zip = File(root, "empty.cbz")
        LocalTestZips.write(zip, listOf(".DS_Store" to "junk", "__MACOSX/._1.jpg" to "apple"))
        val source = sourceFor(listOf(root))
        val item = source.loadListing("LIBRARY", 1, ThrowingHttp).items.single()
        assertThat(item.sourceId).isEqualTo(WorkId.LOCAL_SOURCE)
        val episodes = source.loadEpisodes(item, 1, ThrowingHttp)
        assertThat(episodes.items).hasSize(1)
        assertThat(source.resolveImages(episodes.items.single(), item, ThrowingHttp)).isEmpty()
        assertThat(source.emptyEpisodesOk).isTrue()
    }

    @Test
    fun searchMatchesTitleIgnoreCase() = runBlocking<Unit> {
        val root = fixture("local/library")
        val source = sourceFor(listOf(root))
        val hits = source.search(SearchQuery("STAND"), ThrowingHttp)
        assertThat(hits.map { it.title }).containsExactly("standalone")
        assertThat(hits.single().sourceId).isEqualTo(WorkId.LOCAL_SOURCE)
    }

    @Test
    fun emptyRootsYieldEmptyListing() = runBlocking<Unit> {
        val source = sourceFor(emptyList())
        val page = source.loadListing("LIBRARY", 1, ThrowingHttp)
        assertThat(page.items).isEmpty()
        assertThat(page.lastPage).isEqualTo(1)
    }

    private fun sourceFor(roots: List<File>): LocalSource {
        return LocalSource(
            roots = { roots },
        )
    }

    private fun fixture(path: String): File {
        val url = checkNotNull(javaClass.classLoader?.getResource(path)) { "missing $path" }
        return File(url.toURI())
    }

    private object ThrowingHttp : SourceHttp {
        override fun fetch(spec: FetchSpec): HttpResult = error("local source must not use HTTP")
        override fun fetchText(spec: FetchSpec): String = error("local source must not use HTTP")
        override fun isAccessible(url: String, policy: RequestPolicy): Boolean =
            error("local source must not use HTTP")
    }
}
