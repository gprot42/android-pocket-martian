package com.pocketmartian.app.data.repository

/**
 * A past answer as it is worth sending back to the model: its words, not its markup.
 *
 * Answers are written in HTML for the screen, and every prompt resends the recent ones
 * as context. Measured on the reporting phone: a six-character prompt went out with
 * 8,861 input tokens, almost all of it 22 earlier messages, which the saved conversation
 * now carries from one session to the next. Tags, attributes and entities are paid for
 * on every prompt and tell the model nothing it needs to follow the conversation.
 *
 * Structure the words depend on is kept as plain text: paragraphs and headings as line
 * breaks, list items as "- ", table cells separated by " | ", and a link's address after
 * its text when the text is not the address itself (so "the second link" still means
 * something).
 */
internal fun htmlToHistoryText(html: String): String {
    if ('<' !in html && '&' !in html) return html
    var t = html
    // Invisible content first: styles and scripts are never words.
    t = t.replace(Regex("(?is)<(style|script)\\b.*?</\\1>"), "")
    // Code keeps its line breaks; set it aside while the rest is reflowed.
    val code = ArrayList<String>()
    t = t.replace(Regex("(?is)<pre\\b[^>]*>(.*?)</pre>")) { m ->
        code += decodeEntities(m.groupValues[1].replace(Regex("<[^>]+>"), "")).trim('\n')
        "\n\u0000${code.lastIndex}\u0000\n"
    }
    // Elsewhere, as in HTML itself, a run of whitespace is one space.
    t = t.replace(Regex("\\s+"), " ")
    // Links: keep the address when it adds something.
    t = t.replace(Regex("(?is)<a\\b[^>]*?href\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>(.*?)</a>")) { m ->
        val url = m.groupValues[1].trim()
        val text = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
        when {
            text.isEmpty() -> url
            url.isEmpty() || url.startsWith("#") || sameAddress(text, url) -> text
            else -> "$text ($url)"
        }
    }
    t = t.replace(Regex("(?i)<br\\s*/?>"), "\n")
    // List items and table rows each start a line; cells are separated by " | ".
    t = t.replace(Regex("(?i)<li\\b[^>]*>"), "\n- ")
    t = t.replace(Regex("(?i)<tr\\b[^>]*>"), "\n")
    t = t.replace(Regex("(?i)</t[dh]>\\s*<t[dh]\\b[^>]*>"), " | ")
    // Blocks are set apart by a blank line.
    t = t.replace(Regex("(?i)</?(p|div|h[1-6]|ul|ol|table|blockquote|section|article)\\b[^>]*>"), "\n\n")
    t = t.replace(Regex("<[^>]+>"), "")
    t = decodeEntities(t)
    // Spaces at the ends of lines are left over from tags. Code is still set aside, so
    // its indentation is untouched.
    t = t.lines().joinToString("\n") { it.trim() }
    t = t.replace(Regex("\n{3,}"), "\n\n").trim()
    return t.replace(Regex("\u0000(\\d+)\u0000")) { m -> code[m.groupValues[1].toInt()] }
}

/** "typesafe.ai" and "https://www.typesafe.ai/" are the same address, said twice. */
private fun sameAddress(text: String, url: String): Boolean {
    fun bare(s: String) = s.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
    return bare(text) == bare(url)
}

private fun decodeEntities(text: String): String =
    Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(text) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED_ENTITIES[e]
        } ?: m.value
    }

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "ndash" to "\u2013", "mdash" to "\u2014", "hellip" to "\u2026", "rsquo" to "\u2019",
    "lsquo" to "\u2018", "rdquo" to "\u201D", "ldquo" to "\u201C", "pound" to "\u00A3",
    "euro" to "\u20AC", "deg" to "\u00B0", "times" to "\u00D7", "middot" to "\u00B7"
)
