package com.comics8.core.source.network

import java.io.Closeable
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

data class NetworkNode(
    val path: String,
    val name: String,
    val directory: Boolean,
    val size: Long = 0L,
    val modifiedAt: Long = 0L,
)

interface NetworkFileSystem : Closeable {
    fun list(path: String = ""): List<NetworkNode>
    fun stat(path: String = ""): NetworkNode? = null
    fun open(path: String): InputStream
    fun openChannel(path: String, knownSize: Long = -1L): SeekableByteChannel
    fun test() {
        list("")
    }
    override fun close() {}
}

internal fun joinNetworkPath(parent: String, child: String): String =
    listOf(parent.trim('/'), child.trim('/')).filter { it.isNotEmpty() }.joinToString("/")

internal abstract class ReadOnlySeekableChannel : SeekableByteChannel {
    protected var cursor: Long = 0L
    protected var open = true

    override fun isOpen(): Boolean = open
    override fun position(): Long = cursor
    override fun position(newPosition: Long): SeekableByteChannel {
        require(newPosition >= 0L) { "negative position" }
        cursor = newPosition
        return this
    }
    override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException("read only")
    override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException("read only")
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
