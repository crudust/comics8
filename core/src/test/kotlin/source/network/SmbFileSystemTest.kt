package com.comics8.core.source.network

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class SmbFileSystemTest {
    @Test
    fun rejectsOperationsAfterClose() {
        val config = NetworkSourceConfig(
            id = "network-smb-test",
            protocol = NetworkProtocol.SMB,
            name = "NAS",
            host = "127.0.0.1",
            port = 445,
            share = "comics",
        ).validated()

        val fs = SmbFileSystem(config)
        fs.close()

        val error = assertThrows(IllegalStateException::class.java) {
            fs.list("")
        }
        assertThat(error.message).contains("닫혔습니다")
    }

    @Test
    fun disconnectKeepsRepositoryReusable() {
        val fs = SmbFileSystem(testConfig())
        // 보관된 커넥션이 없는 상태에서도 안전하게 호출되어야 한다.
        fs.disconnect()

        // disconnect는 영구 종료가 아니므로 다음 접근은 재연결을 시도한다(닫힘 예외가 아니다).
        val reconnectError = runCatching { fs.list("") }.exceptionOrNull()
        assertThat(reconnectError).isNotNull()
        assertThat(reconnectError).isNotInstanceOf(IllegalStateException::class.java)
        assertThat(reconnectError!!.message.orEmpty()).doesNotContain("닫혔습니다")

        // 멱등: 반복 호출해도 예외가 없고, 이후에도 재연결 대상으로 남는다.
        fs.disconnect()
        val secondError = runCatching { fs.stat("") }.exceptionOrNull()
        assertThat(secondError).isNotNull()
        assertThat(secondError).isNotInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun closeAfterDisconnectStillTerminatesRepository() {
        val fs = SmbFileSystem(testConfig())
        fs.disconnect()
        fs.close()

        val error = assertThrows(IllegalStateException::class.java) {
            fs.open("Book.cbz")
        }
        assertThat(error.message).contains("닫혔습니다")
    }

    @Test
    fun disconnectAfterCloseDoesNotResurrectRepository() {
        val fs = SmbFileSystem(testConfig())
        fs.close()
        fs.disconnect()

        val error = assertThrows(IllegalStateException::class.java) {
            fs.stat("")
        }
        assertThat(error.message).contains("닫혔습니다")
    }

    /** 재연결 시도가 실제 서버에 닿지 않도록 기본 포트(445)가 아닌 닫힌 포트를 쓴다. */
    private fun testConfig() = NetworkSourceConfig(
        id = "network-smb-test",
        protocol = NetworkProtocol.SMB,
        name = "NAS",
        host = "127.0.0.1",
        port = 1445,
        share = "comics",
    ).validated()
}
