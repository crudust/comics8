package com.comics8.core.network

import com.comics8.core.source.FetchSpec
import com.comics8.core.source.HttpResult
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceLocator
import com.comics8.core.sync.SyncConstants
import com.comics8.core.sync.addComics8SyncHeaders
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import com.comics8.core.model.CustomProxyConfig
import com.comics8.core.model.NetworkSettings
import com.comics8.core.model.ProxyType

class ToonClient(
    private val client: OkHttpClient,
    @Volatile var proxyBaseUrl: String? = SyncConstants.proxyBaseUrl(),
    @Volatile var isProxyEnabled: Boolean = false,
    val proxySelector: DynamicProxySelector = DynamicProxySelector(),
    val proxyAuthenticator: DynamicProxyAuthenticator = DynamicProxyAuthenticator(),
    private val sources: SourceLocator,
) : SourceHttp {

    @Volatile
    var serverProxySyncKey: String? = null

    constructor(
        proxyBaseUrl: String? = SyncConstants.proxyBaseUrl(),
        isProxyEnabled: Boolean = false,
        proxySelector: DynamicProxySelector = DynamicProxySelector(),
        proxyAuthenticator: DynamicProxyAuthenticator = DynamicProxyAuthenticator(),
        sources: SourceLocator,
    ) : this(
        client = defaultClient(proxySelector = proxySelector, proxyAuthenticator = proxyAuthenticator),
        proxyBaseUrl = proxyBaseUrl,
        isProxyEnabled = isProxyEnabled,
        proxySelector = proxySelector,
        proxyAuthenticator = proxyAuthenticator,
        sources = sources,
    )

    val httpClient: OkHttpClient get() = client

    internal var clock: () -> Long = { System.currentTimeMillis() }

    @Volatile
    private var proxyCoolDownUntil = 0L
    private val consecutiveProxyFailures = AtomicInteger(0)

    private val defaultHtmlPolicy = RequestPolicy(
        userAgent = USER_AGENT,
        extraHeaders = mapOf(
            "Accept" to "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ko-KR,ko;q=0.9,en;q=0.8",
        ),
    )

    fun fetch(url: String): String = fetchText(FetchSpec(url, defaultHtmlPolicy))

    override fun fetch(spec: FetchSpec): HttpResult {
        val proxyBase = proxyBaseUrl
        if (isProxyEnabled &&
            !proxyBase.isNullOrBlank() &&
            !spec.url.contains("/proxy?url=") &&
            shouldUseProxy(spec.url) &&
            clock() >= proxyCoolDownUntil
        ) {
            val proxiedUrl = SyncConstants.proxyUrl(spec.url, proxyBase)
            try {
                val proxied = executeFetch(spec.copy(url = proxiedUrl))
                if (proxied.code in 200..299) {
                    consecutiveProxyFailures.set(0)
                    return proxied
                }
                System.err.println("ToonClient: Proxy fetch failed ($proxiedUrl): HTTP ${proxied.code}. Falling back to direct fetch.")
                recordProxyFailure()
            } catch (e: Exception) {
                System.err.println("ToonClient: Proxy fetch failed ($proxiedUrl): ${e.message}. Falling back to direct fetch.")
                recordProxyFailure()
            }
        }
        return executeFetch(spec)
    }

    private fun recordProxyFailure() {
        val failures = consecutiveProxyFailures.incrementAndGet()
        if (failures >= PROXY_FAILURE_THRESHOLD) {
            proxyCoolDownUntil = clock() + PROXY_COOLDOWN_MS
            consecutiveProxyFailures.set(0)
        }
    }

    override fun fetchText(spec: FetchSpec): String {
        val result = fetch(spec)
        if (result.code !in 200..299) {
            error("HTTP ${result.code}")
        }
        return result.body.toString(Charsets.UTF_8)
    }

    override fun isAccessible(url: String, policy: RequestPolicy): Boolean {
        if (url.isBlank()) return false
        return try {
            executeHead(FetchSpec(url, policy))
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Downloads raw bytes for an image URL.
     */
    fun fetchBytes(url: String): ByteArray {
        val registry = sources.registry()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .header("Referer", ImageReferer.forUrl(url, registry))
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun executeFetch(spec: FetchSpec): HttpResult {
        val request = buildRequest(spec, head = false)
        client.newCall(request).execute().use { response ->
            val headers = linkedMapOf<String, String>()
            for (name in response.headers.names()) {
                val value = response.header(name) ?: continue
                headers[name] = value
            }
            val rawBody = response.body?.bytes() ?: ByteArray(0)
            val result = HttpResult(response.code, headers, rawBody)
            return applyRangeSlice(spec, result)
        }
    }

    private val headClient by lazy {
        client.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    private fun executeHead(spec: FetchSpec): Boolean {
        val request = buildRequest(spec, head = true)
        return headClient.newCall(request).execute().use { it.isSuccessful }
    }

    private fun buildRequest(spec: FetchSpec, head: Boolean): Request {
        val builder = Request.Builder().url(spec.url)
        builder.header("User-Agent", spec.policy.userAgent)
        val referer = spec.policy.referer
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        for ((key, value) in spec.policy.extraHeaders) {
            if (key.equals("Range", ignoreCase = true)) continue
            builder.header(key, value)
        }
        for ((key, value) in spec.headers) {
            builder.header(key, value)
        }
        val proxyRoot = proxyBaseUrl?.trimEnd('/')
        val syncKey = serverProxySyncKey?.trim()
        if (!proxyRoot.isNullOrBlank() && !syncKey.isNullOrBlank() &&
            (spec.url.startsWith("$proxyRoot?") || spec.url.startsWith("$proxyRoot/"))
        ) {
            builder.addComics8SyncHeaders(syncKey)
        }
        if (head) builder.head() else builder.get()
        return builder.build()
    }

    private fun applyRangeSlice(spec: FetchSpec, result: HttpResult): HttpResult {
        val rangeHeader = spec.headers.entries
            .firstOrNull { it.key.equals("Range", ignoreCase = true) }
            ?.value
            ?: return result
        if (result.code != 200) return result
        val total = result.body.size
        val bounds = parseBytesRange(rangeHeader, total) ?: return result
        val (start, endInclusive) = bounds
        if (start == 0 && endInclusive == total - 1) return result
        val sliced = result.body.copyOfRange(start, endInclusive + 1)
        val headers = LinkedHashMap(result.headers)
        putHeader(headers, "Content-Length", sliced.size.toString())
        putHeader(headers, "Content-Range", "bytes $start-$endInclusive/$total")
        return HttpResult(result.code, headers, sliced)
    }

    internal fun shouldUseProxy(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val source = sources.registry().sourceForUrl(url) ?: return false
        return source.useProxy(url)
    }

    fun applyNetworkSettings(
        settings: NetworkSettings,
        serverUrl: String = SyncConstants.DEFAULT_SERVER_URL,
    ) {
        when (settings.proxyType) {
            ProxyType.DIRECT -> {
                isProxyEnabled = false
                proxySelector.currentProxy = java.net.Proxy.NO_PROXY
                proxyAuthenticator.credentials = null
            }
            ProxyType.SERVER -> {
                isProxyEnabled = true
                proxyBaseUrl = SyncConstants.proxyBaseUrl(serverUrl)
                proxySelector.currentProxy = java.net.Proxy.NO_PROXY
                proxyAuthenticator.credentials = null
            }
            ProxyType.CUSTOM_HTTP -> {
                isProxyEnabled = false
                val host = settings.customProxy.host.trim().ifBlank { "127.0.0.1" }
                val port = settings.customProxy.port.coerceIn(1, 65535)
                proxySelector.currentProxy = java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved(host, port))
                proxyAuthenticator.credentials = if (settings.customProxy.username.isNotBlank()) {
                    okhttp3.Credentials.basic(settings.customProxy.username, settings.customProxy.password)
                } else null
            }
            ProxyType.CUSTOM_SOCKS -> {
                isProxyEnabled = false
                val host = settings.customProxy.host.trim().ifBlank { "127.0.0.1" }
                val port = settings.customProxy.port.coerceIn(1, 65535)
                proxySelector.currentProxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved(host, port))
                proxyAuthenticator.credentials = if (settings.customProxy.username.isNotBlank()) {
                    okhttp3.Credentials.basic(settings.customProxy.username, settings.customProxy.password)
                } else null
            }
        }
    }

    suspend fun testProxyConnection(
        type: ProxyType,
        config: CustomProxyConfig,
    ): Result<Long> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val start = System.currentTimeMillis()
            val host = config.host.trim().ifBlank { "127.0.0.1" }
            val port = config.port.coerceIn(1, 65535)
            val proxy = when (type) {
                ProxyType.CUSTOM_HTTP -> java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port))
                ProxyType.CUSTOM_SOCKS -> java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress(host, port))
                else -> java.net.Proxy.NO_PROXY
            }
            val testClient = OkHttpClient.Builder()
                .proxy(proxy)
                .apply {
                    if (config.username.isNotBlank()) {
                        proxyAuthenticator { _, response ->
                            response.request.newBuilder()
                                .header("Proxy-Authorization", okhttp3.Credentials.basic(config.username, config.password))
                                .build()
                        }
                    }
                }
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("https://www.google.com/generate_204")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            testClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 204) {
                    error("HTTP ${response.code}")
                }
            }
            System.currentTimeMillis() - start
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        const val HTTP_CACHE_MAX_BYTES = 20L * 1024 * 1024
        const val PROXY_FAILURE_THRESHOLD = 3
        const val PROXY_COOLDOWN_MS = 60_000L

        fun defaultClient(
            cacheDirectory: File? = null,
            proxySelector: java.net.ProxySelector = DynamicProxySelector(),
            proxyAuthenticator: okhttp3.Authenticator = DynamicProxyAuthenticator(),
        ): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .proxySelector(proxySelector)
                .proxyAuthenticator(proxyAuthenticator)
                .dns(FallbackDns)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
            if (cacheDirectory != null) {
                cacheDirectory.mkdirs()
                builder.cache(Cache(cacheDirectory, HTTP_CACHE_MAX_BYTES))
            }
            return builder.build()
        }

        fun newImageHttpClient(
            baseClient: OkHttpClient = defaultClient(),
            sources: com.comics8.core.source.SourceRegistry,
        ): OkHttpClient {
            return baseClient.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .addInterceptor(ComicImageInterceptor(sources))
                .build()
        }


        private fun parseBytesRange(header: String, total: Int): Pair<Int, Int>? {
            if (total <= 0) return null
            val value = header.trim()
            if (!value.startsWith("bytes=", ignoreCase = true)) return null
            val spec = value.substringAfter('=')
            val dash = spec.indexOf('-')
            if (dash < 0) return null
            val startRaw = spec.substring(0, dash)
            val endRaw = spec.substring(dash + 1)
            return when {
                startRaw.isEmpty() && endRaw.isNotEmpty() -> {
                    val suffix = endRaw.toIntOrNull() ?: return null
                    if (suffix <= 0) return null
                    val start = (total - suffix).coerceAtLeast(0)
                    start to (total - 1)
                }
                startRaw.isNotEmpty() -> {
                    val start = startRaw.toIntOrNull() ?: return null
                    val end = if (endRaw.isEmpty()) total - 1 else endRaw.toIntOrNull() ?: return null
                    if (start < 0 || start >= total || end < start) return null
                    start to end.coerceAtMost(total - 1)
                }
                else -> null
            }
        }

        private fun putHeader(headers: MutableMap<String, String>, name: String, value: String) {
            val existing = headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (existing != null) {
                headers[existing] = value
            } else {
                headers[name] = value
            }
        }
    }
}
