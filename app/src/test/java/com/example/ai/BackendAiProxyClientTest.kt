package com.example.ai

import com.example.ai.provider.BackendAiProxyClient
import com.example.ai.provider.ProviderId
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackendAiProxyClientTest {

    @Test
    fun checkHealthGenerateAndStreamSse_communicateWithAuthenticatedBackend() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { ex ->
            val body = """{"service":"myraa-super-brain-backend","status":"ok","timestampEpochMs":12345}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/ai/generate") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            if (auth != "Bearer secret_firebase_jwt") {
                ex.sendResponseHeaders(401, -1)
                return@createContext
            }
            val body = """{"providerId":"openai_gpt","modelId":"gpt-4.1-mini","text":"Proxy OK","promptTokens":10,"completionTokens":5,"latencyMs":42}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/ai/stream") { ex ->
            val sse = "data: {\"delta\":\"Hello \"}\n\ndata: {\"delta\":\"World\"}\n\ndata: {\"done\":true}\n\n".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, sse.size.toLong())
            ex.responseBody.use { it.write(sse) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val testOkHttpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val client = BackendAiProxyClient(
                httpClient = testOkHttpClient,
                tokenProvider = { "secret_firebase_jwt" }
            )

            val health = client.checkHealth(baseUrl).getOrThrow()
            assertEquals("ok", health.status)

            val gen = client.generateViaProxy(
                baseUrl = baseUrl,
                providerId = ProviderId.OPENAI_GPT,
                modelId = "gpt-4.1-mini",
                systemPrompt = "sys",
                prompt = "hello"
            ).getOrThrow()
            assertEquals("Proxy OK", gen.text)
            assertEquals(10L, gen.promptTokens)

            val chunks = client.streamViaProxy(
                baseUrl = baseUrl,
                providerId = ProviderId.OPENAI_GPT,
                modelId = "gpt-4.1-mini",
                systemPrompt = "sys",
                prompt = "hello"
            ).toList()
            assertEquals(listOf("Hello ", "Hello World"), chunks)

            val invalidUrlRes = client.checkHealth("http://not-allowed-insecure-remote.example.com")
            assertTrue(invalidUrlRes.isFailure)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun saveAndDeleteProviderApiKey_communicatesWithVaultAndClassifiesErrors() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/providers/keys") { ex ->
            when (ex.requestMethod) {
                "POST" -> {
                    val body = """
                        {
                          "provider": {
                            "providerId": "groq",
                            "freeTier": true,
                            "connected": true,
                            "statusCode": "CONNECTED_LIVE",
                            "keySource": "USER_VAULT",
                            "maskedKeyHint": "gsk_••••9999",
                            "message": "Verified live",
                            "models": ["llama-3.3-70b-versatile"]
                          }
                        }
                    """.trimIndent().toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                "DELETE" -> {
                    val body = """
                        {
                          "deleted": true,
                          "provider": {
                            "providerId": "groq",
                            "freeTier": true,
                            "connected": false,
                            "statusCode": "MISSING_API_KEY",
                            "keySource": "NONE",
                            "maskedKeyHint": "",
                            "message": "Missing GROQ_API_KEY",
                            "models": ["llama-3.3-70b-versatile"]
                          }
                        }
                    """.trimIndent().toByteArray()
                    ex.sendResponseHeaders(200, body.size.toLong())
                    ex.responseBody.use { it.write(body) }
                }
                else -> ex.sendResponseHeaders(405, -1)
            }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val client = BackendAiProxyClient(tokenProvider = { "secret_firebase_jwt" })

            val saveRes = client.saveProviderApiKey(baseUrl, ProviderId.GROQ, "gsk_secret_key_9999").getOrThrow()
            assertEquals(com.example.ai.provider.ProviderConnectionStatus.CONNECTED_PROXY, saveRes.status)
            assertEquals("USER_VAULT", saveRes.keySource)
            assertEquals("gsk_••••9999", saveRes.maskedKeyHint)

            val delRes = client.deleteProviderApiKey(baseUrl, ProviderId.GROQ).getOrThrow()
            assertEquals(com.example.ai.provider.ProviderConnectionStatus.MISSING_API_KEY, delRes.status)
            assertEquals("NONE", delRes.keySource)

            val rateErr = com.example.ai.provider.ProviderHttpException(429, "RATE_LIMITED", "Rate limited")
            assertEquals(com.example.ai.provider.ProviderConnectionStatus.RATE_LIMITED, com.example.ai.provider.classifyProviderError(rateErr))
            val quotaErr = com.example.ai.provider.ProviderHttpException(402, "QUOTA_EXPIRED", "Out of quota")
            assertEquals(com.example.ai.provider.ProviderConnectionStatus.QUOTA_EXPIRED, com.example.ai.provider.classifyProviderError(quotaErr))
            val offlineErr = java.net.ConnectException("Connection refused")
            assertEquals(com.example.ai.provider.ProviderConnectionStatus.OFFLINE, com.example.ai.provider.classifyProviderError(offlineErr))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun urlValidation_offlineHealth_andInvalidApiKey_reportHonestErrors() = runBlocking {
        assertTrue(BackendAiProxyClient.validateBackendUrlError("https://myraa-backend.run.app") == null)
        assertTrue(BackendAiProxyClient.validateBackendUrlError("http://10.0.2.2:8088") == null)
        assertTrue(BackendAiProxyClient.validateBackendUrlError("https://") != null)
        assertTrue(BackendAiProxyClient.validateBackendUrlError("https://bad host.example.com") != null)
        assertTrue(BackendAiProxyClient.validateBackendUrlError("http://remote-insecure.example.com") != null)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val closedPort = server.address.port
        server.createContext("/v1/providers/keys") { ex ->
            val body = """
                {
                  "saved": false,
                  "verified": false,
                  "provider": {
                    "providerId": "groq",
                    "freeTier": true,
                    "connected": false,
                    "statusCode": "INVALID_API_KEY",
                    "keyConfigured": false,
                    "keySource": "NONE",
                    "maskedKeyHint": "",
                    "message": "Groq rejected this API key (401)."
                  }
                }
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()

        val client = BackendAiProxyClient(tokenProvider = { "secret_firebase_jwt" })
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val badKeyState = client.saveProviderApiKey(baseUrl, ProviderId.GROQ, "gsk_invalid_0000").getOrThrow()
            assertEquals("INVALID_API_KEY", badKeyState.statusCode)
            assertEquals(com.example.ai.provider.ProviderConnectionStatus.PROBE_ERROR, badKeyState.status)
            assertEquals("NONE", badKeyState.keySource)

            val shortKeyRes = client.saveProviderApiKey(baseUrl, ProviderId.GROQ, "short")
            assertTrue(shortKeyRes.isFailure)
        } finally {
            server.stop(0)
        }

        val offlineHealth = client.checkHealth("http://127.0.0.1:$closedPort")
        assertTrue(offlineHealth.isFailure)
        assertEquals(
            com.example.ai.provider.ProviderConnectionStatus.OFFLINE,
            com.example.ai.provider.classifyProviderError(offlineHealth.exceptionOrNull()!!)
        )
    }

    @Test
    fun expiredIdToken_triggersForceRefreshAndRetriesAuthenticatedRequests() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val receivedAuthHeaders = mutableListOf<String>()
        server.createContext("/health") { ex ->
            val body = """{"service":"myraa-super-brain-backend","status":"ok","appCheckAutoRegistrarConfigured":true,"timestampEpochMs":999}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/v1/appcheck/registrar/status") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
            receivedAuthHeaders.add(auth)
            if (auth != "Bearer refreshed_id_jwt") {
                val err = """{"error":"Firebase ID token has expired."}""".toByteArray()
                ex.sendResponseHeaders(401, err.size.toLong())
                ex.responseBody.use { it.write(err) }
                return@createContext
            }
            val ok = """
                {
                  "configured": true,
                  "credentialMode": "SERVICE_ACCOUNT_ENV",
                  "projectId": "gen-lang-client-0645829429",
                  "androidPackage": "com.aistudio.myraa.qxkrmp",
                  "reason": "Active"
                }
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, ok.size.toLong())
            ex.responseBody.use { it.write(ok) }
        }
        server.createContext("/v1/providers/status") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
            receivedAuthHeaders.add(auth)
            if (auth != "Bearer refreshed_id_jwt") {
                val err = """{"error":"Firebase ID token has expired."}""".toByteArray()
                ex.sendResponseHeaders(401, err.size.toLong())
                ex.responseBody.use { it.write(err) }
                return@createContext
            }
            val ok = """{"providers":[]}""".toByteArray()
            ex.sendResponseHeaders(200, ok.size.toLong())
            ex.responseBody.use { it.write(ok) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val forceFlags = mutableListOf<Boolean>()
            val client = BackendAiProxyClient(
                tokenFetcher = com.example.core.security.FirebaseIdTokenFetcher { forceRefresh ->
                    forceFlags.add(forceRefresh)
                    if (forceRefresh) "refreshed_id_jwt" else "expired_id_jwt"
                }
            )

            val health = client.checkHealth(baseUrl).getOrThrow()
            assertTrue(health.appCheckAutoRegistrarConfigured)
            assertTrue(health.latencyMs >= 1L)

            val registrar = client.fetchRegistrarStatus(baseUrl).getOrThrow()
            assertTrue(registrar.configured)
            assertEquals("SERVICE_ACCOUNT_ENV", registrar.credentialMode)
            assertTrue(registrar.tokenRefreshedOnRetry)
            assertEquals(listOf(false, true), forceFlags)
            assertEquals(listOf("Bearer expired_id_jwt", "Bearer refreshed_id_jwt"), receivedAuthHeaders)

            val providers = client.probeProviders(baseUrl).getOrThrow()
            assertTrue(providers.isEmpty())

            // Persistent 401 after refresh surfaces FIREBASE_AUTH_REQUIRED cleanly
            val alwaysRejectedClient = BackendAiProxyClient(
                tokenFetcher = com.example.core.security.FirebaseIdTokenFetcher { "still_rejected_jwt" }
            )
            val failedReg = alwaysRejectedClient.fetchRegistrarStatus(baseUrl)
            assertTrue(failedReg.isFailure)
            val httpEx = failedReg.exceptionOrNull() as com.example.ai.provider.ProviderHttpException
            assertEquals(401, httpEx.httpCode)
            assertEquals("FIREBASE_AUTH_REQUIRED", httpEx.errorCode)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun nonKeyErrors_preserveConfigurationUnavailableNetworkAndAppCheckCategories() {
        assertEquals(
            com.example.ai.provider.ProviderConnectionStatus.UNAVAILABLE,
            com.example.ai.provider.mapStatusCodeToConnectionStatus(false, "CONFIGURATION_ERROR")
        )
        assertEquals(
            com.example.ai.provider.ProviderConnectionStatus.UNAVAILABLE,
            com.example.ai.provider.mapStatusCodeToConnectionStatus(false, "PROVIDER_UNAVAILABLE")
        )
        assertEquals(
            com.example.ai.provider.ProviderConnectionStatus.OFFLINE,
            com.example.ai.provider.mapStatusCodeToConnectionStatus(false, "NETWORK_ERROR")
        )
        assertEquals(
            com.example.ai.provider.ProviderConnectionStatus.UNAVAILABLE,
            com.example.ai.provider.mapStatusCodeToConnectionStatus(false, "APP_CHECK_REQUIRED")
        )
        assertEquals(
            com.example.ai.provider.ProviderConnectionStatus.PROBE_ERROR,
            com.example.ai.provider.mapStatusCodeToConnectionStatus(false, "INVALID_API_KEY")
        )

        val mismatch = BackendAiProxyClient.detectProviderKeyPrefixMismatch(
            ProviderId.GROQ,
            "sk-or-v1-example_openrouter_key"
        )
        assertTrue(mismatch != null && mismatch.contains("OpenRouter", ignoreCase = true) && mismatch.contains("gsk_"))
        assertEquals(
            null,
            BackendAiProxyClient.detectProviderKeyPrefixMismatch(ProviderId.OPENROUTER_FREE, "sk-or-v1-example_openrouter_key")
        )
        assertEquals(
            null,
            BackendAiProxyClient.detectProviderKeyPrefixMismatch(ProviderId.GROQ, "gsk_example_groq_key")
        )
    }
}

