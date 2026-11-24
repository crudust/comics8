package com.comics8.shared.image

import coil3.Extras
import coil3.Uri
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.network.httpHeaders
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.comics8.core.network.ComicImageInterceptor
import java.io.IOException

/**
 * Retries alternate image URLs when the primary HTTP response returned bytes that failed to decode
 * (for example, an HTML error page or corrupt payload served with HTTP 200). Network/HTTP errors
 * are left to [ComicImageInterceptor], which enforces the auto vs manual-retry policy.
 */
class FallbackImageInterceptor(
    private val fallbacksProvider: (String) -> List<String>,
    private val diskCacheProvider: () -> DiskCache? = { null },
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = chain.proceed()
        if (result !is ErrorResult) return result
        if (chain.request.extras[IsFallbackAttemptKey] == true) return result
        if (result.throwable is IOException || result.throwable is HttpException) return result

        val originalUrl = when (val data = chain.request.data) {
            is String -> data
            is Uri -> data.toString()
            else -> return result
        }
        if (!originalUrl.startsWith("http://", ignoreCase = true) &&
            !originalUrl.startsWith("https://", ignoreCase = true)
        ) {
            return result
        }

        diskCacheProvider()?.remove(chain.request.diskCacheKey ?: originalUrl)

        val isManualRetry = chain.request.httpHeaders[ComicImageInterceptor.HEADER_RETRY_MODE]
            .equals(ComicImageInterceptor.RETRY_MODE_MANUAL, ignoreCase = true)
        val limit = if (isManualRetry) {
            ComicImageInterceptor.MAX_MANUAL_FALLBACKS
        } else {
            ComicImageInterceptor.MAX_FALLBACKS_DEFAULT
        }
        val candidates = fallbacksProvider(originalUrl).take(limit)
        for (candidate in candidates) {
            val candidateRequest = chain.request.newBuilder()
                .data(candidate)
                .apply {
                    extras[IsFallbackAttemptKey] = true
                    // Keep the original memory cache key if the caller supplied one (role-partitioned).
                    chain.request.memoryCacheKey?.let(::memoryCacheKey)
                }
                .build()
            val candidateResult = chain.withRequest(candidateRequest).proceed()
            if (candidateResult is SuccessResult) {
                return candidateResult
            }
        }
        return result
    }

    private companion object {
        val IsFallbackAttemptKey: Extras.Key<Boolean> = Extras.Key(false)
    }
}
