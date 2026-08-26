package com.comics8.core.network

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

class DynamicProxySelector : ProxySelector() {
    @Volatile
    var currentProxy: Proxy = Proxy.NO_PROXY

    override fun select(uri: URI?): List<Proxy> = listOf(currentProxy)

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // Ignored fallback
    }
}

class DynamicProxyAuthenticator : Authenticator {
    @Volatile
    var credentials: String? = null

    override fun authenticate(route: Route?, response: Response): Request? {
        val creds = credentials ?: return null
        if (response.request.header("Proxy-Authorization") != null) return null
        return response.request.newBuilder()
            .header("Proxy-Authorization", creds)
            .build()
    }
}
