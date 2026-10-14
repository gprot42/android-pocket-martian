package com.pocketmartian.app.data.repository

import com.pocketmartian.app.data.model.OutputItem
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * The answer as HTML for the chat, whichever way Grok wrote it.
 *
 * The app asks for HTML, and Grok usually complies, but not always: an answer after a web
 * search can come back in Markdown, with `| --- |` tables and `-` bullets, which shown as
 * HTML loses its line breaks and leaves the table as rows of bars. So text with no HTML
 * blocks in it is rendered as Markdown (tables included), and HTML is kept as it is,
 * with the light Markdown clean-up it has always had.
 */
internal fun answerHtml(text: String): String =
    if (looksLikeHtml(text)) cleanUpHtml(text) else markdownToHtml(footnoteLinks(text))

/** For an answer already stored: render it as Markdown only if it holds no HTML blocks. */
internal fun storedAnswerHtml(text: String): String =
    if (looksLikeHtml(text)) text else markdownToHtml(text)

/** Whether text is HTML: it has block-level tags, not just inline ones like a footnote link. */
internal fun looksLikeHtml(text: String): Boolean = HTML_BLOCK.containsMatchIn(text)

private val HTML_BLOCK = Regex(
    """<(p|ul|ol|li|table|tr|td|th|h[1-6]|br|div|pre|blockquote|hr)\b""",
    RegexOption.IGNORE_CASE
)

/** GitHub-flavoured Markdown, tables included, to HTML. */
internal fun markdownToHtml(markdown: String): String {
    val extensions = listOf(TablesExtension.create())
    val parser = Parser.builder().extensions(extensions).build()
    val renderer = HtmlRenderer.builder().extensions(extensions).build()
    return renderer.render(parser.parse(markdown))
}

/** Inline citation footnotes, `[[1]](https://...)`, as superscript links. */
internal fun footnoteLinks(text: String): String =
    text.replace(Regex("""\[\[(\d+)\]\]\(([^)]+)\)""")) { m ->
        "<sup><a href=\"${m.groupValues[2]}\">[${m.groupValues[1]}]</a></sup>"
    }

/**
 * Light Markdown inside an HTML answer:
 *  [[1]](url)  →  <sup><a href="url">[1]</a></sup>
 *  [label](url) →  <a href="url">label</a>
 *  ### Heading  →  <h3>Heading</h3>   (and ## → h2, # → h1)
 *  **text**    →  <strong>text</strong>
 *  *text*      →  <em>text</em>
 */
internal fun cleanUpHtml(text: String): String {
    var out = footnoteLinks(text)
    out = out.replace(Regex("""\[([^\]]+)\]\((https?://[^)]+)\)""")) { m ->
        "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>"
    }
    out = out.replace(Regex("""(?m)^### (.+)$""")) { m -> "<h3>${m.groupValues[1]}</h3>" }
    out = out.replace(Regex("""(?m)^## (.+)$""")) { m -> "<h2>${m.groupValues[1]}</h2>" }
    out = out.replace(Regex("""(?m)^# (.+)$""")) { m -> "<h1>${m.groupValues[1]}</h1>" }
    out = out.replace(Regex("""\*\*(.+?)\*\*""")) { m -> "<strong>${m.groupValues[1]}</strong>" }
    out = out.replace(Regex("""\*(.+?)\*""")) { m -> "<em>${m.groupValues[1]}</em>" }
    return out
}

/**
 * The text of a response's messages, each message a paragraph of its own.
 *
 * Grok often writes a line, searches, writes another, searches again, then answers; each
 * of those is a separate message. Run together, as the response's ready-joined
 * `output_text` has them, they read "…a small virtual machine.Third-party prices
 * disagree.". Parts of the same message are contiguous and are joined as they are.
 */
internal fun joinMessageTexts(output: List<OutputItem>): String =
    output
        .filter { it.type == null || it.type == "message" }
        .map { item ->
            item.content.orEmpty()
                .filter { it.type == null || it.type == "output_text" }
                .mapNotNull { it.text }
                .joinToString("")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
