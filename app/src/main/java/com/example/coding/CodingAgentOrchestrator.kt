package com.example.coding

import android.os.SystemClock
import com.example.ai.provider.AiModelDescriptor
import com.example.ai.provider.GeminiFirebaseProvider
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.ModelRouter
import com.example.ai.provider.ProviderId
import com.example.ai.provider.TokenAndCostTracker
import com.example.core.security.AppCheckDiagnosticException
import com.example.core.security.AppCheckRecoveryHelper
import com.example.core.security.isAppCheckTokenError
import com.example.data.model.BrainLanguage
import com.example.data.model.BrainProject
import com.example.data.model.ProjectFile
import com.example.data.repository.SuperBrainRepository
import com.google.firebase.ai.type.Schema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class CodingSubtaskAssignment(
    val subtaskTitle: String,
    val assignedAgent: String,
    val assignedModelId: String,
    val providerId: ProviderId,
    val status: String
)

data class CodingExecutionResult(
    val summary: String,
    val planSteps: List<CodingSubtaskAssignment>,
    val updatedFiles: List<ProjectFile>,
    val mergedProjectFiles: List<ProjectFile>,
    val staticAnalysis: StaticAnalysisReport,
    val usedModelId: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val estimatedCostUsdMicros: Long,
    val latencyMs: Long
)

class CodingAgentOrchestrator(
    private val registry: ModelCapabilityRegistry,
    private val appCheckRecovery: AppCheckRecoveryHelper = AppCheckRecoveryHelper()
) {
    private val codeGenSchema: Schema by lazy {
        Schema.obj(
            properties = mapOf(
                "technicalPlanSummary" to Schema.string(
                    description = "Concise technical summary of the changes"
                ),
                "subtasks" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "title" to Schema.string(),
                            "agent" to Schema.string()
                        )
                    ),
                    description = "Ordered engineering subtasks and agent roles"
                ),
                "files" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "path" to Schema.string(description = "Relative POSIX workspace file path"),
                            "content" to Schema.string(description = "Complete file contents without markdown fences")
                        )
                    ),
                    description = "1 to 6 created or updated project files"
                )
            )
        )
    }

    suspend fun executeCodingDirective(
        directive: String,
        project: BrainProject,
        existingFiles: List<ProjectFile>,
        language: BrainLanguage,
        preferredModel: AiModelDescriptor? = null,
        backendServerUrl: String = ""
    ): CodingExecutionResult {
        val startedAt = SystemClock.elapsedRealtime()
        val liveModels = registry.getAllLiveModels()
        val chosenModel = preferredModel?.takeIf { it.isLiveInCurrentBuild }
            ?: liveModels.firstOrNull { it.modelId == ModelRouter.MODEL_GEMINI_FLASH }
            ?: liveModels.first()

        val prompt = buildCodingPrompt(directive, project, existingFiles, language)
        val systemPrompt =
            "You are the MYRAA Multi-AI Coding Agent (Kotlin, Jetpack Compose, Gradle KTS, Web, Backend, Firestore). " +
                "Generate clean, complete, compilable source files—never truncated stubs, never hardcoded API keys, " +
                "and strictly follow Android Compose + Material 3 best practices."

        val rawJson = invokeStructuredOrFallbackModel(chosenModel, systemPrompt, prompt, backendServerUrl)
        val parsed = parseGeneratedFilesJson(rawJson, existingFiles, chosenModel, agentName = "Coding Agent")

        val mergedAfterInitial = mergeFiles(existingFiles, parsed.files)
        val report = KotlinAndGradleStaticAnalyzer.analyze(mergedAfterInitial)

        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
        val pTokens = TokenAndCostTracker.estimateTokens(prompt)
        val cTokens = TokenAndCostTracker.estimateTokens(rawJson)

        return CodingExecutionResult(
            summary = parsed.summary,
            planSteps = parsed.subtasks.ifEmpty {
                listOf(
                    CodingSubtaskAssignment(
                        subtaskTitle = directive.take(90),
                        assignedAgent = "Coding Agent",
                        assignedModelId = chosenModel.modelId,
                        providerId = chosenModel.providerId,
                        status = "Completed"
                    )
                )
            },
            updatedFiles = parsed.files,
            mergedProjectFiles = mergedAfterInitial,
            staticAnalysis = report,
            usedModelId = chosenModel.modelId,
            promptTokens = pTokens,
            completionTokens = cTokens,
            estimatedCostUsdMicros = TokenAndCostTracker.computeCostUsdMicros(chosenModel.modelId, pTokens, cTokens),
            latencyMs = elapsed
        )
    }

    suspend fun runAutoBugFixPass(
        project: BrainProject,
        existingFiles: List<ProjectFile>,
        errorLogsOrReport: String,
        preferredModel: AiModelDescriptor? = null,
        backendServerUrl: String = ""
    ): CodingExecutionResult {
        val startedAt = SystemClock.elapsedRealtime()
        val liveModels = registry.getAllLiveModels()
        val chosenModel = preferredModel?.takeIf { it.isLiveInCurrentBuild }
            ?: liveModels.firstOrNull { it.modelId == ModelRouter.MODEL_GEMINI_FLASH }
            ?: liveModels.first()

        val relevantFiles = existingFiles.take(10).joinToString("\n\n") { f ->
            "FILE: ${f.path}\n```\n${f.content.take(3500)}\n```"
        }

        val prompt = """
            |Fix ONLY the broken or misconfigured files in project '${project.name}' (${project.packageName}).
            |
            |Reported Errors / Build Failure Logs:
            |${errorLogsOrReport.take(5000)}
            |
            |Current Workspace Files:
            |$relevantFiles
            |
            |Return JSON with keys: technicalPlanSummary, subtasks (title, agent), and files (path, content) containing the complete fixed contents of modified files.
        """.trimMargin()

        val systemPrompt =
            "You are the MYRAA Bug Fixer Agent. Fix syntax errors, missing imports, manifest/Gradle errors, and Compose bugs precisely while preserving existing functionality."

        val rawJson = invokeStructuredOrFallbackModel(chosenModel, systemPrompt, prompt, backendServerUrl)
        val parsed = parseGeneratedFilesJson(rawJson, existingFiles, chosenModel, agentName = "Bug Fixer Agent")
        val merged = mergeFiles(existingFiles, parsed.files)
        val report = KotlinAndGradleStaticAnalyzer.analyze(merged)

        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
        val pTokens = TokenAndCostTracker.estimateTokens(prompt)
        val cTokens = TokenAndCostTracker.estimateTokens(rawJson)

        return CodingExecutionResult(
            summary = parsed.summary.ifBlank { "Applied Bug Fixer patch across ${parsed.files.size} file(s)." },
            planSteps = parsed.subtasks,
            updatedFiles = parsed.files,
            mergedProjectFiles = merged,
            staticAnalysis = report,
            usedModelId = chosenModel.modelId,
            promptTokens = pTokens,
            completionTokens = cTokens,
            estimatedCostUsdMicros = TokenAndCostTracker.computeCostUsdMicros(chosenModel.modelId, pTokens, cTokens),
            latencyMs = elapsed
        )
    }

    private suspend fun invokeStructuredOrFallbackModel(
        chosenModel: AiModelDescriptor,
        systemPrompt: String,
        prompt: String,
        backendServerUrl: String
    ): String {
        val candidateQueue = buildList {
            add(chosenModel)
            registry.getAllLiveFreeModels().forEach { freeModel ->
                if (freeModel.providerId != chosenModel.providerId || freeModel.modelId != chosenModel.modelId) {
                    add(freeModel)
                }
            }
        }

        var lastError: Exception? = null
        for ((index, candidate) in candidateQueue.withIndex()) {
            val provider = registry.getLiveProvider(candidate.providerId) ?: continue
            try {
                return if (provider is GeminiFirebaseProvider) {
                    val structuredModel = provider.buildStructuredModel(
                        modelId = candidate.modelId,
                        schema = codeGenSchema,
                        systemPrompt = systemPrompt
                    )
                    appCheckRecovery.runWithSingleRetry("Coding Agent (${candidate.modelId})") {
                        withTimeout(AI_TIMEOUT_MS) {
                            structuredModel.generateContent(prompt).text
                                ?: throw IllegalStateException("Gemini coding agent returned empty JSON.")
                        }
                    }
                } else {
                    withTimeout(AI_TIMEOUT_MS) {
                        provider.generateRawText(
                            prompt = "$prompt\n\nOutput MUST be valid raw JSON matching: {\"technicalPlanSummary\": string, \"subtasks\": [{\"title\": string, \"agent\": string}], \"files\": [{\"path\": string, \"content\": string}]}",
                            systemPrompt = systemPrompt,
                            modelId = candidate.modelId,
                            backendServerUrl = backendServerUrl
                        ).text
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (err: Exception) {
                val isAppCheckErr = err is AppCheckDiagnosticException ||
                    (provider.descriptor.providerId == ProviderId.GOOGLE_GEMINI && isAppCheckTokenError(err))
                val hasAnotherProvider = candidateQueue.drop(index + 1).any { it.providerId != ProviderId.GOOGLE_GEMINI }
                if (isAppCheckErr && !hasAnotherProvider) {
                    throw err
                }
                if (!isAppCheckErr) {
                    registry.reportProviderFailure(candidate.providerId, err)
                }
                lastError = err
            }
        }
        throw (lastError ?: IllegalStateException("No live AI model available for coding request."))
    }

    private data class ParsedCodingJson(
        val summary: String,
        val subtasks: List<CodingSubtaskAssignment>,
        val files: List<ProjectFile>
    )

    private fun parseGeneratedFilesJson(
        rawJson: String,
        existingFiles: List<ProjectFile>,
        chosenModel: AiModelDescriptor,
        agentName: String
    ): ParsedCodingJson {
        val clean = rawJson.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val json = JSONObject(clean)
        val summary = json.optString("technicalPlanSummary", "Generated workspace code changes.")

        val subtaskArr = json.optJSONArray("subtasks") ?: JSONArray()
        val subtasks = (0 until subtaskArr.length()).mapNotNull { idx ->
            val obj = subtaskArr.optJSONObject(idx) ?: return@mapNotNull null
            val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val agent = obj.optString("agent", agentName).ifBlank { agentName }
            CodingSubtaskAssignment(
                subtaskTitle = title,
                assignedAgent = agent,
                assignedModelId = chosenModel.modelId,
                providerId = chosenModel.providerId,
                status = "Done"
            )
        }

        val filesArr = json.optJSONArray("files") ?: JSONArray()
        val existingByPath = existingFiles.associateBy { it.path }
        val generated = (0 until filesArr.length()).mapNotNull { idx ->
            val obj = filesArr.optJSONObject(idx) ?: return@mapNotNull null
            val rawPath = obj.optString("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cleanPath = SuperBrainRepository.sanitizeRelativePath(rawPath)
            val content = obj.optString("content", "")
            if (content.isBlank()) return@mapNotNull null
            ProjectFile(
                id = existingByPath[cleanPath]?.id ?: SuperBrainRepository.fileIdForPath(cleanPath),
                path = cleanPath,
                fileName = cleanPath.substringAfterLast('/'),
                language = SuperBrainRepository.inferLanguageFromPath(cleanPath),
                content = content,
                sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                checksumSha256 = SuperBrainRepository.sha256Hex(content),
                updatedByAgent = "$agentName (${chosenModel.displayName})"
            )
        }

        return ParsedCodingJson(summary = summary, subtasks = subtasks, files = generated)
    }

    private fun buildCodingPrompt(
        directive: String,
        project: BrainProject,
        existingFiles: List<ProjectFile>,
        language: BrainLanguage
    ): String {
        val fileSummary = if (existingFiles.isEmpty()) {
            "Workspace has no files yet."
        } else {
            existingFiles.take(12).joinToString("\n\n") { f ->
                "PATH: ${f.path} (${f.sizeBytes} bytes)\n```\n${f.content.take(2200)}\n```"
            }
        }
        return """
            |Project Name: ${project.name}
            |Android Package Name: ${project.packageName}
            |Target Framework: ${project.targetFramework}
            |Preferred Language: ${language.wireValue}
            |
            |Existing Workspace Snapshot:
            |$fileSummary
            |
            |User Engineering Request:
            |$directive
        """.trimMargin()
    }

    companion object {
        private const val AI_TIMEOUT_MS = 60_000L

        fun mergeFiles(
            existing: List<ProjectFile>,
            patches: List<ProjectFile>
        ): List<ProjectFile> {
            val map = linkedMapOf<String, ProjectFile>()
            existing.forEach { map[it.path] = it }
            patches.forEach { map[it.path] = it }
            return map.values.sortedBy { it.path.lowercase() }
        }
    }
}
