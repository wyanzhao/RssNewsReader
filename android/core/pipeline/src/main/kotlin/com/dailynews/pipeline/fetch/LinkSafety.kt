package com.dailynews.pipeline.fetch

import java.io.IOException
import java.net.URI
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Admission gate for article links.
 *
 * Links are taken verbatim from third-party feeds (the RSS `<link>`, falling back to `<guid>` / Atom `<id>` when
 * missing), and previously ran straight into two dangerous exits without a single validation anywhere along the way:
 *
 * 1. **UI**: five `CustomTabsIntent.launchUrl(context, link.toUri())` call sites. `launchUrl` builds a bare
 *    `ACTION_VIEW` without `CATEGORY_BROWSABLE`, so `tel:` / `market://` / app-private schemes can reach activities
 *    deliberately designed to be unreachable from the web; a URI with no scheme or with no handler throws
 *    `ActivityNotFoundException` outright, and none of those five sites has a runCatching — one tap and it crashes.
 * 2. **Fetching**: article-page enrichment GETs this URL. Pointing it at `192.168.x.x` issues a request from inside
 *    the user's own LAN, extracts a 150-word body into `articleText`, and then ships it to the cloud provider inside
 *    the prompt — LAN reconnaissance plus an exfiltration channel.
 *
 * Rejecting at pool admission is the only place that closes both exits at once: a bad link never enters Room at all,
 * and no downstream consumer has to remember to defend against it on its own.
 */
object LinkSafety {
    /**
     * Whether this link may be admitted into the article pool.
     *
     * Empty links are allowed: `ArticlePoolKeys` deliberately keeps a stable identity for linkless entries,
     * and they are neither opened nor fetched. What gets blocked here is the **non-empty but unsafe** kind.
     */
    fun isAcceptable(link: String): Boolean {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return true
        return isSafeHttpUrl(trimmed)
    }

    /**
     * Redirect gate.
     *
     * The admission-time check only ever sees the original URL the feed supplied. A perfectly innocent public link
     * can 302 to `http://192.168.1.1/`, letting an intranet target in through the back door — and this is exactly
     * the hop on the fetch path that is easiest to overlook. Use a **network** interceptor rather than an application
     * interceptor: an application interceptor runs once for the whole chain, while a network interceptor runs on every
     * hop, including every redirect.
     */
    fun privateHostInterceptor() = Interceptor { chain ->
        val url = chain.request().url
        if (isPrivateHost(url.host)) {
            throw IOException("refused request to private host ${url.host}")
        }
        chain.proceed(chain.request()) as Response
    }

    /** Safe to issue a network request to, or hand to the browser to open. */
    fun isSafeHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in HTTP_SCHEMES) return false
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return false
        return !isPrivateHost(host)
    }

    /**
     * Blocks intranet targets by their literal form.
     *
     * This does not defend against DNS rebinding — a real defense would require replacing OkHttp's
     * `Dns`/`SocketFactory` — but it closes the entire real-world attack surface: a hostile feed writing intranet
     * addresses directly. The check looks only at the literal and performs no DNS resolution, because resolving
     * needs the network, and this function is called for every entry on the hot path of feed parsing.
     *
     * IPv6 ULA (`fc00::/7`) and link-local (`fe80::/10`) are matched as hextets, not hostname prefixes:
     * `startsWith("fc")` previously treated `fcc.gov` as unique-local. IPv4-mapped addresses
     * (`::ffff:10.0.0.1`) are classified by the embedded IPv4, not skipped as "not four dotted octets".
     */
    internal fun isPrivateHost(host: String): Boolean {
        val name = host.trim().trim('[', ']').lowercase()
        if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local")) return true
        if (':' in name) return isPrivateIpv6(name)
        return isPrivateIpv4(name)
    }

    private fun isPrivateIpv4(name: String): Boolean {
        val octets = name.split('.')
        if (octets.size != 4 || octets.any { part -> part.isEmpty() || !part.all(Char::isDigit) }) return false
        val numbers = octets.map { it.toIntOrNull() ?: return false }
        if (numbers.any { it !in 0..255 }) return false
        val (first, second) = numbers
        return when {
            first == 0 || first == 127 -> true
            first == 10 -> true
            first == 169 && second == 254 -> true
            first == 172 && second in 16..31 -> true
            first == 192 && second == 168 -> true
            // CGNAT 100.64.0.0/10: carrier-grade internal network, likewise something feeds must never point at.
            first == 100 && second in 64..127 -> true
            else -> false
        }
    }

    private fun isPrivateIpv6(name: String): Boolean {
        if (name == "::1" || name.startsWith("::1:")) return true
        ipv4MappedDotted(name)?.let { return isPrivateIpv4(it) }
        val first = name.substringBefore(':')
        if (first.isEmpty() || first.length > 4 || first.any { it !in HEX }) return false
        val hextet = first.toInt(16)
        // fe80::/10 link-local; fc00::/7 unique local.
        return hextet in 0xfe80..0xfebf || hextet in 0xfc00..0xfdff
    }

    /** Dotted-quad IPv4 embedded in `:ffff:` (compressed `::ffff:a.b.c.d` or expanded `0:0:…:ffff:a.b.c.d`). */
    private fun ipv4MappedDotted(name: String): String? {
        val marker = ":ffff:"
        val prefix = when {
            name.startsWith("::ffff:") -> ""
            marker in name -> name.substringBefore(marker)
            else -> return null
        }
        if (prefix.any { it != '0' && it != ':' }) return null
        val rest = name.substringAfterLast("ffff:")
        return rest.takeIf { candidate ->
            val octets = candidate.split('.')
            octets.size == 4 && octets.all { part -> part.isNotEmpty() && part.all(Char::isDigit) }
        }
    }

    private val HTTP_SCHEMES = setOf("http", "https")
    private val HEX = "0123456789abcdef"
}
