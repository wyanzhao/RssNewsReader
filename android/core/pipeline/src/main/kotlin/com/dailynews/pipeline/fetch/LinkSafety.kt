package com.dailynews.pipeline.fetch

import java.io.IOException
import java.net.URI
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 文章 link 的准入闸。
 *
 * link 逐字取自第三方 feed（RSS `<link>`，缺失时回落 `<guid>` / Atom `<id>`），此前
 * 一路直达两个危险出口，中途没有任何一处校验过它：
 *
 * 1. **UI**：五处 `CustomTabsIntent.launchUrl(context, link.toUri())`。`launchUrl` 构造的是
 *    裸 `ACTION_VIEW` 且不加 `CATEGORY_BROWSABLE`，所以 `tel:` / `market://` / 应用私有
 *    scheme 能触达那些刻意设计成网页无法触达的 activity；无 scheme 或无人处理的 URI
 *    则直接抛 `ActivityNotFoundException`，那五处都没有 runCatching，点一下就崩。
 * 2. **抓取**：文章页富化会去 GET 这个 URL。指向 `192.168.x.x` 就是从用户局域网内部
 *    发起请求，抽出 150 词正文存进 `articleText`，再随 prompt 发到云端供应商——局域网
 *    侦察外加一条外传通道。
 *
 * 在入池处拒绝是唯一能同时关掉两个出口的地方：坏 link 根本不进 Room，后面每一个
 * 消费者都不必各自记得防一遍。
 */
object LinkSafety {
    /**
     * 是否允许把这条 link 收进文章池。
     *
     * 空 link 是允许的：`ArticlePoolKeys` 特意给无 link 条目保留了稳定身份，
     * 而且它们既不会被打开也不会被抓取。这里挡的是**有值但不安全**的那些。
     */
    fun isAcceptable(link: String): Boolean {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) return true
        return isSafeHttpUrl(trimmed)
    }

    /**
     * 重定向闸。
     *
     * 入池处的校验只看得到 feed 给出的原始 URL。一个完全正常的公网链接可以 302 到
     * `http://192.168.1.1/`，于是内网目标从后门进来——而这正是抓取路径上最容易被
     * 忽略的一跳。用**网络**拦截器而不是应用拦截器：应用拦截器整条链只跑一次，
     * 网络拦截器每一跳都跑，包括每一次重定向。
     */
    fun privateHostInterceptor() = Interceptor { chain ->
        val url = chain.request().url
        if (isPrivateHost(url.host)) {
            throw IOException("refused request to private host ${url.host}")
        }
        chain.proceed(chain.request()) as Response
    }

    /** 可以对它发起网络请求，或交给浏览器打开。 */
    fun isSafeHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in HTTP_SCHEMES) return false
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return false
        return !isPrivateHost(host)
    }

    /**
     * 按字面形态挡掉内网目标。
     *
     * 这不防 DNS rebinding——真要防得换掉 OkHttp 的 `Dns`/`SocketFactory`——但它关掉了
     * 现实里的整个攻击面：敌意 feed 直接写内网地址。判断只看字面量，不做 DNS 解析，
     * 因为解析要联网，而这个函数会在解析 feed 的热路径上对每一条目调用。
     */
    private fun isPrivateHost(host: String): Boolean {
        val name = host.trim().trim('[', ']').lowercase()
        if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".local")) return true
        if (name == "::1" || name.startsWith("fc") || name.startsWith("fd") || name.startsWith("fe80:")) return true
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
            // CGNAT 100.64.0.0/10：运营商级内网，同样不该被 feed 指过去。
            first == 100 && second in 64..127 -> true
            else -> false
        }
    }

    private val HTTP_SCHEMES = setOf("http", "https")
}
