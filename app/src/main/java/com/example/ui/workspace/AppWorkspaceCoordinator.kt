package com.example.ui.workspace

import com.example.ai.provider.AiModelDescriptor
import com.example.ai.provider.AiProviderDescriptor
import com.example.ai.provider.ProviderId
import com.example.builder.AppBuilderOrchestrator
import com.example.coding.AndroidProjectScaffolder
import com.example.coding.AppBuildSpecification
import com.example.coding.CodingAgentOrchestrator
import com.example.coding.KotlinAndGradleStaticAnalyzer
import com.example.data.model.BrainLanguage
import com.example.data.model.BrainProject
import com.example.data.model.ProjectFile
import com.example.data.model.ProjectStatus
import com.example.data.model.TargetFramework
import com.example.data.model.UsageLog
import com.example.data.repository.SuperBrainRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppWorkspaceCoordinator(
    private val repository: SuperBrainRepository,
    private val codingOrchestrator: CodingAgentOrchestrator,
    private val appBuilderOrchestrator: AppBuilderOrchestrator,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<EngineeringStudioUiState>,
    private val catalogState: StateFlow<List<AiProviderDescriptor>>,
    private val activeProjectFlow: StateFlow<BrainProject?>,
    private val currentFilesProvider: () -> List<ProjectFile>,
    private val nextVersionProvider: () -> Int,
    private val onActivateProject: (String) -> Unit,
    private val currentUserId: String
) {
    fun fetchMinimalClarifications(language: BrainLanguage) {
        val idea = uiState.value.builderIdeaPrompt.trim()
        if (idea.isBlank() || uiState.value.isAskingQuestions) return
        val cat = uiState.value.builderCategory
        val prefix = uiState.value.defaultPackagePrefix.ifBlank { "com.myraa.app" }
        scope.launch {
            uiState.update { it.copy(isAskingQuestions = true, errorMessage = null) }
            try {
                val (appName, suffix, qs) = appBuilderOrchestrator.generateMinimalClarificationQuestions(idea, cat, language)
                uiState.update {
                    it.copy(
                        isAskingQuestions = false,
                        builderAppName = appName,
                        builderPackageName = AndroidProjectScaffolder.sanitizePackageName("$prefix.$suffix", prefix),
                        clarificationQuestions = qs,
                        clarificationAnswers = qs.associate { q -> q.id to (q.suggestedOptions.firstOrNull() ?: "") },
                        statusMessage = "Generated ${qs.size} focused app clarifications."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.update {
                    it.copy(
                        isAskingQuestions = false,
                        errorMessage = e.localizedMessage ?: "Failed to prepare clarifying questions."
                    )
                }
            }
        }
    }

    fun generateAppWorkspace(language: BrainLanguage, enhanceWithAi: Boolean, onGeneratedOpenWorkspace: () -> Unit) {
        if (uiState.value.isScaffoldingWorkspace) return
        val s = uiState.value
        val spec = AppBuildSpecification(
            appName = s.builderAppName.ifBlank { s.builderCategory.displayLabel },
            packageName = s.builderPackageName,
            versionName = s.builderVersionName.ifBlank { "1.0.0" },
            versionCode = s.builderVersionCode.toIntOrNull()?.coerceIn(1, 1_000_000) ?: 1,
            appCategory = s.builderCategory,
            iconStyle = s.builderIconStyle,
            selectedPermissions = s.builderPermissions.toList(),
            ideaSummary = s.builderIdeaPrompt,
            includeFirebaseTemplate = s.includeFirebaseRules
        )
        scope.launch {
            uiState.update { it.copy(isScaffoldingWorkspace = true, errorMessage = null) }
            try {
                val baseProject = ensureEditableProject(spec.appName, spec.packageName, spec.ideaSummary, language)
                val answerPairs = s.clarificationQuestions.associate { q -> q.question to (s.clarificationAnswers[q.id] ?: "") }
                val res = appBuilderOrchestrator.generateCompleteAppWorkspace(
                    baseProject = baseProject,
                    spec = spec,
                    clarificationAnswers = answerPairs,
                    language = language,
                    enhanceWithAiDomainScreen = enhanceWithAi,
                    backendServerUrl = s.backendUrlDraft
                )
                repository.updateProjectMemory(res.updatedProject).getOrThrow()
                repository.saveProjectFilesAndVersion(
                    projectId = res.updatedProject.id,
                    filesToSave = res.files,
                    allCurrentFilesAfterMerge = res.files,
                    versionNumber = nextVersionProvider(),
                    summary = "App Studio Build (${res.files.size} files, ${res.aiEnhancedScreenCount} AI screen enhancements)",
                    createdByAgent = "App Builder Studio"
                ).getOrThrow()
                val firstKt = res.files.firstOrNull { it.path.endsWith("AppHomeScreen.kt") } ?: res.files.firstOrNull()
                uiState.update {
                    it.copy(
                        isScaffoldingWorkspace = false,
                        staticAnalysis = res.staticAnalysis,
                        selectedFilePath = firstKt?.path,
                        editorContentDraft = firstKt?.content.orEmpty(),
                        isEditorDirty = false,
                        statusMessage = res.summary
                    )
                }
                onActivateProject(res.updatedProject.id)
                onGeneratedOpenWorkspace()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.update {
                    it.copy(
                        isScaffoldingWorkspace = false,
                        errorMessage = e.localizedMessage ?: "App workspace generation failed."
                    )
                }
            }
        }
    }

    fun executeCodingDirective(language: BrainLanguage) {
        val directive = uiState.value.codingDirectiveDraft.trim()
        if (directive.isBlank() || uiState.value.isCodingBusy) return
        scope.launch {
            uiState.update { it.copy(isCodingBusy = true, errorMessage = null) }
            try {
                val proj = ensureEditableProject("Coding Workspace", uiState.value.builderPackageName, directive, language)
                val preferredModel = findSelectedCodingModel()
                val result = codingOrchestrator.executeCodingDirective(
                    directive = directive,
                    project = proj,
                    existingFiles = currentFilesProvider(),
                    language = language,
                    preferredModel = preferredModel,
                    backendServerUrl = uiState.value.backendUrlDraft
                )
                repository.saveProjectFilesAndVersion(
                    projectId = proj.id,
                    filesToSave = result.updatedFiles,
                    allCurrentFilesAfterMerge = result.mergedProjectFiles,
                    versionNumber = nextVersionProvider(),
                    summary = "Coding Agent (${result.usedModelId}): ${result.summary.take(180)}",
                    createdByAgent = "Coding Agent"
                ).getOrThrow()
                repository.recordUsageLog(
                    UsageLog(
                        projectId = proj.id,
                        providerId = preferredModel?.providerId?.wireId ?: ProviderId.GOOGLE_GEMINI.wireId,
                        modelId = result.usedModelId,
                        taskRole = "CODING_AGENT",
                        inputLanguage = language.wireValue,
                        latencyMs = result.latencyMs,
                        succeeded = true,
                        promptTokens = result.promptTokens,
                        completionTokens = result.completionTokens,
                        estimatedCostUsdMicros = result.estimatedCostUsdMicros
                    )
                )
                val focus = result.updatedFiles.firstOrNull()
                uiState.update {
                    it.copy(
                        isCodingBusy = false,
                        codingDirectiveDraft = "",
                        latestCodingSummary = result.summary,
                        latestSubtaskAssignments = result.planSteps,
                        staticAnalysis = result.staticAnalysis,
                        selectedFilePath = focus?.path ?: it.selectedFilePath,
                        editorContentDraft = focus?.content ?: it.editorContentDraft,
                        isEditorDirty = false,
                        statusMessage = "Updated ${result.updatedFiles.size} file(s) • Static errors: ${result.staticAnalysis.errorCount}"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.update {
                    it.copy(
                        isCodingBusy = false,
                        errorMessage = e.localizedMessage ?: "Coding Agent failed."
                    )
                }
            }
        }
    }

    fun autoFixWorkspaceErrors() {
        val proj = activeProjectFlow.value ?: return
        val files = currentFilesProvider()
        if (files.isEmpty() || uiState.value.isCodingBusy) return
        val diagnostics = buildString {
            KotlinAndGradleStaticAnalyzer.analyze(files).issues.forEach {
                appendLine("${it.severity.label} [${it.filePath}:${it.lineHint}] ${it.message} -> ${it.suggestion}")
            }
            val buildErr = uiState.value.activeRemoteBuild?.errorSummary.orEmpty()
            if (buildErr.isNotBlank()) appendLine("Remote Gradle Error:\n$buildErr")
        }.ifBlank { "Perform a clean Kotlin, Compose & Gradle health pass." }

        scope.launch {
            uiState.update { it.copy(isCodingBusy = true, errorMessage = null) }
            try {
                val res = codingOrchestrator.runAutoBugFixPass(
                    project = proj,
                    existingFiles = files,
                    errorLogsOrReport = diagnostics,
                    preferredModel = findSelectedCodingModel(),
                    backendServerUrl = uiState.value.backendUrlDraft
                )
                repository.saveProjectFilesAndVersion(
                    projectId = proj.id,
                    filesToSave = res.updatedFiles,
                    allCurrentFilesAfterMerge = res.mergedProjectFiles,
                    versionNumber = nextVersionProvider(),
                    summary = "Bug Fixer AI: ${res.summary.take(180)}",
                    createdByAgent = "Bug Fixer Agent"
                ).getOrThrow()
                val focus = res.updatedFiles.firstOrNull()
                uiState.update {
                    it.copy(
                        isCodingBusy = false,
                        latestCodingSummary = res.summary,
                        latestSubtaskAssignments = res.planSteps,
                        staticAnalysis = res.staticAnalysis,
                        selectedFilePath = focus?.path ?: it.selectedFilePath,
                        editorContentDraft = focus?.content ?: it.editorContentDraft,
                        isEditorDirty = false,
                        statusMessage = "Bug Fixer patched ${res.updatedFiles.size} file(s). Remaining errors: ${res.staticAnalysis.errorCount}"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                uiState.update {
                    it.copy(
                        isCodingBusy = false,
                        errorMessage = e.localizedMessage ?: "Auto-fix failed."
                    )
                }
            }
        }
    }

    private suspend fun ensureEditableProject(
        fallbackName: String,
        pkg: String,
        summary: String,
        language: BrainLanguage
    ): BrainProject {
        val current = activeProjectFlow.value
        if (current != null && current.status != ProjectStatus.ARCHIVED.wireValue) return current
        val newProj = BrainProject(
            userId = currentUserId,
            name = AndroidProjectScaffolder.sanitizeAppTitle(fallbackName),
            targetFramework = TargetFramework.ANDROID_COMPOSE.wireValue,
            packageName = AndroidProjectScaffolder.sanitizePackageName(pkg),
            requirementsSummary = summary.take(3500),
            detectedLanguage = language.wireValue,
            status = ProjectStatus.ACTIVE.wireValue
        )
        val newId = repository.createProject(newProj).getOrThrow()
        onActivateProject(newId)
        return newProj.copy(id = newId)
    }

    private fun findSelectedCodingModel(): AiModelDescriptor? =
        catalogState.value.flatMap { it.models }.firstOrNull {
            "${it.providerId.wireId}:${it.modelId}" == uiState.value.selectedCodingModelKey && it.isLiveInCurrentBuild
        }
}
