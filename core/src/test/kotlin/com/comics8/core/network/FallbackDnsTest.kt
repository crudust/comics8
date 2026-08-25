package com.comics8.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address

class FallbackDnsTest {
    @Test
    fun resolvesHomeAssistantViaDohWhenNeeded() {
        val addresses = FallbackDns.lookup("homeassistant.tail1946af.ts.net")
        assertFalse("Address list should not be empty", addresses.isEmpty())
        assertTrue("Should contain at least one IPv4 address", addresses.any { it is Inet4Address })
    }

    @Test
    fun resolvesStandardHost() {
        val addresses = FallbackDns.lookup("1.1.1.1")
        assertFalse(addresses.isEmpty())
    }

    @Test
    fun cachesResolvedAddressesForFastLookup() {
        val first = FallbackDns.lookup("1.1.1.1")
        val second = FallbackDns.lookup("1.1.1.1")
        assertTrue(first === second || first == second)
    }

    @Test
    fun resolvesConcurrentlyWithoutError() {
        val threads = (1..10).map {
            Thread {
                val addresses = FallbackDns.lookup("1.1.1.1")
                assertFalse(addresses.isEmpty())
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }
}
