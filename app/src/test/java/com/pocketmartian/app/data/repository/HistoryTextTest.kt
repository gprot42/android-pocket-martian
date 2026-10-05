package com.pocketmartian.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTextTest {

    /** Shaped like the answers the app asks Grok for: HTML, with a list, a table and cited links. */
    private val answer = """
        <h3>What Jev is</h3>
        <p><strong>Jev</strong> is TypeSafe&rsquo;s <em>decision model</em>: it returns a label, a route or a
        score rather than prose, and it is meant to sit inside code, not replace a writer.
        <a href="https://typesafe.ai/blog/introducing-system-one-models-and-jev">[1]</a>
        <a href="https://arize.com/blog/typesafe-jev-llm-judge/">[2]</a></p>
        <ul>
          <li><strong>Use Jev</strong> when code needs a route, a label, a policy check, or a score.</li>
          <li><strong>Use a normal LLM</strong> when the result has to be a sentence.</li>
        </ul>
        <table>
          <tr><th>Task</th><th>Jev</th><th>LLM</th></tr>
          <tr><td>Routing</td><td>Yes</td><td>Slower</td></tr>
          <tr><td>Summaries</td><td>No</td><td>Yes</td></tr>
        </table>
        <p>TypeSafe&#8217;s launch post is at <a href="https://typesafe.ai">typesafe.ai</a> &amp; costs &pound;0.</p>
    """.trimIndent()

    @Test
    fun `an answer keeps its words and loses its markup`() {
        val text = htmlToHistoryText(answer)
        assertFalse(text, text.contains('<'))
        assertFalse(text, Regex("&[a-z#0-9]+;").containsMatchIn(text))
        assertTrue(text, text.contains("Jev is TypeSafe’s decision model"))
        assertTrue(text, text.contains("- Use Jev when code needs a route"))
        assertTrue(text, text.contains("Task | Jev | LLM"))
        assertTrue(text, text.contains("Routing | Yes | Slower"))
        // A link's address stays when its text does not already say it.
        assertTrue(text, text.contains("[1] (https://typesafe.ai/blog/introducing-system-one-models-and-jev)"))
        assertTrue(text, text.contains("launch post is at typesafe.ai & costs £0."))
    }

    @Test
    fun `and is much shorter to send`() {
        val before = answer.length
        val after = htmlToHistoryText(answer).length
        println("HISTORY answer: $before chars as HTML, $after as text (${100 - after * 100 / before}% smaller)")
        assertTrue("only ${100 - after * 100 / before}% smaller", after < before * 0.8)
    }

    @Test
    fun `plain text and typed prompts pass through untouched`() {
        assertEquals("What is 3 < 4?", htmlToHistoryText("What is 3 < 4?"))
        assertEquals("Plain answer.\nSecond line.", htmlToHistoryText("Plain answer.\nSecond line."))
    }

    @Test
    fun `lists and table rows are one line each, and code keeps its layout`() {
        val html = "<p>Steps:</p>\n<ol>\n  <li>One</li>\n  <li>Two</li>\n</ol>\n" +
            "<pre><code>fun main() {\n    println(1 &lt; 2)\n}</code></pre>"
        assertEquals("Steps:\n\n- One\n- Two\n\nfun main() {\n    println(1 < 2)\n}", htmlToHistoryText(html))
    }

    @Test
    fun `scripts and styles are never words`() {
        assertEquals("Hello", htmlToHistoryText("<style>p{color:red}</style><p>Hello</p><script>x()</script>"))
    }
}
