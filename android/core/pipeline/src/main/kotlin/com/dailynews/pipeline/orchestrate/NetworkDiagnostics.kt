package com.dailynews.pipeline.orchestrate

import com.dailynews.model.FeedDefinition
import java.net.InetAddress
import java.net.Socket
import java.net.InetSocketAddress
import java.net.URI
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class NetworkProbe(val target: String, val stage: String, val passed: Boolean, val detail: String)
data class NetworkProbeTarget(val target: String, val url: String)

class NetworkDiagnostics(client: OkHttpClient) {
    private val client = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
    suspend fun run(
        feeds: List<FeedDefinition>,
        limit: Int = 5,
        androidContext: Map<String, String> = emptyMap(),
        providerTargets: List<NetworkProbeTarget> = emptyList(),
    ): List<NetworkProbe> = withContext(Dispatchers.IO) {
        buildList {
            androidContext.forEach { (key, value) -> add(NetworkProbe("android", key, true, value)) }
            suspend fun probe(target: String, url: String, anyHttpResponsePasses: Boolean) {
                val uri = runCatching { URI(url) }.getOrNull()
                val host = uri?.host
                if (host == null) {
                    add(NetworkProbe(target, "url", false, "invalid URL"))
                    return
                }
                val address = runCatching { InetAddress.getAllByName(host).joinToString { it.hostAddress } }
                add(NetworkProbe(target, "dns", address.isSuccess, address.getOrElse { it.message.orEmpty() }))
                val port = if (uri.port > 0) uri.port else if (uri.scheme == "http") 80 else 443
                val socket = runCatching {
                    Socket().use {
                        it.connect(InetSocketAddress(host, port), 5_000)
                        "connected $host:$port"
                    }
                }
                add(NetworkProbe(target, "tcp", socket.isSuccess, socket.getOrElse { it.message.orEmpty() }))
                val http = runCatching {
                    client.newCall(Request.Builder().url(url).header("User-Agent", "DailyNews Android diagnostics").build()).execute().use { response ->
                        "HTTP ${response.code}"
                    }
                }
                val detail = http.getOrElse { it.message.orEmpty() }
                val status = detail.substringAfter("HTTP ").toIntOrNull()
                // Provider probes are reachability checks without credentials. A 401/403/404
                // still proves that DNS, TCP and TLS reached the configured provider host.
                val passed = http.isSuccess && (anyHttpResponsePasses || status in 200..399)
                add(NetworkProbe(target, "https", passed, detail))
            }
            providerTargets.distinctBy { it.url }.forEach { target ->
                probe(target.target, target.url, anyHttpResponsePasses = true)
            }
            feeds.take(limit).forEach { feed ->
                probe(feed.name, feed.url, anyHttpResponsePasses = false)
            }
        }
    }

    companion object {
        private val NETWORK_EVIDENCE = Regex(
            "dns|timeout|timed out|ssl|certificate|http (403|429|5\\d\\d)|" +
                "unable to resolve host|no address associated with hostname|unknownhost|unknown host|" +
                "name or service not known|temporary failure in name resolution|nodename nor servname",
            RegexOption.IGNORE_CASE,
        )

        fun evidenceWarrantsProbe(evidence: String): Boolean = NETWORK_EVIDENCE.containsMatchIn(evidence)

        fun evidenceWarrantsProbe(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { cause ->
            cause is UnknownHostException || cause is SocketTimeoutException || cause is ConnectException ||
                cause is SSLException || evidenceWarrantsProbe(cause.message.orEmpty())
        }

        private val DELAYED_RETRY_EVIDENCE = Regex(
            "dns|timeout|timed out|http (429|5\\d\\d)|connection reset|connection refused|failed to connect|" +
                "unable to resolve host|no address associated with hostname|unknownhost|unknown host|" +
                "name or service not known|temporary failure in name resolution|nodename nor servname",
            RegexOption.IGNORE_CASE,
        )

        /** Bounded retry subset: excludes authorization failures and persistent certificate errors. */
        fun evidenceWarrantsDelayedRetry(error: Throwable): Boolean = generateSequence(error) { it.cause }.any { cause ->
            cause is UnknownHostException || cause is SocketTimeoutException || cause is ConnectException ||
                DELAYED_RETRY_EVIDENCE.containsMatchIn(cause.message.orEmpty())
        }
    }
}

fun interface UnexpectedFailureDiagnostics {
    suspend fun run(evidence: String): List<String>
}

object NoOpUnexpectedFailureDiagnostics : UnexpectedFailureDiagnostics {
    override suspend fun run(evidence: String): List<String> = emptyList()
}
