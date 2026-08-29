package com.comics8.core.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object FallbackDns : Dns {
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val MAX_CACHE_ENTRIES = 256
    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private val inFlightLocks = ConcurrentHashMap<String, Any>()
    private val dohClient = OkHttpClient.Builder()
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        })
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trim().lowercase().removeSuffix(".")
        if (host.isBlank()) throw UnknownHostException("hostname is blank")

        // 1. Fast cache check
        getCached(host)?.let { return it }

        // 2. Deduplicate concurrent lookups for the same host
        val lock = inFlightLocks.computeIfAbsent(host) { Any() }
        return try {
            synchronized(lock) {
                getCached(host)?.let { return@synchronized it }

                // Try system DNS first
                try {
                    val addresses = Dns.SYSTEM.lookup(host)
                    val hasIpv4 = addresses.any { it is Inet4Address }
                    if (hasIpv4) {
                        putCached(host, addresses)
                        return@synchronized addresses
                    }
                } catch (_: Exception) {
                }

                // Fallback to Cloudflare / Google DoH by direct IP (1.1.1.1 / 8.8.8.8)
                val dohAddresses = resolveViaDoh(host)
                if (dohAddresses.isNotEmpty()) {
                    putCached(host, dohAddresses)
                    return@synchronized dohAddresses
                }

                // Last fallback to system DNS or throw
                try {
                    Dns.SYSTEM.lookup(host)
                } catch (_: Exception) {
                    throw UnknownHostException("Unable to resolve host \"$host\": No address associated with hostname")
                }
            }
        } finally {
            inFlightLocks.remove(host, lock)
        }
    }

    private fun getCached(host: String): List<InetAddress>? = synchronized(cacheLock) {
        val entry = cache[host] ?: return@synchronized null
        if (entry.expiresAt <= System.currentTimeMillis()) {
            cache.remove(host)
            null
        } else {
            entry.addresses
        }
    }

    private fun putCached(host: String, addresses: List<InetAddress>) = synchronized(cacheLock) {
        cache[host] = CacheEntry(addresses, System.currentTimeMillis() + CACHE_TTL_MS)
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        val providers = listOf(
            "https://1.1.1.1/dns-query?name=$hostname&type=A",
            "https://8.8.8.8/resolve?name=$hostname&type=A",
        )
        for (url in providers) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .get()
                    .build()
                dohClient.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val answers = json.optJSONArray("Answer")
                        val result = mutableListOf<InetAddress>()
                        if (answers != null) {
                            for (i in 0 until answers.length()) {
                                val answer = answers.optJSONObject(i)
                                val type = answer?.optInt("type", 0) ?: 0
                                val data = answer?.optString("data", "").orEmpty()
                                if (type == 1 && data.isNotEmpty()) { // Type 1 = A record (IPv4)
                                    runCatching {
                                        result.add(InetAddress.getByName(data))
                                    }
                                }
                            }
                        }
                        if (result.isNotEmpty()) return result
                    }
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }
}
