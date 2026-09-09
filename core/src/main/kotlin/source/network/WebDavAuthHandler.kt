package com.comics8.core.source.network

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

internal class WebDavAuthHandler(
    private val config: NetworkSourceConfig,
) : Authenticator, Interceptor {

    internal data class DigestChallenge(
        val realm: String,
        val nonce: String,
        val qop: String? = null,
        val algorithm: String? = null,
        val opaque: String? = null,
    )

    private val lock = Any()
    @Volatile private var cachedDigest: DigestChallenge? = null
    private val ncCounter = AtomicInteger(0)

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (config.username.isBlank()) {
            return chain.proceed(original)
        }
        if (original.header("Authorization") != null) {
            return chain.proceed(original)
        }
        val authenticatedRequest = buildPreemptiveRequest(original)
        return chain.proceed(authenticatedRequest)
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        if (config.username.isBlank()) return null
        if (responseCount(response) >= 3) return null

        val wwwAuthList = response.headers("WWW-Authenticate")
        if (wwwAuthList.isEmpty()) return null

        // 1. Digest 헤더 우선 파싱
        val digestHeader = wwwAuthList.firstOrNull { it.startsWith("Digest", ignoreCase = true) }
        if (digestHeader != null) {
            val challenge = parseDigestChallenge(digestHeader) ?: return null
            synchronized(lock) {
                cachedDigest = challenge
                ncCounter.set(0)
            }
            val digestAuth = buildDigestAuthorization(
                challenge = challenge,
                method = response.request.method,
                url = response.request.url,
            )
            return response.request.newBuilder()
                .header("Authorization", digestAuth)
                .build()
        }

        // 2. Basic 헤더 파싱
        val basicHeader = wwwAuthList.firstOrNull { it.startsWith("Basic", ignoreCase = true) }
        if (basicHeader != null) {
            val previousAuth = response.request.header("Authorization")
            if (previousAuth != null && previousAuth.startsWith("Basic ", ignoreCase = true)) {
                return null
            }
            val basicAuth = Credentials.basic(config.username, config.password, StandardCharsets.UTF_8)
            return response.request.newBuilder()
                .header("Authorization", basicAuth)
                .build()
        }

        return null
    }

    private fun buildPreemptiveRequest(request: Request): Request {
        val challenge = cachedDigest
        return if (challenge != null) {
            val digestAuth = buildDigestAuthorization(
                challenge = challenge,
                method = request.method,
                url = request.url,
            )
            request.newBuilder()
                .header("Authorization", digestAuth)
                .build()
        } else {
            val basicAuth = Credentials.basic(config.username, config.password, StandardCharsets.UTF_8)
            request.newBuilder()
                .header("Authorization", basicAuth)
                .build()
        }
    }

    private fun buildDigestAuthorization(
        challenge: DigestChallenge,
        method: String,
        url: HttpUrl,
    ): String {
        val username = config.username
        val password = config.password
        val realm = challenge.realm
        val nonce = challenge.nonce
        val algorithm = challenge.algorithm ?: "MD5"
        val opaque = challenge.opaque

        val uri = if (url.encodedQuery != null) {
            "${url.encodedPath}?${url.encodedQuery}"
        } else {
            url.encodedPath
        }

        val qopList = challenge.qop?.split(",")?.map { it.trim().lowercase() } ?: emptyList()
        val useAuthQop = qopList.contains("auth")
        val isSess = algorithm.contains("sess", ignoreCase = true)
        val cnonce = if (useAuthQop || isSess) {
            UUID.randomUUID().toString().replace("-", "").take(16)
        } else ""

        val ha1 = when {
            isSess -> {
                val base = hashHex(algorithm, "$username:$realm:$password")
                hashHex(algorithm, "$base:$nonce:$cnonce")
            }
            else -> hashHex(algorithm, "$username:$realm:$password")
        }

        val ha2 = hashHex(algorithm, "$method:$uri")

        val response: String
        val ncStr: String
        if (useAuthQop) {
            val nc = ncCounter.incrementAndGet()
            ncStr = "%08x".format(nc)
            response = hashHex(algorithm, "$ha1:$nonce:$ncStr:$cnonce:auth:$ha2")
        } else {
            ncStr = ""
            response = hashHex(algorithm, "$ha1:$nonce:$ha2")
        }

        val parts = mutableListOf<String>()
        parts.add("""username="$username"""")
        parts.add("""realm="$realm"""")
        parts.add("""nonce="$nonce"""")
        parts.add("""uri="$uri"""")
        if (useAuthQop) {
            parts.add("""cnonce="$cnonce"""")
            parts.add("nc=$ncStr")
            parts.add("qop=auth")
        } else if (isSess) {
            parts.add("""cnonce="$cnonce"""")
        }
        parts.add("""response="$response"""")
        if (challenge.algorithm != null) {
            parts.add("algorithm=${challenge.algorithm}")
        }
        if (opaque != null) {
            parts.add("""opaque="$opaque"""")
        }
        return "Digest " + parts.joinToString(", ")
    }

    internal fun parseDigestChallenge(header: String): DigestChallenge? {
        val trimmed = header.trim()
        if (!trimmed.startsWith("Digest", ignoreCase = true)) return null
        val paramsPart = trimmed.substring(6).trim()
        val regex = Regex("""([a-zA-Z0-9_-]+)\s*=\s*(?:"([^"]*)"|([^, \t\r\n]+))""")
        val params = mutableMapOf<String, String>()
        for (match in regex.findAll(paramsPart)) {
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            params[key] = value
        }
        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        return DigestChallenge(
            realm = realm,
            nonce = nonce,
            qop = params["qop"],
            algorithm = params["algorithm"],
            opaque = params["opaque"],
        )
    }

    private fun hashHex(algorithm: String, data: String): String {
        val algoName = when (algorithm.uppercase()) {
            "SHA-256", "SHA-256-SESS" -> "SHA-256"
            else -> "MD5"
        }
        val digest = MessageDigest.getInstance(algoName).digest(data.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result++
            prior = prior.priorResponse
        }
        return result
    }
}
