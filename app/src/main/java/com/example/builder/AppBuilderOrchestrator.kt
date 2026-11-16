package com.example.builder

import com.example.ai.provider.GeminiFirebaseProvider
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.ModelRouter
import com.example.coding.AndroidProjectScaffolder
import com.example.coding.AppBuildSpecification
import com.example.coding.CodingAgentOrchestrator
import com.example.coding.KotlinAndGradleStaticAnalyzer
import com.example.coding.StaticAnalysisReport
import com.example.core.security.AppCheckDiagnosticException
import com.example.core.security.AppCheckRecoveryHelper
import com.example.core.security.isAppCheckTokenError
import com.example.data.model.AppCategory
import com.example.data.model.BrainLanguage
import com.example.data.model.BrainProject
import com.example.data.model.ProjectFile
import com.example.data.model.ProjectStatus
import com.example.data.model.TargetFramework
import com.google.firebase.ai.type.Schema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

data class BuilderClarificationQuestion(
    val id: String,
    val question: String,
    val suggestedOptions: List<String>
)

data class GeneratedAppWorkspaceResult(
    val updatedProject: BrainProject,
    val files: List<ProjectFile>,
    val staticAnalysis: StaticAnalysisReport,
    val summary: String,
    val aiEnhancedScreenCount: Int
)

class AppBuilderOrchestrator(
    private val registry: ModelCapabilityRegistry,
    private val codingOrchestrator: CodingAgentOrchestrator,
    private val appCheckRecovery: AppCheckRecoveryHelper = AppCheckRecoveryHelper()
) {
    private val clarificationSchema: Schema by lazy {
        Schema.obj(
            properties = mapOf(
                "suggestedAppName" to Schema.string(description = "Clean short app name <= 24 chars"),
                "suggestedPackageSuffix" to Schema.string(description = "Lowercase single word identifier"),
                "questions" to Schema.array(
                    items = Schema.obj(
                        properties = mapOf(
                            "id" to Schema.string(),
                            "question" to Schema.string(),
                            "options" to Schema.array(items = Schema.string())
                        )
                    ),
                    description = "2 to 4 minimal essential clarification questions matching user's language"
                )
            )
        )
    }

    suspend fun generateMinimalClarificationQuestions(
        ideaPrompt: String,
        category: AppCategory,
        language: BrainLanguage
    ): Triple<String, String, List<BuilderClarificationQuestion>> {
        val provider = registry.getPrimaryLiveProvider()
        if (provider !is GeminiFirebaseProvider) {
            return Triple(
                AndroidProjectScaffolder.sanitizeAppTitle(category.displayLabel),
                "app",
                defaultQuestions(category)
            )
        }

        val langPrompt = when (language) {
            BrainLanguage.BN -> "Write questions in Bengali (বাংলা)."
            BrainLanguage.BN_EN -> "Write concise Banglish / English questions."
            else -> "Write crisp questions in English (matching the prompt's language)."
        }

        val model = provider.buildStructuredModel(
            modelId = ModelRouter.MODEL_GEMINI_FLASH,
            schema = clarificationSchema,
            systemPrompt = "You are MYRAA App Studio Intake Architect. Ask ONLY 2 to 4 minimal essential clarifying questions needed before generating an Android Compose app. $langPrompt"
        )

        val prompt = """
            |App Category: ${category.displayLabel} (${category.starterHint})
            |User Idea:
            |$ideaPrompt
        """.trimMargin()

        val responseText = appCheckRecovery.runWithSingleRetry("App Builder clarifications") {
            withTimeout(45_000L) { model.generateContent(prompt).text }
        } ?: return Triple(
            AndroidProjectScaffolder.sanitizeAppTitle(category.displayLabel),
            "studio",
            defaultQuestions(category)
        )

        val json = JSONObject(responseText)
        val appName = AndroidProjectScaffolder.sanitizeAppTitle(
            json.optString("suggestedAppName", category.displayLabel)
        )
        val suffix = json.optString("suggestedPackageSuffix", "studio")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")
            .ifBlank { "studio" }
            .take(16)

        val arr = json.optJSONArray("questions") ?: JSONArray()
        val questions = (0 until arr.length()).mapNotNull { idx ->
            val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
            val q = obj.optString("question").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val optsArr = obj.optJSONArray("options") ?: JSONArray()
            val opts = (0 until optsArr.length()).mapNotNull { j ->
                optsArr.optString(j).takeIf { it.isNotBlank() }
            }.take(4)
            BuilderClarificationQuestion(
                id = obj.optString("id", "q_$idx"),
                question = q,
                suggestedOptions = opts
            )
        }.take(4).ifEmpty { defaultQuestions(category) }

        return Triple(appName, suffix, questions)
    }

    suspend fun generateCompleteAppWorkspace(
        baseProject: BrainProject,
        spec: AppBuildSpecification,
        clarificationAnswers: Map<String, String>,
        language: BrainLanguage,
        enhanceWithAiDomainScreen: Boolean = true,
        backendServerUrl: String = ""
    ): GeneratedAppWorkspaceResult {
        val scaffoldedFiles = AndroidProjectScaffolder.scaffoldProject(spec)

        var finalFiles = scaffoldedFiles
        var aiSummary = "Scaffolded complete Kotlin + Jetpack Compose + Adaptive Icon Gradle project."
        var enhancedCount = 0

        if (enhanceWithAiDomainScreen) {
            val answersText = if (clarificationAnswers.isEmpty()) {
                "No additional answers supplied."
            } else {
                clarificationAnswers.entries.joinToString("\n") { (q, a) -> "- $q: $a" }
            }
            val directive = """
                |Enhance ONLY `app/src/main/java/${spec.packageName.replace('.', '/')}/ui/AppHomeScreen.kt` (and optionally add 1 ViewModel file in the same package) for the app '${spec.appName}' (${spec.appCategory.displayLabel}).
                |Requirements: ${spec.ideaSummary.ifBlank { spec.appCategory.starterHint }}
                |Clarifications:
                |$answersText
                |CRITICAL CONSTRAINTS:
                |- Keep MainActivity.kt calling `AppHomeScreen()` with zero required constructor args!
                |- Only import standard AndroidX Compose BOM (`material3`, `foundation`, `runtime`, `ui`, `material-icons-extended`) declared in app/build.gradle.kts! Do NOT invent third-party imports.
                |- Keep all Kotlin syntax cleanly balanced so remote Gradle :app:assembleDebug compiles cleanly.
            """.trimMargin()

            val projectContext = baseProject.copy(
                name = spec.appName,
                packageName = spec.packageName,
                appCategory = spec.appCategory.wireValue,
                targetFramework = TargetFramework.ANDROID_COMPOSE.wireValue
            )

            runCatching {
                codingOrchestrator.executeCodingDirective(
                    directive = directive,
                    project = projectContext,
                    existingFiles = scaffoldedFiles,
                    language = language,
                    backendServerUrl = backendServerUrl
                )
            }.onSuccess { aiResult ->
                if (!aiResult.staticAnalysis.hasErrors && aiResult.updatedFiles.isNotEmpty()) {
                    finalFiles = aiResult.mergedProjectFiles
                    aiSummary = aiResult.summary
                    enhancedCount = aiResult.updatedFiles.size
                } else {
                    aiSummary = "Scaffolded verified base app (AI domain customization had static errors and was safely held back)."
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                aiSummary = if (err is AppCheckDiagnosticException || isAppCheckTokenError(err)) {
                    "Scaffolded base app; AI screen enhancement failed App Check attestation: ${err.localizedMessage ?: "Firebase App Check token is invalid."}"
                } else {
                    "Scaffolded verified base app (AI screen customization unavailable: ${err.localizedMessage ?: err.javaClass.simpleName})."
                }
            }
        }

        val analysis = KotlinAndGradleStaticAnalyzer.analyze(finalFiles)
        val updatedProject = baseProject.copy(
            name = spec.appName,
            targetFramework = TargetFramework.ANDROID_COMPOSE.wireValue,
            appCategory = spec.appCategory.wireValue,
            packageName = AndroidProjectScaffolder.sanitizePackageName(spec.packageName),
            appVersionName = spec.versionName,
            appVersionCode = spec.versionCode,
            iconStyle = spec.iconStyle.wireValue,
            selectedPermissions = spec.selectedPermissions,
            requirementsSummary = spec.ideaSummary.ifBlank { spec.appCategory.starterHint },
            detectedLanguage = language.wireValue,
            architectureSummary = "Jetpack Compose Material 3 + Gradle 9.1.1 (${spec.iconStyle.displayLabel})",
            status = ProjectStatus.ACTIVE.wireValue,
            fileCount = finalFiles.size,
            completedFeatures = (baseProject.completedFeatures + "Generated ${finalFiles.size}-file Android Compose Workspace").distinct().take(25)
        )

        return GeneratedAppWorkspaceResult(
            updatedProject = updatedProject,
            files = finalFiles,
            staticAnalysis = analysis,
            summary = aiSummary,
            aiEnhancedScreenCount = enhancedCount
        )
    }

    private fun defaultQuestions(category: AppCategory): List<BuilderClarificationQuestion> = listOf(
        BuilderClarificationQuestion(
            id = "storage_mode",
            question = "Should user records sync with Cloud Firestore or stay local?",
            suggestedOptions = listOf("Cloud Firestore + Google Sign-In", "On-Device State Only")
        ),
        BuilderClarificationQuestion(
            id = "primary_screens",
            question = "Which core screens are top priority for ${category.displayLabel}?",
            suggestedOptions = listOf("Home Feed + Detail", "Dashboard + Create Form", "Chat + Profile")
        )
    )
}
