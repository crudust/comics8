package com.comics8.core.network

import com.comics8.core.source.SourceRegistry
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/**
 * Common OkHttp interceptor that handles:
 * 1. Automatic Referer and User-Agent header injection based on SourceRegistry
 * 2. Automatic fallback URL retry (404, 5xx, cross-host 403, HTML 200 error pages, unrecoverable host errors)
 * 3. Session-level sticky host affinity with self-healing and host demotion
 * 4. Manual retry mode (`X-Comics8-Retry-Mode: manual`) expanding fallback triggers and attempt cap
 */
class ComicImageInterceptor(
    private val registry: SourceRegistry,
    private val maxFallbacks: Int = MAX_FALLBACKS_DEFAULT,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : Interceptor {
    companion object {
        const val MAX_FALLBACKS_DEFAULT = 3
        const val MAX_MANUAL_FALLBACKS = 12
        const val HEADER_RETRY_MODE = "X-Comics8-Retry-Mode"
        const val RETRY_MODE_MANUAL = "manual"
        const val HOST_PREFERENCE_TTL_MS = 2 * 60 * 1000L
        const val HOST_DEMOTION_TTL_MS = 60 * 1000L
        const val SOFT_FAILURE_PROMOTE_THRESHOLD = 2
        private const val MAX_HOST_STATE_ENTRIES = 128

        internal fun isUnrecoverableHostError(e: Throwable?): Boolean {
            var current = e
            while (current != null) {
                if (current is SocketTimeoutException) {
                    return false
                }
                if (current is UnknownHostException ||
                    current is ConnectException ||
                    current is SSLException
                ) {
                    return true
                }
                current = current.cause
            }
            return false
        }

        internal fun isHtmlErrorResponse(response: Response): Boolean {
            if (!response.isSuccessful) return false
            val contentType = response.header("Content-Type")?.trim()?.lowercase() ?: return false
            return contentType.startsWith("text/html") || contentType.startsWith("application/xhtml+xml")
        }

        private fun isUsableResponse(response: Response?): Boolean =
            response != null && response.isSuccessful && !isHtmlErrorResponse(response)
    }

    private data class PreferredHost(val targetHost: String, val expiresAtMs: Long)

    private val preferredHosts = ConcurrentHashMap<String, PreferredHost>()
    private val degradedHosts = ConcurrentHashMap<String, Long>()
    private val softRecoveryCounts = ConcurrentHashMap<Pair<String, String>, Int>()

    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.call().isCanceled()) {
            throw IOException("Canceled")
        }

        val originalRequest = chain.request()
        val originalUrl = originalRequest.url.toString()

        if (!originalUrl.startsWith("http://", ignoreCase = true) &&
            !originalUrl.startsWith("https://", ignoreCase = true)
        ) {
            return chain.proceed(originalRequest)
        }

        val isManualRetry = originalRequest.header(HEADER_RETRY_MODE).equals(RETRY_MODE_MANUAL, ignoreCase = true)
        val referer = originalRequest.header("Referer") ?: ImageReferer.forUrl(originalUrl, registry)
        val userAgent = originalRequest.header("User-Agent") ?: ToonClient.USER_AGENT

        val requestWithHeaders = originalRequest.newBuilder()
            .removeHeader(HEADER_RETRY_MODE)
            .apply {
                if (originalRequest.header("Referer") == null && referer.isNotBlank()) {
                    header("Referer", referer)
                }
                if (originalRequest.header("User-Agent") == null) {
                    header("User-Agent", userAgent)
                }
            }
            .build()

        val origHost = SourceRegistry.hostOf(originalUrl)
        var triedPreferredUrl: String? = null
        val pref = if (origHost != null) activePreferredHost(origHost) else null
        if (pref != null && origHost != null) {
            val preferredUrl = ImageFallbacks.forUrl(originalUrl, registry)
                .firstOrNull { SourceRegistry.hostOf(it) == pref.targetHost }
            if (preferredUrl != null) {
                triedPreferredUrl = preferredUrl
                val prefReq = buildFallbackRequest(requestWithHeaders, preferredUrl)
                var prefError: Exception? = null
                val prefResp = try {
                    chain.proceed(prefReq)
                } catch (e: Exception) {
                    prefError = e
                    null
                }
                if (chain.call().isCanceled()) {
                    prefResp?.close()
                    throw prefError ?: IOException("Canceled")
                }
                if (isUsableResponse(prefResp)) {
                    degradedHosts.remove(pref.targetHost)
                    return prefResp!!
                }
                preferredHosts.remove(origHost, pref)
                markDegraded(pref.targetHost)
                prefResp?.close()
            }
        }

        var firstError: Exception? = null
        var response = try {
            chain.proceed(requestWithHeaders)
        } catch (e: Exception) {
            firstError = e
            null
        }

        if (chain.call().isCanceled()) {
            response?.close()
            throw firstError ?: IOException("Canceled")
        }

        if (isUsableResponse(response)) {
            if (origHost != null) {
                degradedHosts.remove(origHost)
                clearSoftRecoveryForOrigin(origHost)
            }
            return response!!
        }

        val initialStatus = response?.code
        val initialError = firstError
        val initialWasHtml = response != null && isHtmlErrorResponse(response)
        val shouldTryFallback = !chain.call().isCanceled() && when {
            isManualRetry -> true
            response != null -> initialStatus == 404 || (initialStatus ?: 0) >= 500 || initialStatus == 403 || initialWasHtml
            initialError != null -> isUnrecoverableHostError(initialError)
            else -> false
        }

        if (shouldTryFallback) {
            val candidates = selectFallbackCandidates(
                originalUrl = originalUrl,
                origHost = origHost,
                triedPreferredUrl = triedPreferredUrl,
                initialStatus = initialStatus,
                isManualRetry = isManualRetry,
            )
            for (fallbackUrl in candidates) {
                if (chain.call().isCanceled()) {
                    response?.close()
                    throw firstError ?: IOException("Canceled")
                }

                response?.close()
                response = null

                val fallbackReq = buildFallbackRequest(requestWithHeaders, fallbackUrl)
                var retryError: Exception? = null
                val retryResp = try {
                    chain.proceed(fallbackReq)
                } catch (e: Exception) {
                    retryError = e
                    firstError = e
                    null
                }

                if (chain.call().isCanceled()) {
                    retryResp?.close()
                    throw firstError ?: IOException("Canceled")
                }

                val fbHost = SourceRegistry.hostOf(fallbackUrl)
                if (isUsableResponse(retryResp)) {
                    if (fbHost != null) {
                        degradedHosts.remove(fbHost)
                    }
                    if (origHost != null && fbHost != null && origHost != fbHost) {
                        recordRecovery(
                            origHost = origHost,
                            fbHost = fbHost,
                            isHardHostFailure = initialError != null ||
                                (initialStatus != null && initialStatus >= 500) ||
                                initialWasHtml,
                        )
                    }
                    return retryResp!!
                }

                if (fbHost != null && isHardHostFailure(retryResp, retryError)) {
                    markDegraded(fbHost)
                }
                if (retryResp != null) {
                    response = retryResp
                }
            }
        }

        if (response != null) {
            return response
        }
        throw firstError ?: IOException("failed to load image: $originalUrl")
    }

    private fun buildFallbackRequest(baseRequest: Request, targetUrl: String): Request {
        val fallbackReferer = ImageReferer.forUrl(targetUrl, registry)
        return baseRequest.newBuilder()
            .url(targetUrl)
            .apply {
                if (fallbackReferer.isNotBlank()) {
                    header("Referer", fallbackReferer)
                }
            }
            .build()
    }

    private fun selectFallbackCandidates(
        originalUrl: String,
        origHost: String?,
        triedPreferredUrl: String?,
        initialStatus: Int?,
        isManualRetry: Boolean,
    ): List<String> {
        val now = clock()
        val raw = ImageFallbacks.forUrl(originalUrl, registry)
            .asSequence()
            .filter { it != originalUrl && it != triedPreferredUrl }
            .filter { candidate ->
                if (!isManualRetry && initialStatus == 403) {
                    val candHost = SourceRegistry.hostOf(candidate)
                    candHost != null && candHost != origHost
                } else {
                    true
                }
            }
            .toList()

        val healthy = ArrayList<String>(raw.size)
        val degraded = ArrayList<String>()
        for (candidate in raw) {
            val host = SourceRegistry.hostOf(candidate)
            if (host != null && isHostDegraded(host, now)) {
                degraded.add(candidate)
            } else {
                healthy.add(candidate)
            }
        }
        val limit = if (isManualRetry) MAX_MANUAL_FALLBACKS else maxFallbacks
        return (healthy + degraded).take(limit.coerceAtLeast(0))
    }

    private fun activePreferredHost(origHost: String): PreferredHost? {
        val pref = preferredHosts[origHost] ?: return null
        if (pref.expiresAtMs <= clock()) {
            preferredHosts.remove(origHost, pref)
            return null
        }
        return pref
    }

    private fun isHostDegraded(host: String, now: Long): Boolean {
        val expiresAt = degradedHosts[host] ?: return false
        if (expiresAt <= now) {
            degradedHosts.remove(host, expiresAt)
            return false
        }
        return true
    }

    private fun markDegraded(host: String) {
        putBounded(degradedHosts, host, clock() + HOST_DEMOTION_TTL_MS)
    }

    private fun promotePreferredHost(origHost: String, targetHost: String) {
        putBounded(preferredHosts, origHost, PreferredHost(targetHost, clock() + HOST_PREFERENCE_TTL_MS))
    }

    private fun recordRecovery(origHost: String, fbHost: String, isHardHostFailure: Boolean) {
        if (isHardHostFailure) {
            promotePreferredHost(origHost, fbHost)
            markDegraded(origHost)
            softRecoveryCounts.remove(origHost to fbHost)
            return
        }
        val key = origHost to fbHost
        val nextCount = (softRecoveryCounts[key] ?: 0) + 1
        if (nextCount >= SOFT_FAILURE_PROMOTE_THRESHOLD) {
            promotePreferredHost(origHost, fbHost)
            softRecoveryCounts.remove(key)
        } else {
            putBounded(softRecoveryCounts, key, nextCount)
        }
    }

    private fun clearSoftRecoveryForOrigin(origHost: String) {
        val it = softRecoveryCounts.keys.iterator()
        while (it.hasNext()) {
            if (it.next().first == origHost) {
                it.remove()
            }
        }
    }

    private fun isHardHostFailure(response: Response?, error: Exception?): Boolean {
        if (error != null && isUnrecoverableHostError(error)) return true
        if (response == null) return false
        return response.code >= 500 || isHtmlErrorResponse(response)
    }

    private fun <K : Any, V : Any> putBounded(map: ConcurrentHashMap<K, V>, key: K, value: V) {
        if (map.size >= MAX_HOST_STATE_ENTRIES && !map.containsKey(key)) {
            map.clear()
        }
        map[key] = value
    }
}
