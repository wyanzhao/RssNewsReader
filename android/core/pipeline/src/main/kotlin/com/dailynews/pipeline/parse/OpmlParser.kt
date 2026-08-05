package com.dailynews.pipeline.parse

import com.dailynews.model.FeedDefinition
import java.io.StringReader
import org.w3c.dom.Element
import org.xml.sax.InputSource

object OpmlParser {
    /** @throws FeedParseException when the document cannot be read; callers surface this to the user. */
    fun parse(content: String): List<FeedDefinition> {
        SecureXml.rejectEntityDeclarations(content)
        val document = try {
            SecureXml.newDocumentBuilder().parse(InputSource(StringReader(content)))
        } catch (error: Exception) {
            throw FeedParseException("OPML parse failed: ${error.message}", error)
        }
        val outlines = document.getElementsByTagName("outline")
        val feeds = mutableListOf<FeedDefinition>()
        for (index in 0 until outlines.length) {
            val element = outlines.item(index) as? Element ?: continue
            val url = element.getAttribute("xmlUrl").trim()
            if (url.isEmpty()) continue
            val name = element.getAttribute("text").ifBlank { element.getAttribute("title") }.ifBlank { url }
            feeds += FeedDefinition(name, url, position = feeds.size)
        }
        return feeds.distinctBy { it.url }
    }

    fun render(feeds: List<FeedDefinition>): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<opml version=\"2.0\">")
        appendLine("  <head><title>DailyNews feeds</title></head>")
        appendLine("  <body>")
        feeds.sortedBy { it.position }.forEach { feed ->
            appendLine("    <outline type=\"rss\" text=\"${xml(feed.name)}\" title=\"${xml(feed.name)}\" xmlUrl=\"${xml(feed.url)}\" />")
        }
        appendLine("  </body>")
        appendLine("</opml>")
    }

    private fun xml(value: String): String = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
