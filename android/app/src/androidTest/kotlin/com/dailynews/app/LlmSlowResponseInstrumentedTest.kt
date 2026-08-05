package com.dailynews.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dailynews.llm.ApiKeySource
import com.dailynews.llm.LlmRequest
import com.dailynews.llm.OpenAiCompatProvider
import com.dailynews.llm.ProviderConfig
import com.dailynews.llm.ProviderType
import com.dailynews.llm.StructuredLlm
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlmSlowResponseInstrumentedTest {
    @Test
    fun delayedResponseBodyIsRetriedOnDevice() = runBlocking {
        MockWebServer().use { server ->
            val certificate = HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost")
                .build()
            val serverCertificates = HandshakeCertificates.Builder()
                .heldCertificate(certificate)
                .build()
            val clientCertificates = HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate)
                .build()
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.enqueue(
                MockResponse()
                    .setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"ok\":false}"}}]}""")
                    .setBodyDelay(300, TimeUnit.MILLISECONDS),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"choices":[{"message":{"role":"assistant","content":"{\"ok\":true}"},"finish_reason":"stop"}]}""",
                ),
            )
            server.start()
            val provider = OpenAiCompatProvider(
                ProviderConfig("device-slow", ProviderType.OPENAI_COMPAT, server.url("/v1").toString(), "alias", true),
                ApiKeySource { "instrumentation-secret" },
                OkHttpClient.Builder()
                    .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                    .protocols(listOf(Protocol.HTTP_1_1))
                    .readTimeout(75, TimeUnit.MILLISECONDS)
                    .build(),
            )

            val result = StructuredLlm(provider, maxTransientRetries = 1, retryDelay = {})
                .completeObject(LlmRequest("test-model", "system", "user", 64)).first

            assertEquals("true", result["ok"].toString())
            assertEquals(2, server.requestCount)
        }
    }
}
