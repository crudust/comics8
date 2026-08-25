package com.comics8.core.source.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbFileSystem(private val config: NetworkSourceConfig) : NetworkFileSystem {
    private val root = NetworkSourceConfig.normalizePath(config.path).replace('/', '\\')
    private val handleLock = Any()
    @Volatile private var activeHandles: Handles? = null
    @Volatile private var closed = false

    override fun list(path: String): List<NetworkNode> = withShare { share ->
        val remoteDir = remotePath(path)
        share.list(remoteDir).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            NetworkNode(
                path = joinNetworkPath(path, name),
                name = name,
                directory = EnumUtils.isSet(
                    info.fileAttributes,
                    FileAttributes.FILE_ATTRIBUTE_DIRECTORY,
                ),
                size = info.endOfFile,
                modifiedAt = info.lastWriteTime.toEpochMillis(),
            )
        }
    }

    override fun stat(path: String): NetworkNode? = withShare { share ->
        val info = share.getFileInformation(remotePath(path))
        NetworkNode(
            path = NetworkSourceConfig.normalizePath(path),
            name = path.substringAfterLast('/').ifBlank { config.path.substringAfterLast('/').ifBlank { config.share } },
            directory = info.standardInformation.isDirectory,
            size = info.standardInformation.endOfFile,
            modifiedAt = info.basicInformation.lastWriteTime.toEpochMillis(),
        )
    }

    override fun open(path: String): InputStream = ChannelInputStream(openChannel(path))

    override fun openChannel(path: String, knownSize: Long): SeekableByteChannel {
        val handles = handles()
        return try {
            val file = handles.share.openFile(
                remotePath(path),
                EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.noneOf(FileAttributes::class.java),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_RANDOM_ACCESS),
            )
            val size = if (knownSize >= 0L) knownSize else file.fileInformation.standardInformation.endOfFile
            SmbChannel(file, size)
        } catch (e: Exception) {
            invalidate(handles)
            throw e
        }
    }

    private fun remotePath(relative: String): String {
        val rel = NetworkSourceConfig.normalizePath(relative).replace('/', '\\')
        return listOf(root, rel).filter { it.isNotEmpty() }.joinToString("\\")
    }

    private fun <T> withShare(block: (DiskShare) -> T): T {
        val handles = handles()
        return try {
            block(handles.share)
        } catch (e: Exception) {
            invalidate(handles)
            throw e
        }
    }

    private fun handles(): Handles = synchronized(handleLock) {
        check(!closed) { "SMB 저장소가 닫혔습니다" }
        activeHandles?.takeIf { it.connection.isConnected }?.let { return@synchronized it }
        activeHandles?.close()
        connect().also { activeHandles = it }
    }

    private fun invalidate(handles: Handles) = synchronized(handleLock) {
        if (activeHandles === handles) {
            activeHandles = null
            handles.close()
        }
    }

    private fun connect(): Handles {
        val client = SMBClient(SMB_CONFIG)
        val connection = try {
            client.connect(config.host, config.port)
        } catch (e: Exception) {
            client.close()
            throw e
        }
        val auth = if (config.username.isBlank()) {
            AuthenticationContext.anonymous()
        } else {
            AuthenticationContext(config.username, config.password.toCharArray(), config.domain)
        }
        val session = try {
            connection.authenticate(auth)
        } catch (e: Exception) {
            connection.close()
            client.close()
            throw e
        }
        val share = try {
            session.connectShare(config.share) as DiskShare
        } catch (e: Exception) {
            session.close()
            connection.close()
            client.close()
            throw e
        }
        return Handles(client, connection, session, share)
    }

    private class Handles(
        val client: SMBClient,
        val connection: Connection,
        val session: Session,
        val share: DiskShare,
    ) : AutoCloseable {
        override fun close() {
            runCatching { share.close() }
            runCatching { session.close() }
            runCatching { connection.close() }
            runCatching { client.close() }
        }
    }

    private class SmbChannel(
        private val file: com.hierynomus.smbj.share.File,
        private val length: Long,
    ) : ReadOnlySeekableChannel() {
        private var blockStart = -1L
        private var block = ByteArray(0)

        override fun size(): Long = synchronized(lock) { length }

        private fun readFileFully(buf: ByteArray, fileOffset: Long, lengthToRead: Int): Int {
            var total = 0
            while (total < lengthToRead) {
                val toReadNow = minOf(lengthToRead - total, 1024 * 1024)
                val read = file.read(buf, fileOffset + total, total, toReadNow)
                if (read <= 0) break
                total += read
            }
            return total
        }

        override fun read(dst: ByteBuffer): Int = synchronized(lock) {
            check(open) { "channel closed" }
            if (cursor >= length) return -1
            var total = 0
            while (dst.hasRemaining() && cursor < length) {
                val remaining = dst.remaining()
                if (remaining >= DIRECT_READ_THRESHOLD) {
                    val toRead = minOf(remaining.toLong(), length - cursor, 1024L * 1024L).toInt()
                    val bytes = ByteArray(toRead)
                    val read = readFileFully(bytes, cursor, toRead)
                    if (read <= 0) {
                        if (total == 0) throw IOException("SMB 파일 읽기 실패 (위치: $cursor)")
                        break
                    }
                    dst.put(bytes, 0, read)
                    cursor += read
                    total += read
                    blockStart = -1L
                    block = ByteArray(0)
                    continue
                }

                val wantedStart = cursor / BLOCK_SIZE * BLOCK_SIZE
                if (blockStart != wantedStart) {
                    loadBlock(wantedStart)
                }
                val offset = (cursor - blockStart).toInt()
                if (offset !in block.indices) {
                    if (total == 0) throw IOException("SMB 블록 읽기 실패 (위치: $cursor, 블록시작: $blockStart)")
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

        private fun loadBlock(start: Long) {
            val toFetch = minOf(BLOCK_SIZE, length - start).toInt()
            if (toFetch <= 0) {
                block = ByteArray(0)
                blockStart = -1L
                return
            }
            val buf = ByteArray(toFetch)
            val read = readFileFully(buf, start, toFetch)
            if (read > 0) {
                block = if (read == toFetch) buf else buf.copyOf(read)
                blockStart = start
            } else {
                block = ByteArray(0)
                blockStart = -1L
            }
        }

        override fun close() {
            synchronized(lock) {
                if (!open) return
                open = false
                block = ByteArray(0)
                try {
                    file.close()
                } catch (_: Exception) {
                }
            }
        }

        companion object {
            private const val BLOCK_SIZE = 512 * 1024L
            private const val DIRECT_READ_THRESHOLD = 512 * 1024
        }
    }

    override fun close() = synchronized(handleLock) {
        if (closed) return@synchronized
        closed = true
        activeHandles?.close()
        activeHandles = null
    }

    companion object {
        private val SMB_CONFIG: SmbConfig = SmbConfig.builder()
            .withTimeout(15L, TimeUnit.SECONDS)
            .withSoTimeout(15L, TimeUnit.SECONDS)
            .withReadBufferSize(1024 * 1024)
            .withWriteBufferSize(1024 * 1024)
            .withMultiProtocolNegotiate(true)
            .build()
    }
}

