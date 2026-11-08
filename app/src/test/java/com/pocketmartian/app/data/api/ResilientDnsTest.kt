package com.pocketmartian.app.data.api

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class ResilientDnsTest {

    private val v4 = InetAddress.getByAddress("api.x.ai", byteArrayOf(104, 18, 0, 1))
    private val v6 = InetAddress.getByAddress(
        "api.x.ai",
        byteArrayOf(0x26, 0x06, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
    )

    private class FixedDns(private val result: () -> List<InetAddress>) : Dns {
        var calls = 0
        override fun lookup(hostname: String): List<InetAddress> {
            calls++
            return result()
        }
    }

    private fun failing() = FixedDns { throw UnknownHostException("nope") }

    @Test
    fun systemResolverWinsWhenItWorks() {
        val system = FixedDns { listOf(v6, v4) }
        val doh = failing()
        val dns = ResilientDns(fallbacks = listOf(doh), system = system)

        val result = dns.lookup("api.x.ai")

        assertEquals(listOf(v4, v6), result) // IPv4 first
        assertEquals(0, doh.calls)
    }

    @Test
    fun fallsBackToDohWhenSystemDnsFails() {
        val doh1 = failing()
        val doh2 = FixedDns { listOf(v4) }
        val dns = ResilientDns(fallbacks = listOf(doh1, doh2), system = failing())

        assertEquals(listOf(v4), dns.lookup("api.x.ai"))
        assertEquals(1, doh1.calls)
        assertEquals(1, doh2.calls)
    }

    @Test
    fun usesLastKnownGoodWhenEveryResolverFails() {
        var systemWorks = true
        val system = FixedDns { if (systemWorks) listOf(v4) else throw UnknownHostException("x") }
        val dns = ResilientDns(fallbacks = listOf(failing()), system = system)

        assertEquals(listOf(v4), dns.lookup("api.x.ai"))
        systemWorks = false
        assertEquals(listOf(v4), dns.lookup("api.x.ai"))
    }

    @Test
    fun throwsUnknownHostWhenNothingEverResolved() {
        val dns = ResilientDns(fallbacks = listOf(failing()), system = failing())
        try {
            dns.lookup("api.x.ai")
            fail("expected UnknownHostException")
        } catch (e: UnknownHostException) {
            assertTrue(e.message.orEmpty().contains("api.x.ai"))
            assertTrue(e.cause is UnknownHostException)
        }
    }

    /** Answers after [delayMs], as a slow or hanging resolver does. */
    private fun slow(delayMs: Long, result: () -> List<InetAddress>) = FixedDns {
        Thread.sleep(delayMs)
        result()
    }

    @Test
    fun aSlowSystemResolverOnlyCostsItsHeadStart() {
        // The phone's resolver hangs and the first fallback times out, as on the reporting
        // phone, where one map tile waited 22 s for this chain tried one after another.
        val system = slow(8_000) { throw UnknownHostException("timed out") }
        val deadDoh = slow(8_000) { throw UnknownHostException("timed out") }
        val workingDoh = slow(200) { listOf(v4) }
        val dns = ResilientDns(fallbacks = listOf(deadDoh, workingDoh), system = system, headStartMs = 300)

        val started = System.currentTimeMillis()
        assertEquals(listOf(v4), dns.lookup("tile.openstreetmap.org"))
        val took = System.currentTimeMillis() - started
        assertTrue("took $took ms", took < 1_500)
    }

    @Test
    fun aHostTheSystemCouldNotResolveReusesTheFallbackAnswer() {
        val system = failing()
        val doh = FixedDns { listOf(v4) }
        var now = 0L
        val dns = ResilientDns(fallbacks = listOf(doh), system = system, fallbackReuseMs = 60_000, clock = { now })

        dns.lookup("tile.openstreetmap.org")
        now += 30_000
        dns.lookup("tile.openstreetmap.org")
        assertEquals(1, system.calls)
        assertEquals(1, doh.calls)

        // After a while the system resolver gets another chance.
        now += 60_000
        dns.lookup("tile.openstreetmap.org")
        assertEquals(2, system.calls)
    }

    @Test
    fun lookupsOfOneHostAtOnceShareOneResolution() {
        val system = slow(300) { listOf(v4) }
        val dns = ResilientDns(fallbacks = emptyList(), system = system)
        val threads = (1..6).map { Thread { dns.lookup("tile.openstreetmap.org") } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, system.calls)
    }

    @Test
    fun aHungResolverCannotHoldALookupPastTheDeadline() {
        val dns = ResilientDns(
            fallbacks = listOf(slow(5_000) { listOf(v4) }),
            system = slow(5_000) { listOf(v4) },
            headStartMs = 100,
            deadlineMs = 500
        )
        val started = System.currentTimeMillis()
        try {
            dns.lookup("api.x.ai")
            fail("expected UnknownHostException")
        } catch (_: UnknownHostException) {
        }
        assertTrue(System.currentTimeMillis() - started < 1_500)
    }

    @Test
    fun preferIpv4IsStable() {
        val v4b = InetAddress.getByAddress("api.x.ai", byteArrayOf(104, 18, 0, 2))
        assertEquals(listOf(v4, v4b, v6), ResilientDns.preferIpv4(listOf(v6, v4, v4b)))
    }
}
