package com.comics8.core.source.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.EnumSet

class SmbFileSystem(private val config: NetworkSourceConfig) : NetworkFileSystem {
    private val root = NetworkSourceConfig.normalizePath(config.path).replace('/', '\\')

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

    override fun open(path: String): InputStream = ChannelInputStream(openChannel(path))

    override fun openChannel(path: String, knownSize: Long): SeekableByteChannel {
        val handles = connect()
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
            SmbChannel(handles, file, size)
        } catch (e: Exception) {
            handles.close()
            throw e
        }
    }

    private fun remotePath(relative: String): String {
        val rel = NetworkSourceConfig.normalizePath(relative).replace('/', '\\')
        return listOf(root, rel).filter { it.isNotEmpty() }.joinToString("\\")
    }

    private fun <T> withShare(block: (DiskShare) -> T): T = connect().use { block(it.share) }

    private fun connect(): Handles {
        val client = SMBClient()
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
        private val handles: Handles,
        private val file: com.hierynomus.smbj.share.File,
        private val length: Long,
    ) : ReadOnlySeekableChannel() {
        override fun size(): Long = length

        override fun read(dst: ByteBuffer): Int {
            check(open) { "channel closed" }
            if (cursor >= length) return -1
            val count = minOf(dst.remaining().toLong(), length - cursor, 1024L * 1024L).toInt()
            if (count <= 0) return 0
            val bytes = ByteArray(count)
            val read = file.read(bytes, cursor, 0, count)
            if (read <= 0) return -1
            dst.put(bytes, 0, read)
            cursor += read
            return read
        }

        override fun close() {
            if (!open) return
            open = false
            runCatching { file.close() }
            handles.close()
        }
    }
}
