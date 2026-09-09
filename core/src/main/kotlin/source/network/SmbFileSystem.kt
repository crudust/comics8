package com.comics8.core.source.network

import com.comics8.core.source.FileRevision
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
                revision = FileRevision(info.endOfFile, info.lastWriteTime.toEpochMillis()),
            )
        }
    }

    override fun stat(path: String): NetworkNode? = withShare { share ->
        val info = share.getFileInformation(remotePath(path))
        NetworkNode(
            path = NetworkSourceConfig.normalizePath(path),
            name = path.substringAfterLast('/').ifBlank { config.path.substringAfterLast('/').ifBlank { config.share } },
            directory = info.standardInformation.isDirectory,
            revision = FileRevision(
                info.standardInformation.endOfFile,
                info.basicInformation.lastWriteTime.toEpochMillis(),
            ),
        )
    }

    override fun open(path: String): InputStream = ChannelInputStream(openFile(path).channel)

    override fun openFile(path: String): OpenedNetworkFile {
        val handles = handles()
        var openedFile: com.hierynomus.smbj.share.File? = null
        return try {
            val file = handles.share.openFile(
                remotePath(path),
                EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                EnumSet.noneOf(FileAttributes::class.java),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_RANDOM_ACCESS),
            )
            openedFile = file
            val info = file.fileInformation
            val revision = FileRevision(
                info.standardInformation.endOfFile,
                info.basicInformation.lastWriteTime.toEpochMillis(),
            )
            OpenedNetworkFile(SmbChannel(file, revision.sizeBytes), revision)
        } catch (e: Exception) {
            runCatching { openedFile?.close() }
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
        length: Long,
    ) : BufferedNetworkChannel(length) {

        override fun fetchRange(offset: Long, lengthToRead: Int): ByteArray {
            val buf = ByteArray(lengthToRead)
            var total = 0
            while (total < lengthToRead) {
                val toReadNow = minOf(lengthToRead - total, 1024 * 1024)
                val read = file.read(buf, offset + total, total, toReadNow)
                if (read <= 0) break
                total += read
            }
            return if (total == lengthToRead) buf else buf.copyOf(total)
        }

        override fun close() {
            runCatching { file.close() }
            super.close()
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
