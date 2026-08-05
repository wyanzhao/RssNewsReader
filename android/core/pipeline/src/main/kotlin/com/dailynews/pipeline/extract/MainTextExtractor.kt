package com.dailynews.pipeline.extract

import com.dailynews.pipeline.text.TextUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

object MainTextExtractor {
    private const val BLOCK_SELECTOR = "p,li,h1,h2,h3,h4,h5,h6,blockquote,pre"
    private const val SKIP_SELECTOR = "script,style,noscript,nav,aside,footer,header,form,figure,button,iframe,svg,select,template,picture,source"

    fun extract(html: String): String {
        if (html.isBlank()) return ""
        return runCatching {
            val document = Jsoup.parse(html)
            document.select(SKIP_SELECTOR).remove()
            val blocks = blocksInClosingOrder(document)
            val preferred = blocks.filter(::isInsidePreferredContainer)
            (preferred.takeIf(List<Element>::isNotEmpty) ?: blocks)
                // cleanText, not a (?U) regex: Android's ICU engine rejects that
                // flag outright, and Python's \s is Unicode-aware.
                .map { block -> TextUtils.cleanText(directText(block)) }
                .filter(String::isNotBlank)
                .joinToString("\n")
        }.getOrDefault("")
    }

    fun truncateWords(text: String, maxWords: Int): String {
        if (text.isBlank() || maxWords <= 0) return text
        val words = TextUtils.cleanText(text).split(' ').filter(String::isNotEmpty)
        return if (words.size <= maxWords) words.joinToString(" ") else words.take(maxWords).joinToString(" ") + "..."
    }

    /**
     * Python's streaming parser emits a block when its *end* tag arrives, so a
     * nested block is emitted before the block enclosing it. Post-order does
     * the same for a parsed tree.
     */
    private fun blocksInClosingOrder(root: Element): List<Element> = buildList {
        fun visit(element: Element) {
            element.children().forEach(::visit)
            if (isBlock(element)) add(element)
        }
        visit(root)
    }

    /**
     * The text a block owns itself. Python appends character data only to the
     * innermost open block, so `<li>lead-in<p>body</p></li>` keeps "lead-in"
     * on the `li` instead of dropping it along with the whole block.
     */
    private fun directText(block: Element): String = buildString {
        fun walk(node: Node) {
            when {
                node is TextNode -> append(node.wholeText)
                node is Element && isBlock(node) -> Unit // belongs to that nested block
                node is Element -> node.childNodes().forEach(::walk)
            }
        }
        block.childNodes().forEach(::walk)
    }

    private fun isBlock(element: Element): Boolean = element.`is`(BLOCK_SELECTOR)

    private fun isInsidePreferredContainer(element: Element): Boolean =
        sequenceOf(element).plus(element.parents().asSequence()).any { candidate ->
            candidate.tagName().equals("article", ignoreCase = true) ||
                candidate.tagName().equals("main", ignoreCase = true) ||
                candidate.attr("role").equals("main", ignoreCase = true)
        }
}
