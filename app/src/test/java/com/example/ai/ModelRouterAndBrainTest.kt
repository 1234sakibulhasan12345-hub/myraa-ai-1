package com.example.ai

import com.example.ai.brain.MasterAiBrain
import com.example.ai.provider.AiModelDescriptor
import com.example.ai.provider.AiModelProvider
import com.example.ai.provider.AiProviderDescriptor
import com.example.ai.provider.BrainGenerationRequest
import com.example.ai.provider.BrainStreamEvent
import com.example.ai.provider.ModelCapability
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.ModelRouter
import com.example.ai.provider.ProviderConnectionStatus
import com.example.ai.provider.ProviderId
import com.example.ai.provider.StructuredOrchestrationPlan
import com.example.ai.provider.TaskCategory
import com.example.data.model.BrainActivityPhase
import com.example.data.model.BrainLanguage
import com.example.data.model.BrainMessage
import com.example.data.model.BrainProject
import com.example.data.model.MessageRole
import com.example.data.model.ProjectStatus
import com.example.data.model.RoutingPolicy
import com.example.data.model.TargetFramework
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [36])
class ModelRouterAndBrainTest {

    private class FakeGeminiProvider(
        private val failPrimaryModelId: String? = null
    ) : AiModelProvider {
        val planningCalls = mutableListOf<String>()
        val streamingCalls = mutableListOf<String>()

        override val descriptor: AiProviderDescriptor = AiProviderDescriptor(
            providerId = ProviderId.GOOGLE_GEMINI,
            displayName = "Google Gemini (Test)",
            status = ProviderConnectionStatus.CONNECTED_LIVE,
            securityNote = "Test descriptor",
            isFreeProvider = true,
            models = listOf(
                AiModelDescriptor(
                    modelId = ModelRouter.MODEL_GEMINI_FLASH,
                    providerId = ProviderId.GOOGLE_GEMINI,
                    displayName = "Gemini 3.8 Flash",
                    roleSummary = "Primary orchestrator",
                    capabilities = setOf(
                        ModelCapability.REASONING,
                        ModelCapability.CODING,
                        ModelCapability.MULTILINGUAL_BN_EN,
                        ModelCapability.VISION_MULTIMODAL
                    ),
                    latencyTier = "Low-Medium",
                    costTier = "Spark Free Tier Eligible",
                    isLiveInCurrentBuild = true,
                    isFreeTier = true
                ),
                AiModelDescriptor(
                    modelId = ModelRouter.MODEL_GEMINI_FLASH_LITE,
                    providerId = ProviderId.GOOGLE_GEMINI,
                    displayName = "Gemini 3.5 Flash-Lite",
                    roleSummary = "Fast fallback",
                    capabilities = setOf(
                        ModelCapability.FAST_CHAT,
                        ModelCapability.MULTILINGUAL_BN_EN
                    ),
                    latencyTier = "Ultra Fast",
                    costTier = "Spark Free Tier Eligible",
                    isLiveInCurrentBuild = true,
                    isFreeTier = true
                ),
                AiModelDescriptor(
                    modelId = "gemini-4-argon",
                    providerId = ProviderId.GOOGLE_GEMINI,
                    displayName = "Gemini 4 Argon",
                    roleSummary = "Planned paid tier",
                    capabilities = setOf(ModelCapability.REASONING),
                    latencyTier = "Standard",
                    costTier = "Blaze Plan Required",
                    isLiveInCurrentBuild = false,
                    requiresBillingOrBackendProxy = true,
                    isFreeTier = false
                )
            )
        )

        override suspend fun analyzeAndPlan(
            request: BrainGenerationRequest,
            modelId: String
        ): StructuredOrchestrationPlan {
            planningCalls.add(modelId)
            if (modelId == failPrimaryModelId) {
                throw IllegalStateException("Simulated primary model outage")
            }
            return StructuredOrchestrationPlan(
                detectedLanguage = BrainLanguage.BN_EN,
                detectedGoal = "Build Android messaging architecture",
                intentSummary = "Design Google login and Firestore realtime messaging flow",
                selectedTaskCategory = TaskCategory.MASTER_PLANNING,
                recommendedFramework = TargetFramework.ANDROID_COMPOSE,
                assignedAgents = listOf("Master Planner Agent", "Android App Developer Agent"),
                subtasks = listOf("Design schema", "Wire auth", "Implement UI"),
                architectureNotes = "Clean MVVM + Firestore",
                detectedRisksOrGaps = listOf("Push notifications arrive in later phase")
            )
        }

        override fun streamSynthesizedResponse(
            request: BrainGenerationRequest,
            plan: StructuredOrchestrationPlan,
            modelId: String
        ): Flow<String> = flow {
            streamingCalls.add(modelId)
            emit("Chunk 1")
            emit("Chunk 1 Chunk 2")
        }
    }

    @Test
    fun registry_exposesLiveGeminiAndPlannedPart02Blueprints() {
        val registry = ModelCapabilityRegistry(listOf(FakeGeminiProvider()))

        val liveModels = registry.getAllLiveModels()
        assertEquals(2, liveModels.size)
        assertTrue(liveModels.all { it.isLiveInCurrentBuild })

        val plannedProviders = registry.catalog.filter {
            it.status == ProviderConnectionStatus.PLANNED_PART_02
        }
        assertEquals(4, plannedProviders.size)
        assertTrue(
            plannedProviders.map { it.providerId }.containsAll(
                listOf(
                    ProviderId.ANTHROPIC_CLAUDE,
                    ProviderId.OPENAI_GPT,
                    ProviderId.DEEPSEEK,
                    ProviderId.META_LLAMA
                )
            )
        )
    }

    @Test
    fun router_routesBengaliPlanningPromptToFlashAndUltraFastToLite() {
        val registry = ModelCapabilityRegistry(listOf(FakeGeminiProvider()))
        val router = ModelRouter(registry)

        val banglaPlanDecision = router.selectModel(
            BrainGenerationRequest(
                prompt = "একটি complete Android অ্যাপ তৈরি করো Firestore chat সহ",
                languagePreference = BrainLanguage.BN,
                routingPolicy = RoutingPolicy.AUTO_BALANCED,
                projectContextSummary = "",
                recentHistory = emptyList()
            )
        )
        assertEquals(ProviderId.GOOGLE_GEMINI, banglaPlanDecision.selectedProvider)
        assertEquals(ModelRouter.MODEL_GEMINI_FLASH, banglaPlanDecision.selectedModel.modelId)
        assertEquals(ModelRouter.MODEL_GEMINI_FLASH_LITE, banglaPlanDecision.fallbackModelId)
        assertEquals(TaskCategory.MASTER_PLANNING, banglaPlanDecision.taskCategory)

        val ultraFastDecision = router.selectModel(
            BrainGenerationRequest(
                prompt = "Summarize architecture",
                languagePreference = BrainLanguage.EN,
                routingPolicy = RoutingPolicy.ULTRA_FAST,
                projectContextSummary = "",
                recentHistory = emptyList()
            )
        )
        assertEquals(ModelRouter.MODEL_GEMINI_FLASH_LITE, ultraFastDecision.selectedModel.modelId)
        assertEquals(ModelRouter.MODEL_GEMINI_FLASH, ultraFastDecision.fallbackModelId)

        val quickAutoDecision = router.selectModel(
            BrainGenerationRequest(
                prompt = "Hi Myraa",
                languagePreference = BrainLanguage.AUTO,
                routingPolicy = RoutingPolicy.AUTO_BALANCED,
                projectContextSummary = "",
                recentHistory = emptyList()
            )
        )
        assertEquals(TaskCategory.QUICK_SUBTASK, quickAutoDecision.taskCategory)
        assertEquals(ModelRouter.MODEL_GEMINI_FLASH_LITE, quickAutoDecision.selectedModel.modelId)

        val archDecision = router.selectModel(
            BrainGenerationRequest(
                prompt = "Design the Firestore schema and security rules",
                languagePreference = BrainLanguage.EN,
                routingPolicy = RoutingPolicy.AUTO_BALANCED,
                projectContextSummary = "",
                recentHistory = emptyList()
            )
        )
        assertEquals(TaskCategory.ARCHITECTURE_DESIGN, archDecision.taskCategory)
        assertEquals(ModelRouter.MODEL_GEMINI_FLASH, archDecision.selectedModel.modelId)
    }

    @Test
    fun masterAiBrain_executesPhasesAndFallsBackOnPrimaryFailure() = runBlocking {
        val fakeProvider = FakeGeminiProvider(failPrimaryModelId = ModelRouter.MODEL_GEMINI_FLASH)
        val registry = ModelCapabilityRegistry(listOf(fakeProvider))
        val router = ModelRouter(registry)
        val brain = MasterAiBrain(registry, router)

        val observedPhases = mutableListOf<BrainActivityPhase>()
        val events = brain.execute(
            request = BrainGenerationRequest(
                prompt = "Create an Android chat app plan in Banglish",
                languagePreference = BrainLanguage.BN_EN,
                routingPolicy = RoutingPolicy.QUALITY_DEFAULT,
                projectContextSummary = "Project Name: Myraa Chat",
                recentHistory = emptyList()
            ),
            onPhaseChanged = { observedPhases.add(it) }
        ).toList()

        assertEquals(
            listOf(
                ModelRouter.MODEL_GEMINI_FLASH,
                ModelRouter.MODEL_GEMINI_FLASH_LITE
            ),
            fakeProvider.planningCalls
        )
        assertTrue(events.any { it is BrainStreamEvent.PlanReady })
        val completed = events.filterIsInstance<BrainStreamEvent.Completed>().single()
        assertEquals("Chunk 1 Chunk 2", completed.fullText)
        assertEquals(
            listOf(
                BrainActivityPhase.ANALYZING_INTENT,
                BrainActivityPhase.SELECTING_MODEL,
                BrainActivityPhase.PLANNING_SUBTASKS,
                BrainActivityPhase.COORDINATING_AGENTS,
                BrainActivityPhase.STREAMING_RESPONSE,
                BrainActivityPhase.READY
            ),
            observedPhases
        )
    }

    @Test
    fun buildProjectMemoryContext_includesProjectDetailsOrEmptyFallback() {
        val fakeProvider = FakeGeminiProvider()
        val registry = ModelCapabilityRegistry(listOf(fakeProvider))
        val brain = MasterAiBrain(registry, ModelRouter(registry))

        val emptyContext = brain.buildProjectMemoryContext(null, emptyList())
        assertTrue(emptyContext.contains("No active project selected"))

        val project = BrainProject(
            id = "proj_1",
            userId = "user_1",
            name = "Myraa Commerce",
            targetFramework = TargetFramework.ANDROID_COMPOSE.wireValue,
            requirementsSummary = "Multi-vendor storefront with Bangla UI",
            architectureSummary = "MVVM + Firestore",
            completedFeatures = listOf("Auth Gate"),
            pendingFeatures = listOf("Catalog UI"),
            status = ProjectStatus.ACTIVE.wireValue
        )
        val msg = BrainMessage(
            id = "m1",
            role = MessageRole.USER.wireValue,
            content = "Start catalog"
        )
        val populated = brain.buildProjectMemoryContext(project, listOf(msg))
        assertTrue(populated.contains("Myraa Commerce"))
        assertTrue(populated.contains("Multi-vendor storefront with Bangla UI"))
        assertTrue(populated.contains("Auth Gate"))
        assertTrue(populated.contains("Catalog UI"))
        assertFalse(populated.contains("No active project selected"))
    }

    @Test
    fun freeTierCatalog_excludesAllPaidModelsAndRequiresKeysForExternalFreeProviders() {
        val fakeGemini = FakeGeminiProvider()
        val client = com.example.ai.provider.BackendAiProxyClient(tokenProvider = { "tok" })
        val providers = listOf(fakeGemini) + ProviderId.externalFreeProviders.map { pid ->
            com.example.ai.provider.BackendProxyAiProvider(pid, client) { "" }
        }
        val registry = ModelCapabilityRegistry(providers)

        val freeCatalog = registry.getFreeTierCatalog()
        assertEquals(8, freeCatalog.size)
        val freeModelIds = freeCatalog.flatMap { it.models }.map { it.modelId }
        assertFalse(freeModelIds.contains("gemini-4-argon"))
        assertFalse(freeModelIds.contains("gemini-nano-banana-2.1"))
        assertFalse(freeCatalog.any { !it.providerId.isFreeProvider })
        assertTrue(freeCatalog.flatMap { it.models }.all { it.isFreeTier })

        val externalFree = freeCatalog.filter { it.providerId != ProviderId.GOOGLE_GEMINI }
        assertTrue(externalFree.all { it.status == ProviderConnectionStatus.MISSING_API_KEY })
        assertTrue(externalFree.flatMap { it.models }.none { it.isLiveInCurrentBuild })
    }

    @Test
    fun masterAiBrain_autoFallsBackFromRateLimitedFreeProviderToHealthyFreeProvider() = runBlocking {
        val groqProvider = object : AiModelProvider {
            override val descriptor: AiProviderDescriptor
                get() = groqDesc
            var groqDesc = AiProviderDescriptor(
                providerId = ProviderId.GROQ,
                displayName = ProviderId.GROQ.displayName,
                status = ProviderConnectionStatus.CONNECTED_PROXY,
                securityNote = "Live",
                isFreeProvider = true,
                models = listOf(
                    AiModelDescriptor(
                        modelId = "llama-3.3-70b-versatile",
                        providerId = ProviderId.GROQ,
                        displayName = "Llama 3.3 70B Versatile",
                        roleSummary = "Fast",
                        capabilities = setOf(ModelCapability.REASONING, ModelCapability.CODING),
                        latencyTier = "Ultra Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = true,
                        isFreeTier = true
                    )
                )
            )

            override suspend fun analyzeAndPlan(request: BrainGenerationRequest, modelId: String): StructuredOrchestrationPlan {
                throw com.example.ai.provider.ProviderHttpException(429, "RATE_LIMITED", "Groq 429 rate limit")
            }

            override fun streamSynthesizedResponse(request: BrainGenerationRequest, plan: StructuredOrchestrationPlan, modelId: String): Flow<String> = flow {}
        }

        val sambanovaProvider = object : AiModelProvider {
            override val descriptor: AiProviderDescriptor = AiProviderDescriptor(
                providerId = ProviderId.SAMBANOVA,
                displayName = ProviderId.SAMBANOVA.displayName,
                status = ProviderConnectionStatus.CONNECTED_PROXY,
                securityNote = "Live",
                isFreeProvider = true,
                models = listOf(
                    AiModelDescriptor(
                        modelId = "Meta-Llama-3.3-70B-Instruct",
                        providerId = ProviderId.SAMBANOVA,
                        displayName = "Meta Llama 3.3 70B (SN)",
                        roleSummary = "Fallback",
                        capabilities = setOf(ModelCapability.REASONING, ModelCapability.CODING),
                        latencyTier = "Ultra Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = true,
                        isFreeTier = true
                    )
                )
            )

            override suspend fun analyzeAndPlan(request: BrainGenerationRequest, modelId: String): StructuredOrchestrationPlan {
                return StructuredOrchestrationPlan(
                    detectedLanguage = BrainLanguage.EN,
                    detectedGoal = "Build app via SambaNova fallback",
                    intentSummary = "Fallback plan",
                    selectedTaskCategory = TaskCategory.MASTER_PLANNING,
                    recommendedFramework = TargetFramework.ANDROID_COMPOSE,
                    assignedAgents = listOf("Planner Agent"),
                    subtasks = listOf("Step 1"),
                    architectureNotes = "MVVM",
                    detectedRisksOrGaps = emptyList()
                )
            }

            override fun streamSynthesizedResponse(request: BrainGenerationRequest, plan: StructuredOrchestrationPlan, modelId: String): Flow<String> =
                flow { emit("SambaNova auto-fallback output OK") }
        }

        val registry = ModelCapabilityRegistry(listOf(groqProvider, sambanovaProvider))
        val router = ModelRouter(registry)
        val brain = MasterAiBrain(registry, router)

        val events = brain.execute(
            request = BrainGenerationRequest(
                prompt = "Build Android app",
                languagePreference = BrainLanguage.EN,
                routingPolicy = RoutingPolicy.AUTO_BALANCED,
                projectContextSummary = "",
                recentHistory = emptyList(),
                preferredProviderId = ProviderId.GROQ
            ),
            onPhaseChanged = {}
        ).toList()

        val planReady = events.filterIsInstance<BrainStreamEvent.PlanReady>().single()
        assertEquals(ProviderId.SAMBANOVA, planReady.routing.selectedProvider)
        assertTrue(planReady.routing.rationale.contains("Fallback", ignoreCase = true))
        val completed = events.filterIsInstance<BrainStreamEvent.Completed>().single()
        assertEquals("SambaNova auto-fallback output OK", completed.fullText)
        assertEquals(0L, completed.estimatedCostUsdMicros)
    }
}

