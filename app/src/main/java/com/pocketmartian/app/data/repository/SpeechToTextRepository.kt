package com.pocketmartian.app.data.repository

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What Grok heard, and how long the recording was. */
data class Transcript(val text: String, val language: String?, val audioSeconds: Double)

/**
 * Dictation for the prompt: record from the microphone, send it to xAI's speech-to-text,
 * get words back.
 *
 * Android's own speech recogniser was not an option. It is provided by an installed
 * recognition service, normally Google's, and the phone this was built for has none: no
 * recognition service, nothing that answers the "recognise speech" request. Grok's
 * transcription (POST /v1/stt, grok-voice-transcribe-2.0) needs nothing on the phone but
 * the microphone, uses the key chat already uses, and understands whatever language is
 * spoken, since the language is left for it to detect.
 *
 * Recording is kept in memory as 16 kHz mono 16-bit PCM, which is what speech needs and
 * 32 KB a second: a two-minute ramble is under 4 MB. It is sent as a WAV file and not
 * kept anywhere afterwards.
 */
@Singleton
class SpeechToTextRepository @Inject constructor(
    okHttpClient: OkHttpClient
) {
    // Transcription is quick; do not inherit chat's half-hour allowance.
    private val http = okHttpClient.newBuilder().callTimeout(90, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var recorder: AudioRecord? = null
    private var job: Job? = null
    private var pcm = ByteArrayOutputStream()
    @Volatile private var recording = false

    val isRecording: Boolean get() = recording

    /** Start listening. False if the microphone could not be opened. */
    @SuppressLint("MissingPermission") // the caller asks for RECORD_AUDIO first
    fun start(): Boolean {
        if (recording) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, CHUNK_BYTES * 4)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Microphone unavailable: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        pcm = ByteArrayOutputStream()
        recorder = rec
        recording = true
        rec.startRecording()
        job = scope.launch {
            val buf = ByteArray(CHUNK_BYTES)
            while (isActive && recording) {
                val read = rec.read(buf, 0, buf.size)
                if (read > 0) synchronized(this@SpeechToTextRepository) { pcm.write(buf, 0, read) }
                if (pcm.size() >= MAX_SECONDS * SAMPLE_RATE * 2) recording = false
            }
            runCatching { rec.stop() }
            rec.release()
        }
        return true
    }

    /** Seconds recorded so far. */
    fun recordedSeconds(): Double = synchronized(this) { pcm.size() / (SAMPLE_RATE * 2.0) }

    /** Whether it stopped itself at the length limit. */
    val reachedLimit: Boolean get() = !recording && recordedSeconds() >= MAX_SECONDS - 0.1

    /** Stop listening and hand back the recording as a WAV file, or null if nothing was heard. */
    suspend fun stop(): ByteArray? {
        recording = false
        job?.join()
        job = null
        recorder = null
        val audio = synchronized(this) { pcm.toByteArray() }
        pcm = ByteArrayOutputStream()
        return if (audio.size < MIN_BYTES) null else wavFromPcm16(audio, SAMPLE_RATE)
    }

    /** Stop and throw the recording away. */
    fun cancel() {
        recording = false
        job?.cancel()
        job = null
        recorder = null
        synchronized(this) { pcm = ByteArrayOutputStream() }
    }

    /** Send a recording to xAI and return the words, timing it for the request log. */
    suspend fun transcribe(bearerToken: String, wav: ByteArray, timing: SendTiming? = null): Result<Transcript> =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", MODEL)
                // The file must be the last field.
                .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
                .build()
            val request = Request.Builder()
                .url(STT_URL)
                .header("Authorization", "Bearer $bearerToken")
                .post(body)
                .build()
            try {
                timing?.attemptStarted()
                http.newCall(request).execute().use { response ->
                    timing?.mark(SendTiming.Mark.HEADERS)
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            RuntimeException("Speech-to-text failed (HTTP ${response.code}): ${text.take(200)}")
                        )
                    }
                    val transcript = parseTranscript(text)
                        ?: return@withContext Result.failure(RuntimeException("Speech-to-text returned no text."))
                    Result.success(transcript)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Transcription failed: ${e.javaClass.simpleName}: ${e.message}")
                Result.failure(RuntimeException("Couldn't reach xAI speech-to-text (${e.javaClass.simpleName})."))
            }
        }

    companion object {
        private const val TAG = "SpeechToText"
        private const val STT_URL = "https://api.x.ai/v1/stt"
        const val MODEL = "grok-voice-transcribe-2.0"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 / 10 // 100 ms
        /** Under a quarter of a second is a tap, not speech. */
        private const val MIN_BYTES = SAMPLE_RATE * 2 / 4
        /** Stops by itself after two minutes, so a forgotten microphone does not run on. */
        const val MAX_SECONDS = 120
    }
}

/** The words, language and length from an /v1/stt response, or null if there are no words. */
internal fun parseTranscript(json: String): Transcript? {
    val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
    val text = obj.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
    if (text.isEmpty()) return null
    return Transcript(
        text = text,
        language = obj.get("language")?.takeIf { it.isJsonPrimitive }?.asString,
        audioSeconds = obj.get("duration")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
    )
}

/** Mono 16-bit PCM in a WAV container. */
internal fun wavFromPcm16(pcm: ByteArray, sampleRate: Int): ByteArray {
    val out = ByteArrayOutputStream(44 + pcm.size)
    fun le4(v: Int) { for (i in 0 until 4) out.write((v shr (8 * i)) and 0xFF) }
    fun le2(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
    fun ascii(s: String) = s.forEach { out.write(it.code) }
    ascii("RIFF"); le4(36 + pcm.size); ascii("WAVE")
    ascii("fmt "); le4(16); le2(1); le2(1); le4(sampleRate); le4(sampleRate * 2); le2(2); le2(16)
    ascii("data"); le4(pcm.size)
    out.write(pcm)
    return out.toByteArray()
}

/** Put dictated words at the end of whatever is already typed. */
internal fun appendDictation(current: String, spoken: String): String {
    val words = spoken.trim()
    if (words.isEmpty()) return current
    if (current.isBlank()) return words
    return if (current.last().isWhitespace()) current + words else "$current $words"
}
