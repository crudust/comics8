package com.comics8.core.network

import com.comics8.core.model.CustomProxyConfig
import com.comics8.core.model.NetworkSettings
import com.comics8.core.model.ProxyType

object NetworkSettingsStorage {
    const val KEY_PROXY_TYPE = "network_proxy_type"
    const val KEY_CUSTOM_HOST = "network_custom_host"
    const val KEY_CUSTOM_PORT = "network_custom_port"
    const val KEY_CUSTOM_USER = "network_custom_user"
    const val KEY_CUSTOM_PASS = "network_custom_pass"

    fun load(getPref: (String, String?) -> String?): NetworkSettings {
        val typeStr = getPref(KEY_PROXY_TYPE, ProxyType.DIRECT.name)
        val type = ProxyType.fromName(typeStr, ProxyType.DIRECT)
        val host = getPref(KEY_CUSTOM_HOST, "") ?: ""
        val port = getPref(KEY_CUSTOM_PORT, "1080")?.toIntOrNull() ?: 1080
        val user = getPref(KEY_CUSTOM_USER, "") ?: ""
        val pass = getPref(KEY_CUSTOM_PASS, "") ?: ""
        return NetworkSettings(
            proxyType = type,
            customProxy = CustomProxyConfig(host, port, user, pass),
        )
    }

    fun save(settings: NetworkSettings, setPref: (String, String?) -> Unit) {
        setPref(KEY_PROXY_TYPE, settings.proxyType.name)
        setPref(KEY_CUSTOM_HOST, settings.customProxy.host)
        setPref(KEY_CUSTOM_PORT, settings.customProxy.port.toString())
        setPref(KEY_CUSTOM_USER, settings.customProxy.username)
        setPref(KEY_CUSTOM_PASS, settings.customProxy.password)
    }
}
