package com.dailynews.app.ui.periodic

/**
 * 把周期简报的 markdown 解析回结构。
 *
 * 简报本体是 `PeriodicDigestRenderer` 生成的、形状完全固定的 markdown，此前整段
 * 原样丢进一个 `Text` —— 用户看到的是字面的 `##`、`**` 和 `[标题](https://…)`，链接
 * 不可点，而隔壁一屏的日报是完整的结构化卡片。
 *
 * 这里刻意只做**这一种**已知格式的解析，不写通用 markdown 解析器：渲染端和解析端
 * 在同一个仓库里，格式变了两边一起改，而通用解析器要为一堆本仓库永远不会产出的
 * 语法负责。任何不认识的行都原样留在段落文本里，所以最坏情况是回到今天的样子，
 * 不会丢内容。
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
    /** 无法归入任何段落的剩余行，原样保留，绝不丢内容。 */
    val trailing: String,
)

private val HEADING = Regex("^#{1,3}\\s+(.*)$")

/** `- 2026-08-03 · [Title](https://…) · Source` 及其宽松变体。 */
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

/** 只去掉 `**` 与 `*`：其余字符原样保留，中文正文里不该有意外替换。 */
internal fun stripEmphasis(value: String): String = value.replace("**", "").replace("*", "")
