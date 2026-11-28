package com.pocketmartian.app.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSubjectTest {

    @Test
    fun `the question becomes the subject`() {
        assertEquals("Grok: Show me closest 7-11", shareSubject("Show me closest 7-11"))
    }

    @Test
    fun `a question over several lines is put on one`() {
        assertEquals("Grok: Translate this: good morning", shareSubject("Translate this:\n\n  good   morning\n"))
    }

    @Test
    fun `a long question is cut at a word`() {
        val subject = shareSubject(
            "Can you determine an algorithm which created these numbers, and explain each step of it in detail please"
        )
        assertTrue(subject, subject.endsWith("\u2026"))
        assertTrue(subject, subject.length <= "Grok: ".length + 73)
        assertTrue(subject, subject.removeSuffix("\u2026").endsWith("numbers, and explain"))
    }

    @Test
    fun `no question, or only pictures, gives the fallback`() {
        assertEquals("Answer from Grok", shareSubject(null))
        assertEquals("Answer from Grok", shareSubject("  "))
        assertEquals("Answer from Grok", shareSubject("[Image]"))
        assertEquals("Conversation with Grok", shareSubject("[3 images]", "Conversation with Grok"))
    }
}
