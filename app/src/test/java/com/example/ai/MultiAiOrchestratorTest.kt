package com.example.ai

import com.example.ai.provider.AiModelDescriptor
import com.example.ai.provider.AiModelProvider
import com.example.ai.provider.AiProviderDescriptor
import com.example.ai.provider.BackendAiProxyClient
import com.example.ai.provider.BackendProxyAiProvider
import com.example.ai.provider.BrainGenerationRequest
import com.example.ai.provider.ModelCapability
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.ModelRouter
import com.example.ai.provider.MultiAiOrchestrator
import com.example.ai.provider.ProviderConnectionStatus
import com.example.ai.provider.ProviderGenerationOutput
import com.example.ai.provider.ProviderId
import com.example.ai.provider.StructuredOrchestrationPlan
import com.example.ai.provider.TaskCategory
import com.example.ai.provider.TokenAndCostTracker
import com.example.data.model.BrainLanguage
import com.example.data.model.TargetFramework
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MultiAiOrchestratorTest {

    private class FakeMultiModelProvider(
        override val descriptor: AiProviderDescriptor,
        private val responseByModel: Map<String, String>
    ) : AiModelProvider {
        override suspend fun analyzeAndPlan(
            request: BrainGenerationRequest,
            modelId: String
        ): StructuredOrchestrationPlan {
            return StructuredOrchestrationPlan(
                detectedLanguage = BrainLanguage.EN,
                detectedGoal = "Test",
                intentSummary = "Test",
                selectedTaskCategory = TaskCategory.CODE_REASONING,
                recommendedFramework = TargetFramework.ANDROID_COMPOSE,
                assignedAgents = listOf("Coding Agent"),
                subtasks = listOf("Step 1"),
                architectureNotes = "MVVM",
                detectedRisksOrGaps = emptyList()
            )
        }

        override fun streamSynthesizedResponse(
            request: BrainGenerationRequest,
            plan: StructuredOrchestrationPlan,
            modelId: String
        ): Flow<String> = flowOf(responseByModel[modelId] ?: "Default stream")

        override suspend fun generateRawText(
            prompt: String,
            systemPrompt: String,
            modelId: String,
            backendServerUrl: String
        ): ProviderGenerationOutput {
            val text = responseByModel[modelId]
                ?: throw IllegalStateException("Simulated failure for $modelId")
            val pt = TokenAndCostTracker.estimateTokens(prompt)
            val ct = TokenAndCostTracker.estimateTokens(text)
            return ProviderGenerationOutput(
                text = text,
                promptTokens = pt,
                completionTokens = ct,
                latencyMs = 45L,
                estimatedCostUsdMicros = TokenAndCostTracker.computeCostUsdMicros(modelId, pt, ct)
            )
        }
    }

    @Test
    fun tokenAndCostTracker_estimatesBengaliAndEnglishAccurately() {
        val enTokens = TokenAndCostTracker.estimateTokens("Build an Android Compose chat screen")
        val bnTokens = TokenAndCostTracker.estimateTokens("বাংলায় একটি চ্যাট অ্যাপ তৈরি করো")
        assertTrue(enTokens >= 5L)
        assertTrue(bnTokens >= 10L)

        val costMicros = TokenAndCostTracker.computeCostUsdMicros("gemini-3.8-flash", 1000L, 1000L)
        assertTrue(costMicros > 0L)
        assertTrue(TokenAndCostTracker.formatUsdMicros(costMicros).startsWith("$"))
    }

    @Test
    fun parallelComparisonAndConsensus_runsLiveModelsAndHonorsOfflineFailure() = runBlocking {
        val geminiProvider = FakeMultiModelProvider(
            descriptor = AiProviderDescriptor(
                providerId = ProviderId.GOOGLE_GEMINI,
                displayName = "Google Gemini",
                status = ProviderConnectionStatus.CONNECTED_LIVE,
                securityNote = "Live",
                models = listOf(
                    AiModelDescriptor(
                        modelId = ModelRouter.MODEL_GEMINI_FLASH,
                        providerId = ProviderId.GOOGLE_GEMINI,
                        displayName = "Gemini 3.8 Flash",
                        roleSummary = "Primary",
                        capabilities = setOf(ModelCapability.REASONING, ModelCapability.CODING),
                        latencyTier = "Fast",
                        costTier = "Free",
                        isLiveInCurrentBuild = true
                    ),
                    AiModelDescriptor(
                        modelId = ModelRouter.MODEL_GEMINI_FLASH_LITE,
                        providerId = ProviderId.GOOGLE_GEMINI,
                        displayName = "Gemini 3.5 Flash-Lite",
                        roleSummary = "Fast",
                        capabilities = setOf(ModelCapability.FAST_CHAT),
                        latencyTier = "Ultra Fast",
                        costTier = "Free",
                        isLiveInCurrentBuild = true
                    )
                )
            ),
            responseByModel = mapOf(
                ModelRouter.MODEL_GEMINI_FLASH to "Gemini Flash answer: Use Repository + StateFlow.",
                ModelRouter.MODEL_GEMINI_FLASH_LITE to "Flash Lite answer: Use ViewModel + Compose."
            )
        )

        val proxyClient = BackendAiProxyClient(tokenProvider = { "fake_token" })
        val openAiProxy = BackendProxyAiProvider(ProviderId.OPENAI_GPT, proxyClient) { "" }

        val registry = ModelCapabilityRegistry(listOf(geminiProvider, openAiProxy))
        val orchestrator = MultiAiOrchestrator(registry, proxyClient)

        val offlineGptModel = BackendProxyAiProvider.defaultModelsFor(ProviderId.OPENAI_GPT, isLive = false).first()
        val comparison = orchestrator.runParallelComparison(
            prompt = "Compare Android state management approaches",
            targetModels = geminiProvider.descriptor.models + offlineGptModel,
            languagePreference = BrainLanguage.EN,
            backendServerUrl = ""
        )

        assertEquals(3, comparison.size)
        val flashRes = comparison.first { it.modelId == ModelRouter.MODEL_GEMINI_FLASH }
        val liteRes = comparison.first { it.modelId == ModelRouter.MODEL_GEMINI_FLASH_LITE }
        val gptRes = comparison.first { it.providerId == ProviderId.OPENAI_GPT }

        assertNull(flashRes.errorMessage)
        assertTrue(flashRes.content.contains("StateFlow"))
        assertNull(liteRes.errorMessage)
        assertNotNull(gptRes.errorMessage)
        assertTrue(gptRes.errorMessage!!.contains("not connected", ignoreCase = true))

        val consensus = orchestrator.synthesizeConsensus(
            originalPrompt = "Compare Android state management approaches",
            responses = comparison,
            languagePreference = BrainLanguage.EN
        )
        assertTrue(consensus.consensusText.isNotBlank())
        assertTrue(consensus.totalEstimatedCostUsdMicros > 0L)
    }

    @Test
    fun refreshExternalProviderStatuses_updatesOnlyConfiguredProvidersOnLiveBackend() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/providers/status") { exchange ->
            val json = """
                {
                  "providers": [
                    {"providerId": "openai_gpt", "connected": true, "statusCode": "CONNECTED_LIVE", "message": "OpenAI live"},
                    {"providerId": "anthropic_claude", "connected": false, "statusCode": "NOT_CONFIGURED_MISSING_KEY", "message": "Missing ANTHROPIC_API_KEY"}
                  ]
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, json.size.toLong())
            exchange.responseBody.use { it.write(json) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val client = BackendAiProxyClient(tokenProvider = { "test_id_token" })
            val gemini = FakeMultiModelProvider(
                AiProviderDescriptor(ProviderId.GOOGLE_GEMINI, "Gemini", ProviderConnectionStatus.CONNECTED_LIVE, "", emptyList()),
                emptyMap()
            )
            val openAi = BackendProxyAiProvider(ProviderId.OPENAI_GPT, client) { baseUrl }
            val claude = BackendProxyAiProvider(ProviderId.ANTHROPIC_CLAUDE, client) { baseUrl }
            val registry = ModelCapabilityRegistry(listOf(gemini, openAi, claude))
            val orchestrator = MultiAiOrchestrator(registry, client)

            val res = orchestrator.refreshExternalProviderStatuses(baseUrl)
            assertTrue(res.isSuccess)
            assertEquals(
                ProviderConnectionStatus.CONNECTED_PROXY,
                registry.catalog.first { it.providerId == ProviderId.OPENAI_GPT }.status
            )
            assertEquals(
                ProviderConnectionStatus.MISSING_API_KEY,
                registry.catalog.first { it.providerId == ProviderId.ANTHROPIC_CLAUDE }.status
            )
            assertTrue(registry.catalog.first { it.providerId == ProviderId.OPENAI_GPT }.models.all { it.isLiveInCurrentBuild })
            assertFalse(registry.catalog.first { it.providerId == ProviderId.ANTHROPIC_CLAUDE }.models.any { it.isLiveInCurrentBuild })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun refreshExternalProviderStatuses_mapsAll7FreeProvidersAndHonestFailureStatuses() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/providers/status") { exchange ->
            val json = """
                {
                  "providers": [
                    {"providerId": "groq", "freeTier": true, "connected": false, "statusCode": "RATE_LIMITED", "keySource": "USER_VAULT", "maskedKeyHint": "gsk_••••1234", "message": "HTTP 429 rate limited", "models": ["llama-3.3-70b-versatile"]},
                    {"providerId": "openrouter_free", "freeTier": true, "connected": true, "statusCode": "CONNECTED_LIVE", "keySource": "USER_VAULT", "maskedKeyHint": "sk-o••••8888", "message": "Live", "models": ["openrouter/free", "deepseek/deepseek-chat-v3:free", "openai/gpt-4.1"]},
                    {"providerId": "mistral", "freeTier": true, "connected": false, "statusCode": "MISSING_API_KEY", "keySource": "NONE", "maskedKeyHint": "", "message": "Missing MISTRAL_API_KEY", "models": ["mistral-small-latest"]},
                    {"providerId": "cohere", "freeTier": true, "connected": false, "statusCode": "QUOTA_EXPIRED", "keySource": "USER_VAULT", "maskedKeyHint": "coh_••••2222", "message": "Quota exhausted", "models": ["command-a-03-2025"]},
                    {"providerId": "cloudflare_ai", "freeTier": true, "connected": false, "statusCode": "OFFLINE", "requiresAccountId": true, "hasAccountId": true, "keySource": "SERVER_ENV", "maskedKeyHint": "cf_t••••7777", "message": "Endpoint offline", "models": ["@cf/meta/llama-3.3-70b-instruct-fp8-fast"]},
                    {"providerId": "nvidia_nim", "freeTier": true, "connected": false, "statusCode": "UNAVAILABLE", "keySource": "SERVER_ENV", "maskedKeyHint": "nvap••••0000", "message": "Service 503", "models": ["meta/llama-3.3-70b-instruct"]},
                    {"providerId": "sambanova", "freeTier": true, "connected": false, "statusCode": "INVALID_API_KEY", "keySource": "USER_VAULT", "maskedKeyHint": "sn_b••••9999", "message": "401 invalid key", "models": ["Meta-Llama-3.3-70B-Instruct"]}
                  ]
                }
            """.trimIndent().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, json.size.toLong())
            exchange.responseBody.use { it.write(json) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val client = BackendAiProxyClient(tokenProvider = { "test_id_token" })
            val providers = ProviderId.externalFreeProviders.map { pid ->
                BackendProxyAiProvider(pid, client) { baseUrl }
            }
            val registry = ModelCapabilityRegistry(providers)
            val orchestrator = MultiAiOrchestrator(registry, client)

            val res = orchestrator.refreshExternalProviderStatuses(baseUrl)
            assertTrue(res.isSuccess)

            val freeCatalog = registry.getFreeTierCatalog()
            assertEquals(ProviderConnectionStatus.RATE_LIMITED, freeCatalog.first { it.providerId == ProviderId.GROQ }.status)
            assertEquals(ProviderConnectionStatus.CONNECTED_PROXY, freeCatalog.first { it.providerId == ProviderId.OPENROUTER_FREE }.status)
            assertEquals(ProviderConnectionStatus.MISSING_API_KEY, freeCatalog.first { it.providerId == ProviderId.MISTRAL }.status)
            assertEquals(ProviderConnectionStatus.QUOTA_EXPIRED, freeCatalog.first { it.providerId == ProviderId.COHERE }.status)
            assertEquals(ProviderConnectionStatus.OFFLINE, freeCatalog.first { it.providerId == ProviderId.CLOUDFLARE_AI }.status)
            assertEquals(ProviderConnectionStatus.UNAVAILABLE, freeCatalog.first { it.providerId == ProviderId.NVIDIA_NIM }.status)
            assertEquals(ProviderConnectionStatus.PROBE_ERROR, freeCatalog.first { it.providerId == ProviderId.SAMBANOVA }.status)

            val openRouterModels = freeCatalog.first { it.providerId == ProviderId.OPENROUTER_FREE }.models.map { it.modelId }
            assertTrue(openRouterModels.contains("deepseek/deepseek-chat-v3:free"))
            assertFalse(openRouterModels.contains("openai/gpt-4.1"))
        } finally {
            server.stop(0)
        }
    }
}

