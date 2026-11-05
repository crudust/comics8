package com.comics8.core.source.network

import com.comics8.core.source.FetchSpec
import com.comics8.core.source.FileRevision
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceHttp
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
    fun reusesClassificationIndexForUnchangedRootButRereadsRootListing() = runBlocking {
        val backend = FolderFs(linkedMapOf("Alpha" to 5_000L, "Beta" to 9_000L))
        val config = testConfig("network-cache-test")
        val index = NetworkWorkIndex(createTempDirectory("network-index").resolve("index.json").toFile())
        val source = NetworkLibrarySource(config, backend, index)
        try {
            source.loadListing("LIBRARY", 1, ThrowingHttp)
            source.loadListing("LIBRARY", 1, ThrowingHttp)

            // 루트 목록은 매번 새로 읽고(수정일 최신 유지), 작품 폴더는 한 번만 뒤진다.
            assertThat(backend.listCalls).containsExactly("", "Alpha", "Beta", "").inOrder()
        } finally {
            NetworkSourceRuntime.remove(config.id)
        }
    }

    @Test
    fun latestFollowsWorkFolderModifiedTimeWithoutStaleCache() = runBlocking {
        val backend = FolderFs(linkedMapOf("Alpha" to 5_000L, "Beta" to 9_000L))
        val config = testConfig("network-latest-test")
        val index = NetworkWorkIndex(createTempDirectory("network-index").resolve("index.json").toFile())
        val source = NetworkLibrarySource(config, backend, index)
        try {
            assertThat(latestTitles(source)).containsExactly("Beta", "Alpha").inOrder()

            // 작품 폴더 안에 회차를 추가하면 그 폴더의 수정일만 바뀐다(루트 수정일은 그대로).
            backend.setFolderModifiedAt("Alpha", 20_000L)

            assertThat(latestTitles(source)).containsExactly("Alpha", "Beta").inOrder()
        } finally {
            NetworkSourceRuntime.remove(config.id)
        }
    }

    @Test
    fun latestUsesWorkFolderModifiedTimeInsteadOfNewestChapter() = runBlocking {
        // Alpha 폴더는 최신이지만 첫 회차 파일은 오래됐고, Beta 는 반대다.
        val backend = FolderFs(
            linkedMapOf("Alpha" to 9_000L, "Beta" to 3_000L),
            chapterModifiedAt = { folder -> if (folder == "Beta") 8_000L else 1_000L },
        )
        val config = testConfig("network-latest-folder-time-test")
        val source = NetworkLibrarySource(config, backend)
        try {
            assertThat(latestTitles(source)).containsExactly("Alpha", "Beta").inOrder()
        } finally {
            NetworkSourceRuntime.remove(config.id)
        }
    }

    private suspend fun latestTitles(source: NetworkLibrarySource): List<String> =
        source.loadListing("LATEST", 1, ThrowingHttp).items.map { it.title }

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

    private class FakeFs(private val zip: ByteArray) : NetworkFileSystem {
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
                "Book.cbz" -> NetworkNode("Book.cbz", "Book.cbz", false, FileRevision(zip.size.toLong(), 0L))
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

    /** 루트 바로 아래에 폴더 작품만 있는 저장소. 폴더/회차 수정일을 테스트에서 직접 바꾼다. */
    private class FolderFs(
        folderModifiedAt: Map<String, Long>,
        private val chapterModifiedAt: (String) -> Long = { 1_000L },
    ) : NetworkFileSystem {
        val listCalls = ArrayList<String>()
        private val folders = LinkedHashMap(folderModifiedAt)

        fun setFolderModifiedAt(name: String, value: Long) {
            folders[name] = value
        }

        override fun list(path: String): List<NetworkNode> {
            listCalls += path
            return if (path.isEmpty()) {
                folders.map { (name, modifiedAt) ->
                    NetworkNode(name, name, directory = true, FileRevision(0L, modifiedAt))
                }
            } else {
                listOf(
                    NetworkNode(
                        "$path/01.cbz",
                        "01.cbz",
                        directory = false,
                        FileRevision(10L, chapterModifiedAt(path)),
                    ),
                )
            }
        }

        override fun stat(path: String): NetworkNode? = null
        override fun open(path: String): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun openFile(path: String): OpenedNetworkFile = throw UnsupportedOperationException()
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
