package com.comics8.core.source.network

import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceHttp
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
            id = "network:source-test",
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
            assertThat(NetworkSourceRuntime.open(urls.single()).readBytes()).isEqualTo(image)
            assertThat(backend.channelOpens).isEqualTo(2)
        } finally {
            NetworkSourceRuntime.remove(config.id)
        }
    }

    private class FakeFs(private val zip: ByteArray) : NetworkFileSystem {
        var channelOpens = 0
        override fun list(path: String): List<NetworkNode> = when (path) {
            "" -> listOf(NetworkNode("Book.cbz", "Book.cbz", false, zip.size.toLong()))
            else -> emptyList()
        }
        override fun open(path: String): InputStream = ByteArrayInputStream(zip)
        override fun openChannel(path: String, knownSize: Long): SeekableByteChannel {
            channelOpens++
            return SeekableInMemoryByteChannel(zip)
        }
    }

    private object ThrowingHttp : SourceHttp {
        override fun fetch(spec: FetchSpec): HttpResult = error("network storage source must not use SourceHttp")
        override fun fetchText(spec: FetchSpec): String = error("network storage source must not use SourceHttp")
        override fun isAccessible(url: String, policy: RequestPolicy): Boolean = false
    }
}
