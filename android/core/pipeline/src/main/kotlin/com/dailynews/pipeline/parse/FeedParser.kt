package com.dailynews.pipeline.parse

import com.dailynews.pipeline.fetch.LinkSafety
import com.dailynews.pipeline.text.TextUtils
import java.io.StringReader
import java.time.Instant
import org.jsoup.Jsoup
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

data class ParsedArticle(
    val title: String,
    val link: String,
    val publishedAt: Instant,
    val summaryEn: String,
)

class FeedParseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object FeedParser {
    fun parse(content: String, maxSummary: Int = 0): List<ParsedArticle> {
        // Android XML providers do not consistently support the JAXP security
        // feature set. Reject declarations in the untrusted feed itself and
        // install an EntityResolver below so security does not depend on any
        // one optional provider feature. Only the prolog is scanned: entity
        // declarations are illegal anywhere else, so matching the whole
        // document would reject healthy feeds whose articles merely quote
        // "<!ENTITY" as prose.
        SecureXml.rejectEntityDeclarations(content)
        val document = try {
            SecureXml.newDocumentBuilder().parse(InputSource(StringReader(content)))
        } catch (error: Exception) {
            throw FeedParseException("XML parse failed: ${error.message}", error)
        }
        val root = document.documentElement
        val isAtom = root.localTag().contains("feed") || root.namespaceURI == "http://www.w3.org/2005/Atom"
        val itemTag = if (isAtom) "entry" else "item"
        return descendants(root, itemTag).mapNotNull { item ->
            val title = item.firstText("title")
            if (title.isBlank()) return@mapNotNull null
            // `<guid>` / Atom `<id>` 常常根本不是 URL，而下游会拿它去打开浏览器、
            // 去抓取页面。不安全的一律降级成空 link——条目仍然保留（无 link 条目
            // 有稳定身份），只是不再是一个可被点击或抓取的目标。
            val rawLink = if (isAtom) atomLink(item) else item.firstText("link").ifBlank { item.firstText("guid") }
            val link = rawLink.takeIf { LinkSafety.isAcceptable(it) }.orEmpty()
            val dateText = if (isAtom) {
                item.firstNonBlank("published", "updated")
            } else {
                item.firstNonBlank("pubDate", "published", "date")
            }
            val publishedAt = TextUtils.parseRssDate(dateText) ?: return@mapNotNull null
            val summary = if (isAtom) {
                item.firstNonBlank("summary", "content")
            } else {
                item.firstNonBlank("description", "summary", "content", "encoded")
            }
            ParsedArticle(
                title = org.jsoup.parser.Parser.unescapeEntities(title, false),
                link = link,
                publishedAt = publishedAt,
                summaryEn = TextUtils.stripHtml(summary, maxSummary),
            )
        }
    }

    fun extractHtmlSummary(content: String, maxChars: Int = 0): String {
        if (content.isBlank()) return ""
        val document = Jsoup.parse(content)
        for (key in listOf("description", "og:description", "twitter:description")) {
            for (element in document.select("meta[name=$key], meta[property=$key]")) {
                val summary = TextUtils.stripHtml(element.attr("content"), maxChars)
                if (summary.isNotBlank()) return summary
            }
        }
        return ""
    }

    private fun atomLink(item: Element): String {
        val links = childElements(item).filter { it.localTag() == "link" }
        links.firstOrNull { it.getAttribute("rel").ifBlank { "alternate" } == "alternate" && it.getAttribute("href").isNotBlank() }
            ?.getAttribute("href")
            ?.let { return it }
        links.firstOrNull { it.getAttribute("href").isNotBlank() }?.getAttribute("href")?.let { return it }
        return item.firstText("link").ifBlank { item.firstText("id") }
    }

    private fun descendants(root: Element, tag: String): List<Element> {
        val result = mutableListOf<Element>()
        fun visit(node: Node) {
            if (node is Element && node.localTag() == tag) result += node
            val children = node.childNodes
            for (index in 0 until children.length) visit(children.item(index))
        }
        visit(root)
        return result
    }

    private fun Element.firstText(tag: String): String = childElements(this)
        .firstOrNull { it.localTag() == tag.lowercase() }
        ?.textContent
        ?.trim()
        .orEmpty()

    private fun Element.firstNonBlank(vararg tags: String): String = tags.firstNotNullOfOrNull { tag -> firstText(tag).takeIf(String::isNotBlank) }.orEmpty()
    private fun Element.localTag(): String = (localName ?: tagName.substringAfter(':')).lowercase()
    private fun childElements(node: Node): List<Element> = buildList {
        val children = node.childNodes
        for (index in 0 until children.length) (children.item(index) as? Element)?.let(::add)
    }
}
