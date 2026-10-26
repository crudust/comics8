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
}
