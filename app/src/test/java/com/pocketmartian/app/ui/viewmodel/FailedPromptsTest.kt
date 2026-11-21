package com.pocketmartian.app.ui.viewmodel

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailedPromptsTest {

    private fun user(text: String) = ChatUiMessage(role = "user", content = text)
    private fun grok(text: String) = ChatUiMessage(role = "assistant", content = text)

    @Test
    fun `a prompt that never got an answer is not sent to Grok again`() {
        // The Aurora Store question failed; the 7-Eleven one came next and was answered.
        val messages = listOf(
            user("Can I add this to Aurora Store?"),
            user("Show me closest 7-11"),
            grok("The nearest 7-Eleven is pin 1.")
        )
        assertEquals(listOf("Show me closest 7-11", "The nearest 7-Eleven is pin 1."), trimHistory(messages).map { it.content })
    }

    @Test
    fun `a failed prompt at the end is left out too`() {
        val messages = listOf(user("q1"), grok("a1"), user("q2 that failed"))
        assertEquals(listOf("q1", "a1"), withoutUnanswered(messages).map { it.content })
    }

    @Test
    fun `retry replaces the failed prompt instead of adding a copy`() {
        val prompt = "Can you determine an algorithm which created these?"
        val messages = listOf(user("q1"), grok("a1"), user(prompt))
        assertEquals(listOf("q1", "a1"), withoutFailedPrompt(messages, prompt).map { it.content })
        // Answered, or a different prompt: nothing is taken off.
        assertEquals(4, withoutFailedPrompt(messages + grok("a2"), prompt).size)
        assertEquals(3, withoutFailedPrompt(messages, "something else").size)
    }

    @Test
    fun `a failed prompt with pictures stays, since retry would not resend them`() {
        val withPicture = ChatUiMessage(role = "user", content = "what is this?", imageUris = listOf(Uri.EMPTY))
        assertEquals(1, withoutFailedPrompt(listOf(withPicture), "what is this?").size)
    }

    @Test
    fun `an answer cut off by a dropped connection says so`() {
        val md = incompleteAnswer("The first half", "markdown")
        assertTrue(md, md.startsWith("The first half"))
        assertTrue(md, md.contains("this answer is incomplete"))
        val html = incompleteAnswer("The first half", "html")
        assertTrue(html, html.endsWith("<p><em>The connection dropped here, so this answer is incomplete.</em></p>"))
    }
}
