package com.comics8.core.source.network

import com.comics8.core.source.FetchSpec
import com.comics8.core.source.FileRevision
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.local.LibraryScanIndex
import com.comics8.core.source.local.PreviewImageResolver
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class NetworkLibrarySourceTest {
    @Test
    fun streamsZipEntryWithoutMaterializingArchive() = runBlocking {
        val image = "page-data".toByteArray()
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { archive ->
                archive.putNextEntry(ZipEntry("001.jpg"))
                archive.write(image)
                archive.closeEntry()
            }
        }.toByteArray()
        val backend = FakeFs(zip)
        val config = NetworkSourceConfig(
            id = "network-source-test",
            protocol = NetworkProtocol.SMB,
            name = "NAS",
            host = "nas",
            share = "comics",
        )
        val source = NetworkLibrarySource(config, backend)
        try {
            val listing = source.loadListing("LIBRARY", 1, ThrowingHttp)
            assertThat(listing.items.map { it.title }).containsExactly("Book")
            assertThat(backend.channelOpens).isEqualTo(0)
            val episodes = source.loadEpisodes(listing.items.single(), 1, ThrowingHttp)
            assertThat(backend.channelOpens).isEqualTo(0)
            val urls = source.resolveImages(episodes.items.single(), listing.items.single(), ThrowingHttp)
            assertThat(urls).hasSize(1)
            assertThat(backend.channelOpens).isEqualTo(1)
            val statCallsBeforeRead = backend.statCalls
            assertThat(NetworkSourceRuntime.open(urls.single()).readBytes()).isEqualTo(image)
            // Reuses the cached zip session, so channel is not opened again
            assertThat(backend.channelOpens).isEqualTo(1)
            assertThat(backend.statCalls).isEqualTo(statCallsBeforeRead)
        } finally {
            NetworkSourceRuntime.remove(config.id)
        }
    }

    @Test
    fun recoversFromBrokenSessionAndMatchesFallbackZipEntries() = runBlocking {
        val image = "page-data-2".toByteArray()
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { archive ->
                archive.putNextEntry(ZipEntry("sub\\002.jpg"))
                archive.write(image)
                archive.closeEntry()
            }
        }.toByteArray()
        var shouldFailChannel = true
        val backend = object : NetworkFileSystem {
            var channelOpens = 0
            override fun list(path: String): List<NetworkNode> = emptyList()
            override fun stat(path: String): NetworkNode? = null
            override fun open(path: String): InputStream = ByteArrayInputStream(zip)
            override fun openFile(path: String): OpenedNetworkFile {
                channelOpens++
                if (shouldFailChannel) {
                    shouldFailChannel = false
                    throw java.io.IOException("Temporary SMB connection failure")
                }
                return OpenedNetworkFile(
                    SeekableInMemoryByteChannel(zip),
                    FileRevision(zip.size.toLong(), 1L),
                )
            }
        }
        val config = NetworkSourceConfig(
            id = "network-recovery-test",
            protocol = NetworkProtocol.SMB,
            name = "NAS",
            host = "nas",
            share = "comics",
        )
        NetworkSourceRuntime.register(config.id, backend)
        try {
            val url = NetworkImageUri.encode(
                sourceId = config.id,
                path = "Book.cbz",
                zipEntry = "sub/002.jpg", // Normalized slash
            )
            // 1st channel open fails with IOException -> automatic invalidation & retry succeeds
            val bytes = NetworkSourceRuntime.open(url).readBytes()
            assertThat(bytes).isEqualTo(image)
            assertThat(backend.channelOpens).isEqualTo(2)
        } finally {
            NetworkSourceRuntime.remove(config.id)
        }
    }

    @Test
    fun loadsEpisodesDirectlyWithoutScanningRootAgain(): Unit = runBlocking {
        val backend = object : NetworkFileSystem {
            var listCalls = mutableListOf<String>()
            override fun list(path: String): List<NetworkNode> {
                listCalls.add(path)
                return when (path) {
                    "MangaSeries" -> listOf(
                        NetworkNode("MangaSeries/01.cbz", "01.cbz", false, FileRevision(100L, 0L)),
                        NetworkNode("MangaSeries/02.cbz", "02.cbz", false, FileRevision(100L, 0L)),
                    )
                    else -> emptyList()
                }
            }
            override fun stat(path: String): NetworkNode? = null
            override fun open(path: String): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun openFile(path: String): OpenedNetworkFile = OpenedNetworkFile(
                SeekableInMemoryByteChannel(ByteArray(0)),
                FileRevision(0L, 0L),
            )
        }
        val config = NetworkSourceConfig(
            id = "network-direct-test",
            protocol = NetworkProtocol.SMB,
            name = "NAS",
            host = "nas",
            share = "comics",
        )
        val source = NetworkLibrarySource(config, backend)
        try {
            val item = com.comics8.core.model.ToonItem(
                id = "series:MangaSeries",
                title = "MangaSeries",
                thumbUrl = "",
                href = "",
                sourceId = config.id,
            )
            val episodes = source.loadEpisodes(item, 1, ThrowingHttp)
            assertThat(episodes.items.map { it.title }).containsExactly("01", "02")
            // Verify that backend.list was called ONLY for "MangaSeries", NOT for "" (root)
            assertThat(backend.listCalls).containsExactly("MangaSeries")
        } finally {
            NetworkSourceRuntime.remove(config.id)
        }
    }

    @Test
    fun reusesIndexAfterSingleRootStatWithoutListingAgain() = runBlocking {
        val backend = FakeFs(ByteArray(8), rootModifiedAt = 1234L)
        val config = NetworkSourceConfig(
            id = "network-cache-test",
            protocol = NetworkProtocol.SMB,
            name = "NAS",
            host = "nas",
            share = "comics",
        )
        val index = LibraryScanIndex(createTempDirectory("network-index").resolve("index.json").toFile())
        val source = NetworkLibrarySource(config, backend, index)
        try {
            source.loadListing("LIBRARY", 1, ThrowingHttp)
            source.loadListing("LIBRARY", 1, ThrowingHttp)

            assertThat(backend.listCalls).isEqualTo(1)
            assertThat(backend.statCalls).isEqualTo(2)
        } finally {
            NetworkSourceRuntime.remove(config.id)
        }
    }

    @Test
    fun opensCompletedArchiveUsingRevisionFromFileHandleInsteadOfStaleScanSize() = runBlocking {
        val image = "completed-after-scan".toByteArray()
        val completedZip = zipBytes("001.jpg", image)
        val backend = MutableFs(completedZip.copyOf(32), revisionTime = 1L)
        val config = testConfig("network-growing-file-test")
        val source = NetworkLibrarySource(config, backend)
        try {
            val listing = source.loadListing("LIBRARY", 1, ThrowingHttp)
            val episode = source.loadEpisodes(listing.items.single(), 1, ThrowingHttp).items.single()

            backend.replace(completedZip, revisionTime = 2L)

            val preview = PreviewImageResolver.resolve(
                listing.items.single().thumbUrl,
                refreshNetworkRevision = true,
            )
            assertThat(preview!!.key.revision)
                .isEqualTo(FileRevision(completedZip.size.toLong(), 2L))
            val urls = source.resolveImages(episode, listing.items.single(), ThrowingHttp)
            assertThat(NetworkSourceRuntime.open(urls.single()).readBytes()).isEqualTo(image)
            assertThat(NetworkImageUri.parse(urls.single())!!.revision)
                .isEqualTo(FileRevision(completedZip.size.toLong(), 2L))
        } finally {
            NetworkSourceRuntime.remove(config.id)
        }
    }

    @Test
    fun replacesZipSessionWhenSamePathGetsNewRevision() = runBlocking {
        val firstZip = zipBytes("001.jpg", "first".toByteArray())
        val secondZip = zipBytes("001.jpg", "second".toByteArray())
        val backend = MutableFs(firstZip, revisionTime = 1L)
        val config = testConfig("network-replaced-file-test")
        val source = NetworkLibrarySource(config, backend)
        try {
            val listing = source.loadListing("LIBRARY", 1, ThrowingHttp)
            val episode = source.loadEpisodes(listing.items.single(), 1, ThrowingHttp).items.single()
            val firstUrl = source.resolveImages(episode, listing.items.single(), ThrowingHttp).single()
            assertThat(NetworkSourceRuntime.open(firstUrl).readBytes()).isEqualTo("first".toByteArray())

            backend.replace(secondZip, revisionTime = 2L)

            val secondUrl = source.resolveImages(episode, listing.items.single(), ThrowingHttp).single()
            assertThat(NetworkSourceRuntime.open(secondUrl).readBytes()).isEqualTo("second".toByteArray())
            assertThat(backend.channelOpens).isEqualTo(2)
        } finally {
            NetworkSourceRuntime.remove(config.id)
        }
    }

    @Test
    fun loadEpisodesPopulatesMtimeAndFormattedDate() = runBlocking {
        val image = "page-data".toByteArray()
        val zip = zipBytes("001.jpg", image)
        val epochMs = 1718755200000L
        val backend = MutableFs(zip, epochMs)
        val config = testConfig("network-sort-test")
        val source = NetworkLibrarySource(config, backend)
        val listing = source.loadListing("LIBRARY", 1, ThrowingHttp)
        val episodes = source.loadEpisodes(listing.items.single(), 1, ThrowingHttp)
        val episode = episodes.items.single()
        assertThat(episode.mtime).isEqualTo(epochMs)
        assertThat(episode.date).isNotNull()
    }

    private class FakeFs(private val zip: ByteArray, private val rootModifiedAt: Long = 0L) : NetworkFileSystem {
        var channelOpens = 0
        var listCalls = 0
        var statCalls = 0
        override fun list(path: String): List<NetworkNode> {
            listCalls++
            return when (path) {
                "" -> listOf(NetworkNode("Book.cbz", "Book.cbz", false, FileRevision(zip.size.toLong(), 0L)))
                else -> emptyList()
            }
        }
        override fun stat(path: String): NetworkNode? {
            statCalls++
            return when (path) {
                "Book.cbz" -> NetworkNode(
                    "Book.cbz",
                    "Book.cbz",
                    false,
                    FileRevision(zip.size.toLong(), 0L),
                )
                "" -> rootModifiedAt.takeIf { it > 0L }?.let {
                    NetworkNode("", "comics", true, FileRevision(0L, it))
                }
                else -> null
            }
        }
        override fun open(path: String): InputStream = ByteArrayInputStream(zip)
        override fun openFile(path: String): OpenedNetworkFile {
            channelOpens++
            return OpenedNetworkFile(
                SeekableInMemoryByteChannel(zip),
                FileRevision(zip.size.toLong(), 0L),
            )
        }
    }

    private class MutableFs(initial: ByteArray, revisionTime: Long) : NetworkFileSystem {
        private var bytes = initial
        private var revision = FileRevision(initial.size.toLong(), revisionTime)
        var channelOpens = 0

        fun replace(next: ByteArray, revisionTime: Long) {
            bytes = next
            revision = FileRevision(next.size.toLong(), revisionTime)
        }

        override fun list(path: String): List<NetworkNode> = when (path) {
            "" -> listOf(NetworkNode("Book.cbz", "Book.cbz", false, revision))
            else -> emptyList()
        }

        override fun stat(path: String): NetworkNode? = when (path) {
            "Book.cbz" -> NetworkNode(path, "Book.cbz", false, revision)
            else -> null
        }

        override fun open(path: String): InputStream = ByteArrayInputStream(bytes)

        override fun openFile(path: String): OpenedNetworkFile {
            channelOpens++
            return OpenedNetworkFile(SeekableInMemoryByteChannel(bytes), revision)
        }
    }

    private fun zipBytes(name: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { archive ->
            archive.putNextEntry(ZipEntry(name))
            archive.write(bytes)
            archive.closeEntry()
        }
    }.toByteArray()

    private fun testConfig(id: String) = NetworkSourceConfig(
        id = id,
        protocol = NetworkProtocol.SMB,
        name = "NAS",
        host = "nas",
        share = "comics",
    )

    private object ThrowingHttp : SourceHttp {
        override fun fetch(spec: FetchSpec): HttpResult = error("network storage source must not use SourceHttp")
        override fun fetchText(spec: FetchSpec): String = error("network storage source must not use SourceHttp")
        override fun isAccessible(url: String, policy: RequestPolicy): Boolean = false
    }
}
