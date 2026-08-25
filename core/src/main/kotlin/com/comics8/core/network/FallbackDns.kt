package com.comics8.core.network

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object FallbackDns : Dns {
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()
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
        val host = hostname.trim()
        if (host.isBlank()) throw UnknownHostException("hostname is blank")

        // 1. Fast cache check
        cache[host]?.let { return it }

        // 2. Deduplicate concurrent lookups for the same host
        val lock = inFlightLocks.computeIfAbsent(host) { Any() }
        val resolved = synchronized(lock) {
            cache[host]?.let { return@synchronized it }

            // Try system DNS first
            try {
                val addresses = Dns.SYSTEM.lookup(host)
                val hasIpv4 = addresses.any { it is Inet4Address }
                if (hasIpv4) {
                    cache[host] = addresses
                    return@synchronized addresses
                }
            } catch (_: Exception) {
            }

            // Fallback to Cloudflare / Google DoH by direct IP (1.1.1.1 / 8.8.8.8)
            val dohAddresses = resolveViaDoh(host)
            if (dohAddresses.isNotEmpty()) {
                cache[host] = dohAddresses
                return@synchronized dohAddresses
            }

            // Last fallback to system DNS or throw
            try {
                Dns.SYSTEM.lookup(host)
            } catch (e: Exception) {
                throw UnknownHostException("Unable to resolve host \"$host\": No address associated with hostname")
            }
        }
        inFlightLocks.remove(host, lock)
        return resolved
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
                val response = dohClient.newCall(req).execute()
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
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }
}
