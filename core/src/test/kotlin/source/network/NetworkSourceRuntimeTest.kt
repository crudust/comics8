package com.comics8.core.source.network

import com.comics8.core.source.FileRevision
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.channels.SeekableByteChannel

class NetworkSourceRuntimeTest {
    @Test
    fun disconnectReleasesZipSessionsAndBackend() {
        val backend = RecordingFileSystem()
        NetworkSourceRuntime.register("network-runtime-test", backend)
        try {
            NetworkSourceRuntime.zipImageEntries("network-runtime-test", "Book.cbz")
            assertThat(backend.openFileCalls).isEqualTo(1)

            NetworkSourceRuntime.disconnect("network-runtime-test")

            assertThat(backend.disconnectCalls).isEqualTo(1)
            // ZIP 세션이 retire되었으므로 같은 revision이라도 새 세션을 열어야 한다.
            NetworkSourceRuntime.zipImageEntries("network-runtime-test", "Book.cbz")
            assertThat(backend.openFileCalls).isEqualTo(2)
        } finally {
            NetworkSourceRuntime.remove("network-runtime-test")
        }
    }

    @Test
    fun disconnectAllReleasesEveryBackend() {
        val first = RecordingFileSystem()
        val second = RecordingFileSystem()
        NetworkSourceRuntime.register("network-runtime-test-a", first)
        NetworkSourceRuntime.register("network-runtime-test-b", second)
        try {
            NetworkSourceRuntime.disconnectAll()

            assertThat(first.disconnectCalls).isEqualTo(1)
            assertThat(second.disconnectCalls).isEqualTo(1)
        } finally {
            NetworkSourceRuntime.remove("network-runtime-test-a")
            NetworkSourceRuntime.remove("network-runtime-test-b")
        }
    }

    /**
     * 목록/stat/openFile만 제공하는 최소 백엔드. ZIP 세션 재사용 여부를 openFileCalls로 관찰한다.
     * disconnect는 실제 백엔드처럼 세션을 끊는 대신 호출 횟수만 기록한다.
     */
    private class RecordingFileSystem : NetworkFileSystem {
        private val zip = buildZip()
        private val revision = FileRevision(zip.size.toLong(), 0L)
        var openFileCalls = 0
        var disconnectCalls = 0

        override fun list(path: String): List<NetworkNode> = when (path) {
            "" -> listOf(NetworkNode("Book.cbz", "Book.cbz", false, revision))
            else -> emptyList()
        }

        override fun stat(path: String): NetworkNode? = when (path) {
            "Book.cbz" -> NetworkNode("Book.cbz", "Book.cbz", false, revision)
            else -> null
        }

        override fun open(path: String): InputStream = ByteArrayInputStream(zip)

        override fun openFile(path: String): OpenedNetworkFile {
            openFileCalls++
            return OpenedNetworkFile(InMemoryChannel(zip), revision)
        }

        override fun disconnect() {
            disconnectCalls++
        }
    }

    private class InMemoryChannel(private val bytes: ByteArray) : SeekableByteChannel {
        private var position = 0L
        private var open = true

        override fun read(dst: java.nio.ByteBuffer): Int {
            if (!open) throw java.nio.channels.ClosedChannelException()
            if (position >= bytes.size) return -1
            val count = minOf(dst.remaining().toLong(), bytes.size - position).toInt()
            dst.put(bytes, position.toInt(), count)
            position += count
            return count
        }

        override fun write(src: java.nio.ByteBuffer): Int =
            throw java.nio.channels.NonWritableChannelException()

        override fun position(): Long = position

        override fun position(newPosition: Long): SeekableByteChannel {
            position = newPosition
            return this
        }

        override fun size(): Long = bytes.size.toLong()

        override fun truncate(size: Long): SeekableByteChannel =
            throw java.nio.channels.NonWritableChannelException()

        override fun isOpen(): Boolean = open

        override fun close() {
            open = false
        }
    }

    private companion object {
        fun buildZip(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("001.jpg"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
            return out.toByteArray()
        }
    }
}
