package com.dailynews.app.ui.periodic

/**
 * Parses the periodic digest's markdown back into structure.
 *
 * The digest body is markdown produced by `PeriodicDigestRenderer` with a completely
 * fixed shape. Previously the whole block was dropped verbatim into a single `Text` —
 * users saw literal `##`, `**`, and `[title](https://…)` (titles are Chinese in the
 * real output), links were not clickable, while the daily report one screen over was
 * fully structured cards.
 *
 * This deliberately parses only **this one** known format instead of writing a
 * general markdown parser: the renderer and the parser live in the same repo, and
 * when the format changes both sides change together, whereas a general parser would
 * have to answer for a pile of syntax this repo will never produce. Any unrecognized
 * line stays verbatim in the section body text, so the worst case is falling back to
 * today's appearance; no content is lost.
 */
data class DigestLink(val title: String, val url: String, val meta: String)

data class DigestSection(
    val heading: String,
    val body: String,
    val links: List<DigestLink>,
)

data class ParsedDigest(
    val title: String,
    val sections: List<DigestSection>,
    /** Leftover lines that belong to no section, kept verbatim; content is never lost. */
    val trailing: String,
)

private val HEADING = Regex("^#{1,3}\\s+(.*)$")

/** `- 2026-08-03 · [Title](https://…) · Source` and its lenient variants. */
private val LINK_LINE = Regex("^\\s*[-*]?\\s*(.*?)\\[([^]]+)]\\(([^)]+)\\)\\s*(.*)$")

fun parseDigestMarkdown(markdown: String): ParsedDigest {
    var title = ""
    val sections = mutableListOf<DigestSection>()
    val trailing = StringBuilder()

    var heading: String? = null
    val body = StringBuilder()
    val links = mutableListOf<DigestLink>()

    fun flush() {
        val current = heading ?: return
        sections += DigestSection(current, body.toString().trim(), links.toList())
        heading = null
        body.setLength(0)
        links.clear()
    }

    markdown.lines().forEach { line ->
        val headingMatch = HEADING.matchEntire(line.trimEnd())
        val linkMatch = LINK_LINE.matchEntire(line.trimEnd())
        when {
            headingMatch != null -> {
                val text = stripEmphasis(headingMatch.groupValues[1])
                if (title.isEmpty() && heading == null && sections.isEmpty()) {
                    title = text
                } else {
                    flush()
                    heading = text
                }
            }
            linkMatch != null -> {
                val prefix = stripEmphasis(linkMatch.groupValues[1]).trim().trim('·', '-', ' ')
                val suffix = stripEmphasis(linkMatch.groupValues[4]).trim().trim('·', '-', ' ')
                val meta = listOf(prefix, suffix).filter(String::isNotBlank).joinToString(" · ")
                val link = DigestLink(stripEmphasis(linkMatch.groupValues[2]), linkMatch.groupValues[3].trim(), meta)
                if (heading == null) trailing.appendLine(line) else links += link
            }
            else -> {
                val text = stripEmphasis(line)
                if (heading == null) {
                    if (text.isNotBlank()) trailing.appendLine(text)
                } else if (text.isNotBlank() || body.isNotEmpty()) {
                    body.appendLine(text)
                }
            }
        }
    }
    flush()
    return ParsedDigest(title, sections, trailing.toString().trim())
}

/** Strips only `**` and `*`: every other character is kept verbatim; there should be no accidental replacements in Chinese body text. */
internal fun stripEmphasis(value: String): String = value.replace("**", "").replace("*", "")
