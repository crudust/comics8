package com.comics8.core.source.network

import com.comics8.core.source.FileRevision
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

data class NetworkNode(
    val path: String,
    val name: String,
    val directory: Boolean,
    val revision: FileRevision,
)

data class OpenedNetworkFile(
    val channel: SeekableByteChannel,
    val revision: FileRevision,
)

interface NetworkFileSystem : Closeable {
    fun list(path: String = ""): List<NetworkNode>
    fun stat(path: String = ""): NetworkNode? = null
    fun open(path: String): InputStream
    fun openFile(path: String): OpenedNetworkFile
    fun test() {
        list("")
    }
    override fun close() {}
}

internal fun joinNetworkPath(parent: String, child: String): String =
    listOf(parent.trim('/'), child.trim('/')).filter { it.isNotEmpty() }.joinToString("/")

internal abstract class ReadOnlySeekableChannel : SeekableByteChannel {
    protected val lock = Any()
    protected var cursor: Long = 0L
    protected var open = true

    protected fun ensureOpen() {
        if (!open) throw java.nio.channels.ClosedChannelException()
    }

    override fun isOpen(): Boolean = synchronized(lock) { open }
    override fun position(): Long = synchronized(lock) {
        ensureOpen()
        cursor
    }
    override fun position(newPosition: Long): SeekableByteChannel = synchronized(lock) {
        ensureOpen()
        require(newPosition >= 0L) { "negative position" }
        cursor = newPosition
        this
    }
    override fun write(src: ByteBuffer): Int = throw java.nio.channels.NonWritableChannelException()
    override fun truncate(size: Long): SeekableByteChannel = throw java.nio.channels.NonWritableChannelException()
}

internal abstract class BufferedNetworkChannel(
    private val length: Long,
) : ReadOnlySeekableChannel() {
    private var blockStart = -1L
    private var block = ByteArray(0)

    override fun size(): Long = synchronized(lock) {
        ensureOpen()
        length
    }

    protected abstract fun fetchRange(offset: Long, lengthToRead: Int): ByteArray

    override fun read(dst: ByteBuffer): Int = synchronized(lock) {
        ensureOpen()
        if (!dst.hasRemaining()) return 0
        if (cursor >= length) return -1
        var total = 0
        while (dst.hasRemaining() && cursor < length) {
            val remaining = dst.remaining()

            // 64KB 이상 대용량 읽기는 블록 캐시를 거치지 않고 최대 1MB 다이렉트 스트리밍
            if (remaining >= DIRECT_READ_THRESHOLD) {
                val toRead = minOf(remaining.toLong(), length - cursor, DIRECT_READ_MAX).toInt()
                val bytes = fetchRange(cursor, toRead)
                if (bytes.isEmpty()) {
                    if (total == 0) throw IOException("네트워크 파일 읽기 실패 (위치: $cursor)")
                    break
                }
                val putCount = minOf(bytes.size, dst.remaining())
                dst.put(bytes, 0, putCount)
                cursor += putCount
                total += putCount
                blockStart = -1L
                block = ByteArray(0)
                continue
            }

            val wantedStart = cursor / BLOCK_SIZE * BLOCK_SIZE
            if (blockStart != wantedStart) {
                val toRead = minOf(BLOCK_SIZE, length - wantedStart).toInt()
                block = fetchRange(wantedStart, toRead)
                blockStart = wantedStart
            }
            val offset = (cursor - blockStart).toInt()
            if (offset !in block.indices) {
                blockStart = -1L
                block = ByteArray(0)
                if (total == 0) throw IOException("네트워크 블록 읽기 실패 (위치: $cursor, 블록시작: $wantedStart)")
                break
            }
            val count = minOf(
                remaining.toLong(),
                (block.size - offset).toLong(),
                length - cursor,
            ).toInt()
            if (count <= 0) break
            dst.put(block, offset, count)
            cursor += count
            total += count
        }
        return if (total == 0 && cursor >= length) -1 else total
    }

    override fun close() {
        synchronized(lock) {
            open = false
            block = ByteArray(0)
            blockStart = -1L
        }
    }

    companion object {
        internal const val BLOCK_SIZE = 256 * 1024L
        internal const val DIRECT_READ_THRESHOLD = 64 * 1024
        internal const val DIRECT_READ_MAX = 1024 * 1024L
    }
}

internal class ChannelInputStream(
    private val channel: SeekableByteChannel,
) : InputStream() {
    private val one = ByteArray(1)
    override fun read(): Int = if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(buffer, offset, length))
    override fun close() = channel.close()
}
