package com.comics8.core.network

import com.comics8.core.model.CustomProxyConfig
import com.comics8.core.model.NetworkSettings
import com.comics8.core.model.ProxyType
import com.comics8.core.source.SourceLocator
import com.comics8.core.source.SourceRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class NetworkSettingsTest {

    @Test
    fun loadAndSaveNetworkSettings() {
        val map = mutableMapOf<String, String>()
        val defaultSettings = NetworkSettingsStorage.load { k, def -> map[k] ?: def }
        assertThat(defaultSettings.proxyType).isEqualTo(ProxyType.DIRECT)

        val custom = NetworkSettings(
            proxyType = ProxyType.CUSTOM_SOCKS,
            customProxy = CustomProxyConfig(
                host = "10.0.0.1",
                port = 9050,
                username = "admin",
                password = "secret",
            )
        )
        NetworkSettingsStorage.save(custom) { k, v -> if (v != null) map[k] = v else map.remove(k) }

        val loaded = NetworkSettingsStorage.load { k, def -> map[k] ?: def }
        assertThat(loaded.proxyType).isEqualTo(ProxyType.CUSTOM_SOCKS)
        assertThat(loaded.customProxy.host).isEqualTo("10.0.0.1")
        assertThat(loaded.customProxy.port).isEqualTo(9050)
        assertThat(loaded.customProxy.username).isEqualTo("admin")
        assertThat(loaded.customProxy.password).isEqualTo("secret")
    }

    @Test
    fun applyNetworkSettingsUpdatesToonClient() {
        val client = ToonClient(sources = SourceLocator { SourceRegistry() })

        // 1. Direct
        client.applyNetworkSettings(NetworkSettings(proxyType = ProxyType.DIRECT))
        assertThat(client.isProxyEnabled).isFalse()
        assertThat(client.proxySelector.currentProxy).isEqualTo(Proxy.NO_PROXY)
        assertThat(client.proxyAuthenticator.credentials).isNull()

        // 2. Server
        client.applyNetworkSettings(NetworkSettings(proxyType = ProxyType.SERVER), "https://my-sync.example.com/api/comics8/sync")
        assertThat(client.isProxyEnabled).isTrue()
        assertThat(client.proxyBaseUrl).isEqualTo("https://my-sync.example.com/api/comics8/proxy")
        assertThat(client.proxySelector.currentProxy).isEqualTo(Proxy.NO_PROXY)

        // 3. Custom HTTP
        client.applyNetworkSettings(
            NetworkSettings(
                proxyType = ProxyType.CUSTOM_HTTP,
                customProxy = CustomProxyConfig(host = "127.0.0.1", port = 8080, username = "u", password = "p")
            )
        )
        assertThat(client.isProxyEnabled).isFalse()
        assertThat(client.proxySelector.currentProxy.type()).isEqualTo(Proxy.Type.HTTP)
        val httpAddr = client.proxySelector.currentProxy.address() as InetSocketAddress
        assertThat(httpAddr.hostString).isEqualTo("127.0.0.1")
        assertThat(httpAddr.port).isEqualTo(8080)
        assertThat(client.proxyAuthenticator.credentials).isNotNull()

        // 4. Custom SOCKS
        client.applyNetworkSettings(
            NetworkSettings(
                proxyType = ProxyType.CUSTOM_SOCKS,
                customProxy = CustomProxyConfig(host = "192.168.1.50", port = 1080)
            )
        )
        assertThat(client.isProxyEnabled).isFalse()
        assertThat(client.proxySelector.currentProxy.type()).isEqualTo(Proxy.Type.SOCKS)
        val socksAddr = client.proxySelector.currentProxy.address() as InetSocketAddress
        assertThat(socksAddr.hostString).isEqualTo("192.168.1.50")
        assertThat(socksAddr.port).isEqualTo(1080)
        assertThat(client.proxyAuthenticator.credentials).isNull()
    }
}
