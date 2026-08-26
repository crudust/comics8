package com.comics8.core.model

enum class ProxyType {
    DIRECT,
    SERVER,
    CUSTOM_HTTP,
    CUSTOM_SOCKS;

    companion object {
        fun fromName(name: String?, default: ProxyType = DIRECT): ProxyType {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: default
        }
    }
}

data class CustomProxyConfig(
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
)

data class NetworkSettings(
    val proxyType: ProxyType = ProxyType.DIRECT,
    val customProxy: CustomProxyConfig = CustomProxyConfig(),
)
