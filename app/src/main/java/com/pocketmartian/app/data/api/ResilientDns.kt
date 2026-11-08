package com.pocketmartian.app.data.api

import android.util.Log
import okhttp3.Dns
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * DNS resolver that does not give up on the first failure.
 *
 * "Can't reach api.x.ai (DNS)" on Android is usually one of:
 *  - the carrier / hotel / captive Wi-Fi resolver dropping or blocking the query
 *  - Private DNS (DoT) misconfigured on the device
 *  - a VPN that hijacks port 53
 *  - a brief resolver outage right after a network hand-over
 *
 * How it resolves:
 *  1. the system resolver ([Dns.SYSTEM]) gets a [headStartMs] head start
 *  2. if it has failed or not answered by then, every DNS-over-HTTPS resolver in
 *     [fallbacks] (bootstrapped with literal IPs so they work even when port-53 DNS is
 *     dead) starts at once, and the first answer from any of them, or the system's, wins
 *  3. failing all of those, the last address set that worked for this host (stale
 *     cache), so a transient resolver blip during an active session never fails the request
 *
 * Tried one after another, a broken system resolver and one dead fallback cost 22
 * seconds before a map tile could even start: each waited for its own timeout. Raced,
 * the cost is the head start. A host the system resolver couldn't answer is then
 * answered from the fallback's result for [fallbackReuseMs], rather than paying again on
 * every request, and lookups of one host at the same time (the tiles of one map) share
 * a single resolution.
 *
 * Results are ordered IPv4 before IPv6. Mobile networks quite often advertise an
 * IPv6 route that silently blackholes traffic; OkHttp tries addresses sequentially,
 * so putting IPv6 first would cost a full connect timeout before anything works.
 */
class ResilientDns(
    private val fallbacks: List<Dns>,
    private val system: Dns = Dns.SYSTEM,
    private val headStartMs: Long = SYSTEM_HEAD_START_MS,
    private val deadlineMs: Long = LOOKUP_DEADLINE_MS,
    private val fallbackReuseMs: Long = FALLBACK_REUSE_MS,
    private val clock: () -> Long = System::currentTimeMillis
) : Dns {

    private val lastGood = ConcurrentHashMap<String, List<InetAddress>>()
    /** Hosts the system resolver failed for, with the fallback's answer and when it came. */
    private val viaFallback = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<List<InetAddress>>>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "ResilientDns").apply { isDaemon = true } }

    override fun lookup(hostname: String): List<InetAddress> {
        viaFallback[hostname]?.let { (addresses, at) ->
            if (clock() - at < fallbackReuseMs) return addresses
        }
        val mine = CompletableFuture<List<InetAddress>>()
        val shared = inFlight.putIfAbsent(hostname, mine)
        if (shared != null) {
            return try {
                shared.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
        try {
            return resolve(hostname).also { mine.complete(it) }
        } catch (e: Exception) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(hostname, mine)
        }
    }

    private sealed class Outcome {
        class Found(val addresses: List<InetAddress>, val fallback: Int?) : Outcome()
        class Failed(val error: Exception, val fallback: Int?) : Outcome()
    }

    private fun resolve(hostname: String): List<InetAddress> {
        val outcomes = LinkedBlockingQueue<Outcome>()
        fun start(dns: Dns, fallback: Int?) = pool.execute {
            outcomes.put(
                try {
                    val addresses = dns.lookup(hostname)
                    if (addresses.isNotEmpty()) Outcome.Found(addresses, fallback)
                    else Outcome.Failed(UnknownHostException("No addresses for $hostname"), fallback)
                } catch (e: Exception) {
                    Outcome.Failed(e, fallback)
                }
            )
        }

        start(system, null)
        var running = 1
        var fallbacksStarted = false
        var firstError: Exception? = null
        val deadline = clock() + deadlineMs
        while (running > 0) {
            val wait = if (fallbacksStarted) deadline - clock() else minOf(headStartMs, deadline - clock())
            val outcome = if (wait > 0) outcomes.poll(wait, TimeUnit.MILLISECONDS) else null
            when (outcome) {
                is Outcome.Found -> return found(hostname, outcome)
                is Outcome.Failed -> {
                    running--
                    if (firstError == null) firstError = outcome.error
                    if (outcome.fallback == null) Log.w(TAG, "System DNS failed for $hostname: ${outcome.error.message}")
                    else Log.w(TAG, "DoH fallback #${outcome.fallback + 1} failed for $hostname: ${outcome.error.message}")
                }
                null -> {
                    if (fallbacksStarted || clock() >= deadline) break
                    if (fallbacks.isEmpty()) continue
                    Log.w(TAG, "System DNS slow for $hostname; asking DoH too")
                }
            }
            // The system resolver failed or is slow: ask every fallback at once.
            if (!fallbacksStarted && (outcome == null || running == 0)) {
                fallbacksStarted = true
                fallbacks.forEachIndexed { index, dns -> start(dns, index) }
                running += fallbacks.size
            }
        }

        lastGood[hostname]?.let { cached ->
            Log.w(TAG, "Using last known addresses for $hostname (resolvers unavailable)")
            return cached
        }

        throw UnknownHostException(
            "Unable to resolve host \"$hostname\": system DNS and DNS-over-HTTPS both failed"
        ).apply { firstError?.let { initCause(it) } }
    }

    private fun found(hostname: String, outcome: Outcome.Found): List<InetAddress> {
        val ordered = preferIpv4(outcome.addresses)
        lastGood[hostname] = ordered
        if (outcome.fallback != null) {
            Log.i(TAG, "Resolved $hostname via DoH fallback #${outcome.fallback + 1}")
            viaFallback[hostname] = ordered to clock()
        } else {
            viaFallback.remove(hostname)
        }
        return ordered
    }

    companion object {
        private const val TAG = "ResilientDns"
        /** A working resolver answers in well under this; a broken one can take 10 s to say so. */
        const val SYSTEM_HEAD_START_MS = 1_500L
        /** The longest any lookup waits before falling back to the last known addresses. */
        const val LOOKUP_DEADLINE_MS = 10_000L
        /** How long a host the system resolver failed for is answered from the fallback's result. */
        const val FALLBACK_REUSE_MS = 10 * 60_000L

        /** Stable partition: keep resolver order within each family, IPv4 first. */
        fun preferIpv4(addresses: List<InetAddress>): List<InetAddress> =
            addresses.sortedBy { it is Inet6Address }
    }
}
