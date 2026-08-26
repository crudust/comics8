package com.comics8.core.source.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.io.path.createTempDirectory

class NetworkSourceConfigTest {
    @Test
    fun legacyColonIdIsMigratedToWorkIdSafeForm() {
        val config = NetworkSourceConfig(
            id = "network:legacy",
            protocol = NetworkProtocol.SMB,
            name = "NAS",
            host = "nas",
            share = "media",
        ).validated()

        assertThat(config.id).isEqualTo("network-legacy")
        assertThat(com.comics8.core.source.WorkId(config.id, "book").sourceId).isEqualTo("network-legacy")
    }

    @Test
    fun smbValidationNormalizesConnection() {
        val config = NetworkSourceConfig(
            id = "network-test",
            protocol = NetworkProtocol.SMB,
            name = " NAS ",
            host = "smb://nas.local/",
            share = "/Comics/",
            path = "Manga\\Korean",
            username = " user ",
        ).validated()

        assertThat(config.name).isEqualTo("NAS")
        assertThat(config.host).isEqualTo("nas.local")
        assertThat(config.share).isEqualTo("Comics")
        assertThat(config.path).isEqualTo("Manga/Korean")
        assertThat(config.username).isEqualTo("user")
    }

    @Test
    fun storeRoundTripsAndDeletesConnections() {
        val dir = createTempDirectory("network-store").toFile().apply { deleteOnExit() }
        val store = NetworkSourceStore(dir.resolve("connections.json"))
        val config = NetworkSourceConfig(
            id = "network-test",
            protocol = NetworkProtocol.WEBDAV,
            name = "DAV",
            url = "https://example.com/dav",
            username = "me",
            password = "secret",
        )

        store.save(config)
        assertThat(store.all()).containsExactly(config.copy(url = "https://example.com/dav/"))

        store.delete(config.id)
        assertThat(store.all()).isEmpty()
    }

    @Test
    fun parentTraversalIsRejected() {
        val config = NetworkSourceConfig(
            protocol = NetworkProtocol.SMB,
            name = "NAS",
            host = "nas",
            share = "media",
            path = "../private",
        )
        val result = runCatching { config.validated() }
        assertThat(result.isFailure).isTrue()
    }
}
