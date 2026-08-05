package com.dailynews.pipeline.parse

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import org.xml.sax.InputSource

/**
 * Shared XML hardening for every untrusted document this app parses (feeds and
 * imported OPML alike). Android/OEM providers reject different subsets of the
 * JAXP security surface, so each control is negotiated on its own and the
 * EntityResolver below carries the guarantee that actually matters.
 */
internal object SecureXml {
    private val entityDeclaration = Regex("<!\\s*ENTITY\\b", RegexOption.IGNORE_CASE)

    /**
     * Provider-independent internal-entity gate. The scanner skips comments and processing
     * instructions while locating the real root element, so a comment can neither hide a
     * following DOCTYPE nor falsely trigger on security prose that quotes `<!ENTITY`.
     */
    fun rejectEntityDeclarations(content: String) {
        val prolog = content.substring(0, rootElementOffset(content))
        val declarationsOnly = prolog
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
            .replace(Regex("<\\?[\\s\\S]*?\\?>"), "")
        if (entityDeclaration.containsMatchIn(declarationsOnly)) {
            throw FeedParseException("XML entity declarations are not allowed")
        }
    }

    private fun rootElementOffset(content: String): Int {
        var index = 0
        while (index < content.length) {
            val start = content.indexOf('<', index)
            if (start < 0) return content.length
            when {
                content.startsWith("<!--", start) -> {
                    val end = content.indexOf("-->", start + 4)
                    if (end < 0) return content.length
                    index = end + 3
                }
                content.startsWith("<?", start) -> {
                    val end = content.indexOf("?>", start + 2)
                    if (end < 0) return content.length
                    index = end + 2
                }
                content.getOrNull(start + 1)?.let { it.isLetter() || it == '_' || it == ':' } == true -> return start
                else -> index = start + 1
            }
        }
        return content.length
    }

    fun newDocumentBuilder(): DocumentBuilder {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
        }

        factory.trySecurityOption { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        factory.trySecurityOption { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        factory.trySecurityOption { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        factory.trySecurityOption { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        factory.trySecurityOption { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        factory.trySecurityOption { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        factory.trySecurityOption { isExpandEntityReferences = false }
        factory.trySecurityOption { isXIncludeAware = false }

        return factory.newDocumentBuilder().apply {
            // Provider-independent containment boundary: external subsets and
            // entities resolve to empty local input and can never trigger a
            // network or file-system read.
            setEntityResolver { _, _ -> InputSource(StringReader("")) }
        }
    }

    private inline fun DocumentBuilderFactory.trySecurityOption(block: DocumentBuilderFactory.() -> Unit) {
        try {
            block()
        } catch (_: ParserConfigurationException) {
            // Unsupported by this Android/JAXP provider; the EntityResolver
            // remains the mandatory safety layer.
        } catch (_: IllegalArgumentException) {
            // JAXP attributes are optional on older Android providers.
        } catch (_: UnsupportedOperationException) {
            // XInclude/entity-expansion setters may be unavailable.
        } catch (_: AbstractMethodError) {
            // Defensive compatibility for incomplete OEM implementations.
        }
    }
}
