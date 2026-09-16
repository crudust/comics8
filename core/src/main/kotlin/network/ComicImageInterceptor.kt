package com.comics8.core.network

import com.comics8.core.source.SourceRegistry
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Common OkHttp interceptor that handles:
 * 1. Automatic Referer header injection based on SourceRegistry (e.g. Hitomi CDN requires hitomi.la Referer)
 * 2. User-Agent header injection
 * 3. Automatic 404/5xx fallback URL retry (e.g. thumb fallbacks: webp -> avif -> alt hosts)
 */
class ComicImageInterceptor(
    private val registry: SourceRegistry,
    private val maxFallbacks: Int = MAX_FALLBACKS_DEFAULT,
) : Interceptor {
    companion object {
        const val MAX_FALLBACKS_DEFAULT = 2

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
    }

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

        val referer = originalRequest.header("Referer") ?: ImageReferer.forUrl(originalUrl, registry)
        val userAgent = originalRequest.header("User-Agent") ?: ToonClient.USER_AGENT

        val requestWithHeaders = originalRequest.newBuilder()
            .apply {
                if (originalRequest.header("Referer") == null && referer.isNotBlank()) {
                    header("Referer", referer)
                }
                if (originalRequest.header("User-Agent") == null) {
                    header("User-Agent", userAgent)
                }
            }
            .build()

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

        if (response != null && response.isSuccessful) {
            return response
        }

        val shouldTryFallback = !chain.call().isCanceled() && when {
            response != null -> response.code == 404 || response.code >= 500
            firstError != null -> isUnrecoverableHostError(firstError)
            else -> false
        }

        if (shouldTryFallback) {
            val fallbacks = ImageFallbacks.forUrl(originalUrl, registry).take(maxFallbacks)
            for (fallbackUrl in fallbacks) {
                if (chain.call().isCanceled()) {
                    response?.close()
                    throw firstError ?: IOException("Canceled")
                }

                response?.close()
                response = null

                val fallbackReferer = ImageReferer.forUrl(fallbackUrl, registry)
                val fallbackReq = requestWithHeaders.newBuilder()
                    .url(fallbackUrl)
                    .apply {
                        if (fallbackReferer.isNotBlank()) {
                            header("Referer", fallbackReferer)
                        }
                    }
                    .build()

                val retryResp = try {
                    chain.proceed(fallbackReq)
                } catch (e: Exception) {
                    firstError = e
                    null
                }

                if (chain.call().isCanceled()) {
                    retryResp?.close()
                    throw firstError ?: IOException("Canceled")
                }

                if (retryResp != null) {
                    if (retryResp.isSuccessful) {
                        return retryResp
                    }
                    response = retryResp
                }
            }
        }

        if (response != null) {
            return response
        }
        throw firstError ?: IOException("failed to load image: $originalUrl")
    }
}
