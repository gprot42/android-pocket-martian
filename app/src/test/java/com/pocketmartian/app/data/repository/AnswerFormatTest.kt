package com.pocketmartian.app.data.repository

import com.pocketmartian.app.data.model.OutputContent
import com.pocketmartian.app.data.model.OutputItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers come back as HTML or, despite being asked for HTML, as Markdown. */
class AnswerFormatTest {

    private val markdownAnswer = """
        About €6 a month.

        | What you run | Monthly cap |
        | --- | --- |
        | CX23 | €5.49 |

        A few things change the bill:
        - Billed by the hour.
        - Deleting the server stops the charge.
    """.trimIndent()

    @Test
    fun aMarkdownAnswerBecomesATableAndAList() {
        val html = answerHtml(markdownAnswer)
        assertTrue(html, "<table>" in html && "<td>CX23</td>" in html)
        assertTrue(html, "<ul>" in html && "<li>Billed by the hour.</li>" in html)
        assertFalse("no raw table rows left", "| --- |" in html)
    }

    @Test
    fun anHtmlAnswerIsKeptAsHtml() {
        val html = answerHtml("<p>Hello <b>there</b></p>\n<ul><li>One</li></ul>")
        assertTrue(html, html.startsWith("<p>Hello"))
        assertFalse("not wrapped again", html.contains("&lt;p&gt;"))
    }

    @Test
    fun footnotesBecomeLinksInEitherForm() {
        val md = answerHtml("IPv6 is free. [[1]](https://www.hetzner.com/cloud)")
        assertTrue(md, "<sup><a href=\"https://www.hetzner.com/cloud\">[1]</a></sup>" in md)
        val html = answerHtml("<p>IPv6 is free. [[1]](https://www.hetzner.com/cloud)</p>")
        assertTrue(html, "<sup><a href=\"https://www.hetzner.com/cloud\">[1]</a></sup>" in html)
    }

    @Test
    fun inlineTagsAloneDoNotMakeAnswerHtml() {
        // A Markdown answer with a footnote link in it is still Markdown.
        assertFalse(looksLikeHtml("Free. <sup><a href=\"x\">[1]</a></sup>\n\n| a | b |\n| --- | --- |"))
        assertTrue(looksLikeHtml("<p>Free.</p>"))
        assertTrue(looksLikeHtml("<TABLE><tr><td>x</td></tr></TABLE>"))
    }

    @Test
    fun aStoredMarkdownAnswerIsRenderedWhenShown() {
        assertTrue("<table>" in storedAnswerHtml(markdownAnswer))
        val stored = "<p>Already HTML</p>"
        assertEquals(stored, storedAnswerHtml(stored))
    }

    @Test
    fun separateMessagesBecomeSeparateParagraphs() {
        fun message(text: String) = OutputItem(type = "message", content = listOf(OutputContent(type = "output_text", text = text)))
        val joined = joinMessageTexts(
            listOf(
                message("Checking Hetzner's current pricing."),
                OutputItem(type = "web_search_call"),
                message("Third-party prices disagree."),
                message("About €6 a month.")
            )
        )
        assertEquals(
            "Checking Hetzner's current pricing.\n\nThird-party prices disagree.\n\nAbout €6 a month.",
            joined
        )
    }
}
