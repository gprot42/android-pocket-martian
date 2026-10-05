package com.pocketmartian.app.data.repository

import java.util.concurrent.ConcurrentHashMap

/**
 * Where the time went on one prompt, from tapping Send to the answer being complete.
 *
 * Added after "prompt results very slow" could not be diagnosed: the phone kept about a
 * minute of system log and the app wrote no timings, so nothing said whether the wait
 * was on the phone (waiting for GPS, looking up a place name), on the network, or at xAI
 * (reasoning, web searches, writing). Each prompt now produces one line with each
 * milestone as time since Send, plus what the request asked for and what it cost.
 * Numbers only: never the prompt or the answer. See [RequestTimingLog] for where the
 * line goes.
 *
 * Milestones are recorded once, the first time they happen, from whichever thread sees
 * them (the reply streams in on a network thread).
 */
class SendTiming(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val start = nowMs()
    private val marks = ConcurrentHashMap<Mark, Long>()
    private val info = ConcurrentHashMap<String, String>()

    @Volatile var searches = 0
        private set
    @Volatile var attempts = 0
        private set

    enum class Mark(val label: String) {
        AUTH("auth"),
        LOCATION("location"),
        REQUEST("request sent"),
        HEADERS("response headers"),
        FIRST_EVENT("first event"),
        FIRST_SEARCH("first search"),
        FIRST_TEXT("first text"),
        DONE("done")
    }

    fun mark(mark: Mark) {
        marks.putIfAbsent(mark, nowMs() - start)
    }

    fun elapsed(mark: Mark): Long? = marks[mark]

    @Volatile private var trainLookups = 0
    @Volatile private var trainMs = 0L

    /** A live train times lookup the app ran for the model, and how long it took. */
    fun trainLookup(ms: Long) {
        trainLookups++
        trainMs += ms
    }

    fun searchStarted() {
        searches++
        mark(Mark.FIRST_SEARCH)
    }

    fun attemptStarted() {
        attempts++
        mark(Mark.REQUEST)
    }

    /** Anything worth knowing about the request or result, e.g. model, effort, tokens. */
    fun note(key: String, value: Any?) {
        if (value != null) info[key] = value.toString()
    }

    /**
     * One line, e.g.
     * `grok-4.7 effort=low rail=no | request 3.1s (location 3.0s) · headers 3.4s · first text 9.8s ·
     * done 14.2s | 2 searches | in 7,812 out 1,204 reasoning 3,880 | ok`
     */
    fun summary(outcome: String): String {
        mark(Mark.DONE)
        fun s(ms: Long?) = if (ms == null) "-" else if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)
        val head = listOf("kind", "model", "effort", "turns", "rail", "stream")
            .mapNotNull { k -> info[k]?.let { if (k == "model" || k == "kind") it else "$k=$it" } }
            .joinToString(" ")
        val phases = Mark.entries
            .filter { it != Mark.AUTH && it != Mark.LOCATION }
            .mapNotNull { m -> marks[m]?.let { "${m.label} ${s(it)}" } }
            .joinToString(" · ")
        val before = listOfNotNull(
            marks[Mark.AUTH]?.let { "auth ${s(it)}" },
            marks[Mark.LOCATION]?.let { "location ${s(it)}" + (info["location"]?.let { w -> " $w" } ?: "") }
        ).joinToString(", ")
        val tokens = listOf("in", "cached", "out", "reasoning")
            .mapNotNull { k -> info["tokens.$k"]?.toIntOrNull()?.let { "$k ${"%,d".format(it)}" } }
            .joinToString(" ")
        val context = listOfNotNull(
            info["history"]?.let { "history $it" },
            info["prompt"]?.let { "prompt $it chars" },
            info["images"]?.takeIf { it != "0" }?.let { "$it images" },
            trainLookups.takeIf { it > 0 }?.let { "$it train lookups ${trainMs}ms" },
            attempts.takeIf { it > 1 }?.let { n -> "$n attempts" + (info["retried after"]?.let { " (retried after $it)" } ?: "") }
        ).joinToString(", ")
        return listOf(
            head,
            phases + (if (before.isNotEmpty()) " ($before)" else ""),
            if (info["kind"] == null) "$searches searches" else "",
            tokens,
            context,
            outcome
        ).filter { it.isNotBlank() }.joinToString(" | ")
    }
}
