package com.pocketmartian.app.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_HISTORY = 10
private const val HISTORY_FILE = "response_history.json"

data class ResponseHistoryEntry(
    val timestamp: String,
    val prompt: String,
    val response: String
)

@Singleton
class ResponseHistoryRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val historyFile: File = File(context.filesDir, HISTORY_FILE)
    private val listType = object : TypeToken<List<ResponseHistoryEntry>>() {}.type

    private val _entries = MutableStateFlow<List<ResponseHistoryEntry>>(loadFromDisk())
    val entries: StateFlow<List<ResponseHistoryEntry>> = _entries.asStateFlow()

    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /**
     * Writes happen off the main thread, where they used to run as each answer landed,
     * and one at a time, so they reach the file in the order they were made.
     */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun loadFromDisk(): List<ResponseHistoryEntry> {
        return try {
            if (historyFile.exists()) {
                gson.fromJson(historyFile.readText(), listType) ?: emptyList()
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveToDisk(entries: List<ResponseHistoryEntry>) {
        try {
            historyFile.writeText(gson.toJson(entries))
        } catch (_: Exception) { /* best-effort */ }
    }

    fun add(prompt: String, response: String) {
        val entry = ResponseHistoryEntry(
            timestamp = formatter.format(Date()),
            prompt = prompt,
            response = response
        )
        val updated = (_entries.value + entry).takeLast(MAX_HISTORY)
        _entries.value = updated
        writer.execute { saveToDisk(updated) }
    }

    fun clear() {
        _entries.value = emptyList()
        writer.execute { saveToDisk(emptyList()) }
    }
}
