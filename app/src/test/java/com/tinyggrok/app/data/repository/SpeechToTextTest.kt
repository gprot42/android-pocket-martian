package com.tinyggrok.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SpeechToTextTest {

    @Test
    fun `the words come out of xAI's response`() {
        // The example response from xAI's speech-to-text documentation.
        val json = """
            {"text": "The balance is ${'$'}167,983.15.", "language": "en", "duration": 3.45,
             "words": [{"text": "The", "start": 0.24, "end": 0.48}]}
        """.trimIndent()
        val t = parseTranscript(json)!!
        assertEquals("The balance is \$167,983.15.", t.text)
        assertEquals("en", t.language)
        assertEquals(3.45, t.audioSeconds, 1e-9)
    }

    @Test
    fun `silence or nonsense gives no transcript`() {
        assertNull(parseTranscript("""{"text": "   ", "duration": 1.0}"""))
        assertNull(parseTranscript("""{"error": "bad"}"""))
        assertNull(parseTranscript("not json"))
    }

    @Test
    fun `the recording is a valid 16 kHz mono WAV`() {
        val pcm = ByteArray(32_000) { (it % 7).toByte() } // one second
        val wav = wavFromPcm16(pcm, 16_000)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(36 + pcm.size, b.getInt(4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(1, b.getShort(20).toInt())      // PCM
        assertEquals(1, b.getShort(22).toInt())      // mono
        assertEquals(16_000, b.getInt(24))           // sample rate
        assertEquals(32_000, b.getInt(28))           // bytes per second
        assertEquals(16, b.getShort(34).toInt())     // bits per sample
        assertEquals("data", String(wav, 36, 4))
        assertEquals(pcm.size, b.getInt(40))
        assertEquals(44 + pcm.size, wav.size)
    }

    @Test
    fun `dictated words join what is already typed`() {
        assertEquals("What is the weather", appendDictation("", " What is the weather "))
        assertEquals("Hello there", appendDictation("Hello", "there"))
        assertEquals("Hello there", appendDictation("Hello ", "there"))
        assertEquals("Hello", appendDictation("Hello", "   "))
    }

    @Test
    fun `a dictation timing line says what it was`() {
        var now = 0L
        val t = SendTiming { now }
        t.note("kind", "speech-to-text"); t.note("model", "grok-voice-transcribe-2.0")
        now = 3; t.mark(SendTiming.Mark.AUTH)
        now = 10; t.attemptStarted()
        now = 850; t.mark(SendTiming.Mark.HEADERS)
        now = 860
        assertEquals(
            "speech-to-text grok-voice-transcribe-2.0 | request sent 10ms · response headers 850ms · done 860ms (auth 3ms) | " +
                "ok, 9 words from 4.2 s of audio",
            t.summary("ok, 9 words from 4.2 s of audio")
        )
    }
}
