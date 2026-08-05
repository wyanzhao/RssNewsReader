package com.dailynews.pipeline.orchestrate

import com.dailynews.model.FeedDefinition
import java.net.InetAddress
import java.net.Socket
import java.net.InetSocketAddress
import java.net.URI
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class NetworkProbe(val target: String, val stage: String, val passed: Boolean, val detail: String)

class NetworkDiagnostics(private val client: OkHttpClient) {
    suspend fun run(feeds: List<FeedDefinition>, limit: Int = 5, androidContext: Map<String, String> = emptyMap()): List<NetworkProbe> = withContext(Dispatchers.IO) {
        buildList {
            androidContext.forEach { (key, value) -> add(NetworkProbe("android", key, true, value)) }
            feeds.take(limit).forEach { feed ->
                val uri = runCatching { URI(feed.url) }.getOrNull()
                val host = uri?.host
                if (host == null) {
                    add(NetworkProbe(feed.name, "url", false, "invalid URL"))
                    return@forEach
                }
                val address = runCatching { InetAddress.getAllByName(host).joinToString { it.hostAddress } }
                add(NetworkProbe(feed.name, "dns", address.isSuccess, address.getOrElse { it.message.orEmpty() }))
                val port = if (uri.port > 0) uri.port else if (uri.scheme == "http") 80 else 443
                val socket = runCatching {
                    Socket().use {
                        it.connect(InetSocketAddress(host, port), 5_000)
                        "connected $host:$port"
                    }
                }
                add(NetworkProbe(feed.name, "tcp", socket.isSuccess, socket.getOrElse { it.message.orEmpty() }))
                val http = runCatching {
                    client.newCall(Request.Builder().url(feed.url).header("User-Agent", "DailyNews Android diagnostics").build()).execute().use { response ->
                        "HTTP ${response.code}"
                    }
                }
                val detail = http.getOrElse { it.message.orEmpty() }
                val passed = http.isSuccess && detail.substringAfter("HTTP ").toIntOrNull() in 200..399
                add(NetworkProbe(feed.name, "https", passed, detail))
            }
        }
    }

    companion object {
        fun evidenceWarrantsProbe(evidence: String): Boolean = Regex("dns|timeout|timed out|ssl|certificate|http (403|429|5\\d\\d)", RegexOption.IGNORE_CASE).containsMatchIn(evidence)

        fun evidenceWarrantsProbe(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { cause ->
            cause is UnknownHostException || cause is SocketTimeoutException || cause is ConnectException ||
                cause is SSLException || evidenceWarrantsProbe(cause.message.orEmpty())
        }
    }
}

fun interface UnexpectedFailureDiagnostics {
    suspend fun run(evidence: String): List<String>
}

object NoOpUnexpectedFailureDiagnostics : UnexpectedFailureDiagnostics {
    override suspend fun run(evidence: String): List<String> = emptyList()
}
