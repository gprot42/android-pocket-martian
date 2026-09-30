package com.tinyggrok.app.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class ResponsesSseParserTest {

    private val parser = ResponsesSseParser()

    @Test
    fun accumulatesDeltasAndCompletedPayload() {
        val sse = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"Hello"}

            : keepalive

            data: {"type":"response.output_text.delta","delta":" world"}

            data: {"type":"response.completed","response":{"output_text":"Hello world","usage":{"input_tokens":3,"output_tokens":2,"total_tokens":5}}}

            data: [DONE]

        """.trimIndent()
        val parsed = parser.parse(StringReader(sse))
        assertEquals("Hello world", parsed.completed?.outputText)
        assertEquals("Hello world", parsed.accumulatedText)
        assertEquals(5, parsed.completed?.usage?.totalTokens)
        assertNull(parsed.errorMessage)
    }

    @Test
    fun listenerReceivesSearchAndTextAsTheyArrive() {
        val deltas = mutableListOf<String>()
        var searches = 0
        val listener = object : ResponsesStreamListener {
            override fun onSearchStarted() { searches++ }
            override fun onDelta(text: String) { deltas += text }
        }
        val sse = """
            data: {"type":"response.web_search_call.in_progress"}

            data: {"type":"response.web_search_call.completed"}

            data: {"type":"response.output_text.delta","delta":"Trains "}

            data: {"type":"response.output_text.delta","delta":"run hourly."}

            data: [DONE]

        """.trimIndent()

        val parsed = ResponsesSseParser(listener = listener).parse(StringReader(sse))

        assertEquals(listOf("Trains ", "run hourly."), deltas)
        assertEquals(2, searches)
        assertTrue(parsed.usedWebSearch)
        assertEquals("Trains run hourly.", parsed.accumulatedText)
    }

    @Test
    fun listenerIsOptional() {
        val sse = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hi\"}\n\n"
        assertEquals("hi", ResponsesSseParser().parse(StringReader(sse)).accumulatedText)
    }

    @Test
    fun failedEventSurfacesMessage() {
        val sse = """
            data: {"type":"response.failed","response":{"error":{"message":"tool exploded"}}}

        """.trimIndent()
        val parsed = parser.parse(StringReader(sse))
        assertEquals("tool exploded", parsed.errorMessage)
    }

    @Test
    fun webSearchAndCitationEvents() {
        val sse = """
            data: {"type":"response.web_search_call.in_progress"}

            data: {"type":"response.output_text.annotation.added","annotation":{"url":"https://www.nationalrail.co.uk"}}

            data: {"type":"response.output_text.delta","delta":"Next train is 12:04"}

        """.trimIndent()
        val parsed = parser.parse(StringReader(sse))
        assertTrue(parsed.usedWebSearch)
        assertEquals(listOf("https://www.nationalrail.co.uk"), parsed.citations)
        assertEquals("Next train is 12:04", parsed.accumulatedText)
    }

    @Test
    fun chatCompletionChunkFallback() {
        val sse = """
            data: {"id":"x","object":"chat.completion.chunk","choices":[{"delta":{"content":"Ah"}}]}

            data: {"choices":[{"delta":{"content":"oy"}}]}

            data: [DONE]
        """.trimIndent()
        val parsed = parser.parse(StringReader(sse))
        assertEquals("Ahoy", parsed.accumulatedText)
        assertFalse(parsed.usedWebSearch)
    }

    @Test
    fun ignoresMalformedAndEmptyEvents() {
        val sse = """
            data: not-json

            data:

            data: {"type":"response.output_text.delta","delta":"ok"}

        """.trimIndent()
        val parsed = parser.parse(StringReader(sse))
        assertEquals("ok", parsed.accumulatedText)
        assertNull(parsed.errorMessage)
    }

    @Test
    fun completedOutputWebSearchFlag() {
        val sse = """
            data: {"type":"response.completed","response":{"output":[{"type":"web_search_call"},{"type":"message","content":[{"type":"output_text","text":"done"}]}]}}

        """.trimIndent()
        val parsed = parser.parse(StringReader(sse))
        assertTrue(parsed.usedWebSearch)
        assertEquals("done", parsed.completed?.output?.last()?.content?.first()?.text)
    }

    @Test
    fun searchesAreCountedOnceEachAndEveryEventIsReported() {
        // One web search arrives as several events; the timing log must count it once.
        val sse = """
            data: {"type":"response.created"}

            data: {"type":"response.output_item.added","item":{"type":"web_search_call","id":"ws_1"}}

            data: {"type":"response.web_search_call.in_progress","item_id":"ws_1"}

            data: {"type":"response.web_search_call.completed","item_id":"ws_1"}

            data: {"type":"response.output_item.done","item":{"type":"web_search_call","id":"ws_1"}}

            data: {"type":"response.output_item.added","item":{"type":"web_search_call","id":"ws_2"}}

            data: {"type":"response.output_text.delta","delta":"Hi"}

            data: {"type":"response.completed","response":{"output_text":"Hi","usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12,"input_tokens_details":{"cached_tokens":4},"output_tokens_details":{"reasoning_tokens":40}}}}

        """.trimIndent()
        var calls = 0
        val types = mutableListOf<String>()
        val listener = object : ResponsesStreamListener {
            override fun onSearchCall() { calls++ }
            override fun onEvent(type: String) { types += type }
        }
        val parsed = ResponsesSseParser(listener = listener).parse(StringReader(sse))
        assertEquals(2, calls)
        assertEquals("response.created", types.first())
        assertEquals(8, types.size)
        // Cached and reasoning tokens come through for the timing line.
        assertEquals(4, parsed.completed?.usage?.inputDetails?.cachedTokens)
        assertEquals(40, parsed.completed?.usage?.outputDetails?.reasoningTokens)
    }
}
