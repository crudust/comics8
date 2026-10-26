package com.comics8.core.source.network

import com.comics8.core.source.FileRevision
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

class SmbFileSystem(private val config: NetworkSourceConfig) : NetworkFileSystem {
    private val root = NetworkSourceConfig.normalizePath(config.path).replace('/', '\\')
    private val poolLock = Any()
    private val pool = ArrayList<Handles>()
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
        for (attempt in 0..1) {
            val handles = acquireHandles()
            if (!handles.retainChannel()) {
                invalidate(handles)
                continue
            }
            var openedFile: com.hierynomus.smbj.share.File? = null
            try {
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
                return OpenedNetworkFile(SmbChannel(handles, file, revision.sizeBytes), revision)
            } catch (e: Exception) {
                handles.releaseChannel()
                runCatching { openedFile?.close() }
                if (isFatalConnectionError(e, handles)) {
                    invalidate(handles)
                    if (attempt == 0) continue
                }
                throw e
            }
        }
        throw IOException("SMB 파일 열기 실패: $path")
    }

    private fun remotePath(relative: String): String {
        val rel = NetworkSourceConfig.normalizePath(relative).replace('/', '\\')
        return listOf(root, rel).filter { it.isNotEmpty() }.joinToString("\\")
    }

    private fun <T> withShare(block: (DiskShare) -> T): T {
        for (attempt in 0..1) {
            val handles = acquireHandles()
            try {
                return block(handles.share)
            } catch (e: Exception) {
                if (isFatalConnectionError(e, handles)) {
                    invalidate(handles)
                    if (attempt == 0) continue
                }
                throw e
            }
        }
        throw IOException("SMB 작업 실패")
    }

    private fun acquireHandles(): Handles {
        synchronized(poolLock) {
            check(!closed) { "SMB 저장소가 닫혔습니다" }
            val iterator = pool.iterator()
            while (iterator.hasNext()) {
                val h = iterator.next()
                if (!h.isUsable()) {
                    iterator.remove()
                    h.close()
                }
            }
            if (pool.isNotEmpty()) {
                val idle = pool.firstOrNull { it.activeCount() == 0 }
                if (idle != null) return idle
                if (pool.size >= MAX_POOL_SIZE) {
                    return pool.minByOrNull { it.activeCount() } ?: pool.first()
                }
            }
        }

        val created = try {
            connect()
        } catch (e: Exception) {
            synchronized(poolLock) {
                pool.firstOrNull { it.isUsable() }
            } ?: throw e
        }

        synchronized(poolLock) {
            if (closed) {
                created.close()
                throw IllegalStateException("SMB 저장소가 닫혔습니다")
            }
            if (pool.size < MAX_POOL_SIZE) {
                pool.add(created)
                return created
            } else {
                val selected = pool.minByOrNull { it.activeCount() } ?: pool.first()
                created.close()
                return selected
            }
        }
    }

    private fun invalidate(handles: Handles) = synchronized(poolLock) {
        if (pool.remove(handles)) {
            handles.close()
        }
    }

    private fun isFatalConnectionError(e: Throwable, handles: Handles): Boolean {
        if (!handles.isUsable()) return true
        if (e is TransportException) return true
        if (e is SocketException) return true
        if (e is SocketTimeoutException) return true
        if (e is EOFException) return true
        if (e is SMBApiException) {
            return when (e.status) {
                NtStatus.STATUS_USER_SESSION_DELETED,
                NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
                NtStatus.STATUS_CONNECTION_DISCONNECTED,
                NtStatus.STATUS_CONNECTION_RESET -> true
                else -> false
            }
        }
        if (e is SMBRuntimeException) return true
        return false
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
        private val activeChannels = AtomicInteger(0)

        fun isUsable(): Boolean = connection.isConnected

        fun activeCount(): Int = activeChannels.get()

        fun retainChannel(): Boolean {
            if (!isUsable()) return false
            activeChannels.incrementAndGet()
            return true
        }

        fun releaseChannel() {
            activeChannels.decrementAndGet()
        }

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
            try {
                runCatching { file.close() }
            } finally {
                handles.releaseChannel()
                super.close()
            }
        }
    }

    override fun close() = synchronized(poolLock) {
        if (closed) return@synchronized
        closed = true
        pool.forEach { it.close() }
        pool.clear()
    }

    private class SmbSocketFactory : SocketFactory() {
        private val delegate = getDefault()

        private fun configure(socket: Socket): Socket {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.receiveBufferSize = SOCKET_BUFFER_SIZE
            socket.sendBufferSize = SOCKET_BUFFER_SIZE
            return socket
        }

        override fun createSocket(): Socket = configure(delegate.createSocket())

        override fun createSocket(host: String, port: Int): Socket =
            configure(delegate.createSocket(host, port))

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            configure(delegate.createSocket(host, port, localHost, localPort))

        override fun createSocket(host: InetAddress, port: Int): Socket =
            configure(delegate.createSocket(host, port))

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            configure(delegate.createSocket(address, port, localAddress, localPort))

        companion object {
            private const val SOCKET_BUFFER_SIZE = 1024 * 1024
        }
    }

    companion object {
        private const val MAX_POOL_SIZE = 3

        private val SMB_CONFIG: SmbConfig = SmbConfig.builder()
            .withSocketFactory(SmbSocketFactory())
            .withTimeout(15L, TimeUnit.SECONDS)
            .withSoTimeout(15L, TimeUnit.SECONDS)
            .withReadTimeout(15L, TimeUnit.SECONDS)
            .withWriteTimeout(15L, TimeUnit.SECONDS)
            .withTransactTimeout(15L, TimeUnit.SECONDS)
            .withReadBufferSize(1024 * 1024)
            .withWriteBufferSize(1024 * 1024)
            .withMultiProtocolNegotiate(true)
            .build()
    }
}
