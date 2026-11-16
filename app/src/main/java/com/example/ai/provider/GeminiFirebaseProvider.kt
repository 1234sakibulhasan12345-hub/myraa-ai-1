package com.example.ai.provider

import android.os.SystemClock
import com.example.core.security.AppCheckHealthStatus
import com.example.core.security.AppCheckRecoveryHelper
import com.example.core.security.appCheckDiagnosticFlow
import com.example.core.security.isAppCheckTokenError
import com.example.data.model.BrainLanguage
import com.example.data.model.TargetFramework
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class GeminiFirebaseProvider(
    private val appCheckRecovery: AppCheckRecoveryHelper = AppCheckRecoveryHelper()
) : AiModelProvider {

    @Volatile
    private var runtimeOverrideStatus: ProviderConnectionStatus? = null

    @Volatile
    private var runtimeOverrideMessage: String? = null

    fun reportRuntimeFailure(throwable: Throwable) {
        val status = classifyProviderError(throwable)
        if (!status.isOperational && status != ProviderConnectionStatus.MISSING_API_KEY) {
            runtimeOverrideStatus = status
            runtimeOverrideMessage = throwable.localizedMessage ?: status.label
        }
    }

    fun clearRuntimeFailure() {
        runtimeOverrideStatus = null
        runtimeOverrideMessage = null
    }

    private fun currentStatusAndNote(): Pair<ProviderConnectionStatus, String> {
        val appCheck = appCheckDiagnosticFlow.value
        val override = runtimeOverrideStatus
        return when {
            appCheck.status == AppCheckHealthStatus.RATE_LIMITED ->
                ProviderConnectionStatus.RATE_LIMITED to appCheck.lastMessage
            appCheck.status == AppCheckHealthStatus.TOKEN_INVALID ->
                ProviderConnectionStatus.PROBE_ERROR to appCheck.lastMessage
            override != null ->
                override to (runtimeOverrideMessage ?: override.label)
            else ->
                ProviderConnectionStatus.CONNECTED_LIVE to
                    "Authenticated via Firebase App Check attestation (Spark Free Tier). Zero client API keys."
        }
    }

    override val descriptor: AiProviderDescriptor
        get() {
            val (status, note) = currentStatusAndNote()
            val modelsLive = status.isOperational
            return AiProviderDescriptor(
                providerId = ProviderId.GOOGLE_GEMINI,
                displayName = "Google Gemini (Firebase AI Logic)",
                status = status,
                securityNote = note,
                isFreeProvider = true,
                keySource = "FIREBASE_APP_CHECK",
                maskedKeyHint = "App Check Verified",
                models = listOf(
                    AiModelDescriptor(
                        modelId = ModelRouter.MODEL_GEMINI_FLASH,
                        providerId = ProviderId.GOOGLE_GEMINI,
                        displayName = "Gemini 3.8 Flash",
                        roleSummary = "Default Master Brain orchestrator for planning, code generation, Bengali/EN/Banglish & vision",
                        capabilities = setOf(
                            ModelCapability.REASONING,
                            ModelCapability.CODING,
                            ModelCapability.MULTILINGUAL_BN_EN,
                            ModelCapability.VISION_MULTIMODAL,
                            ModelCapability.STRUCTURED_JSON,
                            ModelCapability.FAST_CHAT
                        ),
                        latencyTier = "Low-Medium",
                        costTier = "FREE",
                        isLiveInCurrentBuild = modelsLive,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Spark Free Tier (RPM/RPD limits via Firebase AI Logic)"
                    ),
                    AiModelDescriptor(
                        modelId = ModelRouter.MODEL_GEMINI_FLASH_LITE,
                        providerId = ProviderId.GOOGLE_GEMINI,
                        displayName = "Gemini 3.5 Flash-Lite",
                        roleSummary = "Ultra-fast Spark Free model for quick subtasks, classification & rapid fallback",
                        capabilities = setOf(
                            ModelCapability.FAST_CHAT,
                            ModelCapability.MULTILINGUAL_BN_EN,
                            ModelCapability.STRUCTURED_JSON
                        ),
                        latencyTier = "Ultra Fast",
                        costTier = "FREE",
                        isLiveInCurrentBuild = modelsLive,
                        isFreeTier = true,
                        freeTierLimitsSummary = "Spark Free Tier • High-speed lightweight model"
                    ),
                    AiModelDescriptor(
                        modelId = "gemini-4-argon",
                        providerId = ProviderId.GOOGLE_GEMINI,
                        displayName = "Gemini 4 Argon",
                        roleSummary = "Frontier STEM & deep code reasoning tier (requires Blaze billing)",
                        capabilities = setOf(
                            ModelCapability.REASONING,
                            ModelCapability.CODING,
                            ModelCapability.MULTILINGUAL_BN_EN,
                            ModelCapability.STRUCTURED_JSON
                        ),
                        latencyTier = "Standard",
                        costTier = "Blaze Plan Required",
                        isLiveInCurrentBuild = false,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = false
                    ),
                    AiModelDescriptor(
                        modelId = "gemini-nano-banana-2.1",
                        providerId = ProviderId.GOOGLE_GEMINI,
                        displayName = "Gemini Nano Banana 2.1",
                        roleSummary = "In-app image generation & editing capability (requires Blaze billing)",
                        capabilities = setOf(ModelCapability.IMAGE_GEN),
                        latencyTier = "Medium",
                        costTier = "Blaze Plan Required",
                        isLiveInCurrentBuild = false,
                        requiresBillingOrBackendProxy = true,
                        isFreeTier = false
                    )
                )
            )
        }

    private val planSchema: Schema by lazy {
        Schema.obj(
            properties = mapOf(
                "detectedLanguage" to Schema.enumeration(
                    values = listOf("EN", "BN", "BN_EN"),
                    description = "Language of the user prompt: EN, BN (Bengali script), or BN_EN (Bangla-English mixed/Banglish)"
                ),
                "detectedGoal" to Schema.string(
                    description = "Concise 1-sentence summary of the user's true software or task objective"
                ),
                "intentSummary" to Schema.string(
                    description = "Actionable technical interpretation of what must be built or answered"
                ),
                "selectedTaskCategory" to Schema.enumeration(
                    values = listOf(
                        "MASTER_PLANNING",
                        "ARCHITECTURE_DESIGN",
                        "CODE_REASONING",
                        "MULTILINGUAL_CHAT",
                        "VISION_ANALYSIS",
                        "QUICK_SUBTASK"
                    ),
                    description = "Primary category of the request"
                ),
                "recommendedFramework" to Schema.enumeration(
                    values = listOf("ANDROID_COMPOSE", "WEB_APP", "BACKEND_API", "FULLSTACK_SYSTEM"),
                    description = "Target software platform"
                ),
                "assignedAgents" to Schema.array(
                    items = Schema.string(),
                    description = "2 to 5 relevant specialized agent roles from the MYRAA agent roster"
                ),
                "subtasks" to Schema.array(
                    items = Schema.string(),
                    description = "3 to 7 ordered concrete execution subtasks"
                ),
                "architectureNotes" to Schema.string(
                    description = "Concise summary of recommended architecture, security, and data flow"
                ),
                "detectedRisksOrGaps" to Schema.array(
                    items = Schema.string(),
                    description = "Ambiguities, missing requirements, or security considerations to verify"
                )
            )
        )
    }

    private val flashPlanningModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ModelRouter.MODEL_GEMINI_FLASH,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = planSchema
            },
            systemInstruction = content { text(PLANNING_SYSTEM_PROMPT) }
        )
    }

    private val litePlanningModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ModelRouter.MODEL_GEMINI_FLASH_LITE,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = planSchema
            },
            systemInstruction = content { text(PLANNING_SYSTEM_PROMPT) }
        )
    }

    private val flashSynthesisModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ModelRouter.MODEL_GEMINI_FLASH,
            systemInstruction = content { text(SYNTHESIS_SYSTEM_PROMPT) }
        )
    }

    private val liteSynthesisModel: GenerativeModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ModelRouter.MODEL_GEMINI_FLASH_LITE,
            systemInstruction = content { text(SYNTHESIS_SYSTEM_PROMPT) }
        )
    }

    override suspend fun analyzeAndPlan(
        request: BrainGenerationRequest,
        modelId: String
    ): StructuredOrchestrationPlan {
        val model = when (modelId) {
            ModelRouter.MODEL_GEMINI_FLASH_LITE -> litePlanningModel
            else -> flashPlanningModel
        }

        val planningContent: Content = content {
            request.attachedBitmap?.let { bitmap -> image(bitmap) }
            text(buildPlanningPrompt(request))
        }

        val response = appCheckRecovery.runWithSingleRetry("Gemini planning ($modelId)") {
            withTimeout(AI_TIMEOUT_MS) { model.generateContent(planningContent) }
        }
        val jsonText = response.text
            ?: throw IllegalStateException("Master AI Brain returned an empty structured plan.")

        return parsePlanJson(jsonText)
    }

    override fun streamSynthesizedResponse(
        request: BrainGenerationRequest,
        plan: StructuredOrchestrationPlan,
        modelId: String
    ): Flow<String> = flow {
        val model = when (modelId) {
            ModelRouter.MODEL_GEMINI_FLASH_LITE -> liteSynthesisModel
            else -> flashSynthesisModel
        }

        val synthesisContent: Content = content {
            request.attachedBitmap?.let { bitmap -> image(bitmap) }
            text(buildSynthesisPrompt(request, plan))
        }

        val accumulated = StringBuilder()
        var emittedAnyChunk = false

        suspend fun collectStreamOnce() {
            withTimeout(AI_TIMEOUT_MS) {
                model.generateContentStream(synthesisContent).collect { chunk ->
                    val chunkText = chunk.text.orEmpty()
                    if (chunkText.isNotEmpty()) {
                        emittedAnyChunk = true
                        accumulated.append(chunkText)
                        emit(accumulated.toString())
                    }
                }
            }
        }

        try {
            appCheckRecovery.ensureNotRateLimited("Gemini stream ($modelId)")
            collectStreamOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!emittedAnyChunk && isAppCheckTokenError(e)) {
                appCheckRecovery.recoverAfterAppCheckError(
                    operationName = "Gemini stream ($modelId)",
                    originalError = e
                ) {
                    collectStreamOnce()
                }
            } else {
                throw e
            }
        }

        if (accumulated.isBlank()) {
            throw IllegalStateException("Model completed without generating text output.")
        }
    }

    override suspend fun generateRawText(
        prompt: String,
        systemPrompt: String,
        modelId: String,
        backendServerUrl: String
    ): ProviderGenerationOutput {
        val targetModelId = when (modelId) {
            ModelRouter.MODEL_GEMINI_FLASH_LITE -> ModelRouter.MODEL_GEMINI_FLASH_LITE
            else -> ModelRouter.MODEL_GEMINI_FLASH
        }
        val model = Firebase.ai.generativeModel(
            modelName = targetModelId,
            systemInstruction = content { text(systemPrompt) }
        )
        val startedAt = SystemClock.elapsedRealtime()
        val response = appCheckRecovery.runWithSingleRetry("Gemini generation ($targetModelId)") {
            withTimeout(AI_TIMEOUT_MS) { model.generateContent(prompt) }
        }
        val text = response.text
            ?: throw IllegalStateException("Gemini returned an empty response.")
        val latencyMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
        val pTokens = TokenAndCostTracker.estimateTokens(systemPrompt + "\n" + prompt)
        val cTokens = TokenAndCostTracker.estimateTokens(text)
        return ProviderGenerationOutput(
            text = text,
            promptTokens = pTokens,
            completionTokens = cTokens,
            latencyMs = latencyMs,
            estimatedCostUsdMicros = TokenAndCostTracker.computeCostUsdMicros(targetModelId, pTokens, cTokens)
        )
    }

    fun buildStructuredModel(
        modelId: String = ModelRouter.MODEL_GEMINI_FLASH,
        schema: Schema,
        systemPrompt: String
    ): GenerativeModel {
        return Firebase.ai.generativeModel(
            modelName = modelId,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = schema
            },
            systemInstruction = content { text(systemPrompt) }
        )
    }

    private fun parsePlanJson(rawJson: String): StructuredOrchestrationPlan {
        val json = JSONObject(rawJson)
        val lang = BrainLanguage.fromWire(json.optString("detectedLanguage", "EN"))
        val detectedGoal = json.optString("detectedGoal", "Analyze user request")
        val intentSummary = json.optString("intentSummary", detectedGoal)
        val taskCategory = TaskCategory.fromWire(json.optString("selectedTaskCategory", "MASTER_PLANNING"))
        val framework = TargetFramework.fromWire(json.optString("recommendedFramework", "ANDROID_COMPOSE"))
        val agents = json.optJSONArray("assignedAgents")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { it.isNotBlank() } }
        }.orEmpty().ifEmpty { listOf("Master Planner Agent") }
        val subtasks = json.optJSONArray("subtasks")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { it.isNotBlank() } }
        }.orEmpty()
        val architectureNotes = json.optString("architectureNotes", "")
        val risks = json.optJSONArray("detectedRisksOrGaps")?.let { arr ->
            (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { it.isNotBlank() } }
        }.orEmpty()

        return StructuredOrchestrationPlan(
            detectedLanguage = lang,
            detectedGoal = detectedGoal,
            intentSummary = intentSummary,
            selectedTaskCategory = taskCategory,
            recommendedFramework = framework,
            assignedAgents = agents,
            subtasks = subtasks,
            architectureNotes = architectureNotes,
            detectedRisksOrGaps = risks
        )
    }

    private fun buildPlanningPrompt(request: BrainGenerationRequest): String {
        val historyBlock = if (request.recentHistory.isEmpty()) {
            "None"
        } else {
            request.recentHistory.takeLast(6).joinToString("\n") { msg ->
                "[${msg.role}] ${msg.content.take(350)}"
            }
        }

        return """
            |User Language Preference: ${request.languagePreference.wireValue}
            |Active Project Memory:
            |${request.projectContextSummary}
            |
            |Recent Conversation Turns:
            |$historyBlock
            |
            |Current User Directive:
            |${request.prompt}
        """.trimMargin()
    }

    private fun buildSynthesisPrompt(
        request: BrainGenerationRequest,
        plan: StructuredOrchestrationPlan
    ): String {
        val responseLangInstruction = when (request.languagePreference) {
            BrainLanguage.BN -> "Respond clearly in natural Bengali (বাংলা), keeping technical identifiers accurate."
            BrainLanguage.BN_EN -> "Respond in clear, developer-friendly Bangla-English (Benglish) matching the user's conversational mix."
            BrainLanguage.EN -> "Respond clearly in English."
            BrainLanguage.AUTO -> when (plan.detectedLanguage) {
                BrainLanguage.BN -> "Match the user's language and respond in natural Bengali (বাংলা) with accurate technical terms."
                BrainLanguage.BN_EN -> "Match the user's Bangla-English style naturally while keeping technical architecture crisp."
                else -> "Respond clearly in English."
            }
        }

        return """
            |$responseLangInstruction
            |
            |Platform Grounding Rule:
            |You are running inside MYRAA AI SUPER BRAIN (Multi-AI Platform, Coding Agent, App Builder Studio & Build Server client).
            |Live right now: Google Gemini via Firebase AI Logic + App Check, Coding Agent file generation + static analysis + version history, Android App Builder Studio scaffolding, and remote APK Build Server client. External providers (OpenAI GPT, Anthropic Claude, DeepSeek, Meta Llama) run ONLY when the user configures their own deployed backend server with valid API keys.
            |Never fabricate compilation success or unverified APK downloads.
            |
            |Project Context:
            |${request.projectContextSummary}
            |
            |Orchestrated Plan Summary:
            |- Goal: ${plan.detectedGoal}
            |- Framework: ${plan.recommendedFramework.displayLabel}
            |- Agents Assigned: ${plan.assignedAgents.joinToString(", ")}
            |- Subtasks: ${plan.subtasks.joinToString(" | ")}
            |- Risks/Gaps: ${plan.detectedRisksOrGaps.joinToString(" | ").ifBlank { "None noted" }}
            |
            |User Prompt:
            |${request.prompt}
        """.trimMargin()
    }

    companion object {
        private const val AI_TIMEOUT_MS = 60_000L

        private const val PLANNING_SYSTEM_PROMPT =
            "You are the Master Planner Agent of MYRAA AI SUPER BRAIN. " +
                "Understand prompts written in Bengali (বাংলা), English, or Bangla-English (Benglish). " +
                "Extract the user's true software goal, select the target framework, decompose complex tasks into concrete subtasks, " +
                "assign appropriate MYRAA agent roles, and detect ambiguities or security risks. " +
                "Never claim multiple external AI models' weights were merged."

        private const val SYNTHESIS_SYSTEM_PROMPT =
            "You are MYRAA AI SUPER BRAIN, a senior software architect and multi-agent orchestrator. " +
                "Deliver clear, practical, security-first technical guidance, architecture breakdowns, and implementation steps."
    }
}
