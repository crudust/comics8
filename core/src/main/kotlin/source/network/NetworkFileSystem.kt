package com.comics8.core.source.network

import com.comics8.core.source.FileRevision
import java.io.Closeable
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

    override fun isOpen(): Boolean = synchronized(lock) { open }
    override fun position(): Long = synchronized(lock) { cursor }
    override fun position(newPosition: Long): SeekableByteChannel = synchronized(lock) {
        require(newPosition >= 0L) { "negative position" }
        cursor = newPosition
        this
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
