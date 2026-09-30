package com.tinyggrok.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendTimingTest {

    private var now = 0L
    private fun timing() = SendTiming { now }

    @Test
    fun `each milestone is recorded once, as time since Send`() {
        val t = timing()
        now = 3_004; t.mark(SendTiming.Mark.LOCATION)
        now = 3_120; t.attemptStarted()
        now = 3_530; t.mark(SendTiming.Mark.HEADERS)
        now = 5_000; t.searchStarted()
        now = 6_000; t.searchStarted()
        now = 9_800; t.mark(SendTiming.Mark.FIRST_TEXT)
        now = 9_900; t.mark(SendTiming.Mark.FIRST_TEXT) // later deltas do not move it
        assertEquals(9_800L, t.elapsed(SendTiming.Mark.FIRST_TEXT))
        assertEquals(5_000L, t.elapsed(SendTiming.Mark.FIRST_SEARCH))
        assertEquals(2, t.searches)
    }

    @Test
    fun `the summary reads left to right in the order things happened`() {
        val t = timing()
        t.note("model", "grok-4.7"); t.note("effort", "low"); t.note("rail", "no")
        now = 12; t.mark(SendTiming.Mark.AUTH)
        now = 3_004; t.mark(SendTiming.Mark.LOCATION); t.note("location", "waited for GPS")
        now = 3_120; t.attemptStarted()
        now = 3_530; t.mark(SendTiming.Mark.HEADERS)
        now = 9_800; t.mark(SendTiming.Mark.FIRST_TEXT)
        t.note("tokens.in", 7812); t.note("tokens.out", 1204); t.note("tokens.reasoning", 3880)
        t.note("history", "8 msgs 23,410 chars")
        now = 14_200
        val line = t.summary("ok")
        assertEquals(
            "grok-4.7 effort=low rail=no | request sent 3.1s · response headers 3.5s · first text 9.8s · done 14.2s " +
                "(auth 12ms, location 3.0s waited for GPS) | 0 searches | in 7,812 out 1,204 reasoning 3,880 | " +
                "history 8 msgs 23,410 chars | ok",
            line
        )
    }

    @Test
    fun `a failed or stopped send still says how far it got`() {
        val t = timing()
        now = 400; t.attemptStarted()
        now = 900; t.attemptStarted()
        now = 30_000
        val line = t.summary("error: HTTP 503")
        assertTrue(line, line.contains("request sent 400ms"))
        assertTrue(line, line.contains("2 attempts"))
        assertTrue(line, line.endsWith("error: HTTP 503"))
        assertFalse(line, line.contains("first text"))
    }
}
