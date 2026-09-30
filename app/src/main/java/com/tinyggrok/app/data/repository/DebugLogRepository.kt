package com.tinyggrok.app.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Cap the in-memory ring so debug logs cannot grow into gigabytes of RSS. */
internal const val MAX_DEBUG_LOG_ENTRIES = 200

private const val DAY_MS = 24L * 60 * 60 * 1000

data class DebugLogEntry(
    val id: Long,
    val timestamp: String,
    val timestampMillis: Long,
    val direction: String,
    val summary: String,
    val body: String
)

@Singleton
class DebugLogRepository @Inject constructor() {
    private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
    val logs: StateFlow<List<DebugLogEntry>> = _logs.asStateFlow()

    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val olderFormatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val nextId = AtomicLong(0)

    fun logOutgoing(summary: String, body: String) {
        append("→ OUT", summary, body)
    }

    fun logIncoming(summary: String, body: String) {
        append("← IN", summary, body)
    }

    /** How long a prompt took; see RequestTimingLog. Always written, Debug mode or not. */
    fun logTiming(summary: String, atMillis: Long = System.currentTimeMillis()) {
        append("⏱ TIME", summary, "", atMillis)
    }

    /** Voice-specific log entry — always written regardless of chat debug mode. */
    fun logVoice(direction: String, summary: String, body: String) {
        append(direction, summary, body)
    }

    fun clear() {
        _logs.value = emptyList()
    }

    private fun append(
        direction: String,
        summary: String,
        body: String,
        now: Long = System.currentTimeMillis()
    ) {
        val entry = DebugLogEntry(
            id = nextId.incrementAndGet(),
            timestamp = if (now / DAY_MS == System.currentTimeMillis() / DAY_MS) {
                formatter.format(Date(now))
            } else {
                olderFormatter.format(Date(now))
            },
            timestampMillis = now,
            direction = direction,
            summary = summary,
            body = sanitizeLogBody(body)
        )
        synchronized(this) {
            // Kept in time order: timings from earlier runs are added with their own times.
            val current = _logs.value
            val merged = if (current.isEmpty() || current.last().timestampMillis <= now) {
                current + entry
            } else {
                (current + entry).sortedBy { it.timestampMillis }
            }
            _logs.value = if (merged.size > MAX_DEBUG_LOG_ENTRIES) merged.takeLast(MAX_DEBUG_LOG_ENTRIES) else merged
        }
    }
}
