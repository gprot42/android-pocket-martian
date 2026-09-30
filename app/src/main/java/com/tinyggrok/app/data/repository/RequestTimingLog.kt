package com.tinyggrok.app.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the timing line of every prompt (see [SendTiming]) where it can be read later.
 *
 * The system log alone is not enough: on the reporting phone it held about a minute of
 * history, so by the time anyone looked, the slow request had long gone. Each line goes
 * to three places:
 *  - the system log, tag `RequestTiming`, for watching live over USB;
 *  - the in-app Logs screen, whether or not Debug mode is on (Copy All sends it on);
 *  - a small file in the app's private storage, the last [KEEP_LINES] prompts, loaded
 *    back into the Logs screen when the app starts, so a slow request from yesterday is
 *    still there today, even after a crash.
 */
@Singleton
class RequestTimingLog @Inject constructor(
    @ApplicationContext context: Context,
    private val debugLog: DebugLogRepository
) {
    private val file = File(context.filesDir, FILE_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    init {
        // Earlier runs' timings, back on the Logs screen at the times they happened.
        scope.launch {
            recent().forEach { line ->
                val at = runCatching { stamp.parse(line.take(19))?.time }.getOrNull() ?: return@forEach
                debugLog.logTiming(line.drop(20), atMillis = at)
            }
        }
    }

    fun record(line: String) {
        val now = System.currentTimeMillis()
        Log.i(TAG, line)
        debugLog.logTiming(line, atMillis = now)
        val stamped = "${stamp.format(Date(now))} $line"
        scope.launch {
            synchronized(lock) {
                runCatching {
                    file.appendText(stamped + "\n")
                    // Trim now and then rather than on every write.
                    val lines = file.readLines()
                    if (lines.size > KEEP_LINES + TRIM_SLACK) file.writeText(lines.takeLast(KEEP_LINES).joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    /** The kept lines, oldest first, each starting with its date and time. */
    fun recent(): List<String> = synchronized(lock) {
        runCatching { if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList() }
            .getOrDefault(emptyList())
            .takeLast(KEEP_LINES)
    }

    fun clear() {
        scope.launch { synchronized(lock) { runCatching { file.delete() } } }
    }

    private companion object {
        const val TAG = "RequestTiming"
        const val FILE_NAME = "request_timings.log"
        const val KEEP_LINES = 300
        const val TRIM_SLACK = 50
    }
}
