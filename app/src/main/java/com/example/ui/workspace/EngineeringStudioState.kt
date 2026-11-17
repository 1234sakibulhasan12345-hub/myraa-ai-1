package com.example.ui.workspace

import com.example.ai.provider.ConsensusSynthesisResult
import com.example.ai.provider.ModelRouter
import com.example.ai.provider.ParallelModelResponse
import com.example.ai.provider.ProviderId
import com.example.ai.provider.ProviderLiveProbeState
import com.example.build.RemoteBuildJobResponse
import com.example.builder.BuilderClarificationQuestion
import com.example.coding.CodingSubtaskAssignment
import com.example.coding.StaticAnalysisReport
import com.example.data.model.AppCategory
import com.example.data.model.BuildArtifact
import com.example.data.model.BuildType
import com.example.data.model.IconStylePreset

enum class BackendConnectionStatus(val label: String) {
    NOT_CONFIGURED("URL NOT SET"),
    INVALID_URL("INVALID URL"),
    SAVING("SAVING URL..."),
    TESTING("TESTING CONNECTION..."),
    CONNECTED("CONNECTED"),
    OFFLINE("BACKEND OFFLINE"),
    ERROR("CONNECTION ERROR")
}

enum class ProviderKeyVaultStatus(val label: String) {
    MISSING_API_KEY("Missing API Key"),
    BACKEND_URL_MISSING("Backend URL Missing"),
    BACKEND_OFFLINE("Backend Offline"),
    INVALID_API_KEY("Invalid API Key"),
    VERIFICATION_IN_PROGRESS("Verification in Progress"),
    VERIFIED("Verified"),
    SAVE_FAILED("Save Failed")
}

data class ProviderVaultFeedback(
    val status: ProviderKeyVaultStatus,
    val message: String,
    val clearDraftToken: Long = 0L
)

data class EngineeringStudioUiState(
    // Backend & Multi-AI Model Hub
    val backendUrlDraft: String = "",
    val preferredBuildType: BuildType = BuildType.DEBUG,
    val defaultPackagePrefix: String = "com.myraa.app",
    val isProbingBackend: Boolean = false,
    val isSavingBackendUrl: Boolean = false,
    val isTestingBackendConnection: Boolean = false,
    val backendConnectionStatus: BackendConnectionStatus = BackendConnectionStatus.NOT_CONFIGURED,
    val backendConnectionDetail: String = "Save a valid HTTPS Backend Server URL to enable encrypted API key storage & remote builds.",
    val backendAuthDiagStatus: String = "Pending sign-in / health verification",
    val backendRegistrarDiagStatus: String = "Not checked",
    val backendHealthLatencyMs: Long? = null,
    val backendLastCheckedEpochMs: Long = 0L,
    val keyMutationBusyProviderId: ProviderId? = null,
    val providerVaultFeedback: Map<String, ProviderVaultFeedback> = emptyMap(),
    val isVerifyingAppCheck: Boolean = false,
    val lastProbeSummary: String = "Gemini Spark Free Tier active via App Check. Save Free provider API keys in Settings to activate Groq, OpenRouter, Mistral, Cohere, Cloudflare, NIM & SambaNova.",
    val probeStates: List<ProviderLiveProbeState> = emptyList(),
    val comparisonPrompt: String = "",
    val selectedCompareModelKeys: Set<String> = setOf(
        "google_gemini:${ModelRouter.MODEL_GEMINI_FLASH}",
        "google_gemini:${ModelRouter.MODEL_GEMINI_FLASH_LITE}"
    ),
    val isComparing: Boolean = false,
    val parallelResponses: List<ParallelModelResponse> = emptyList(),
    val consensusResult: ConsensusSynthesisResult? = null,

    // Workspace & Coding Agent
    val selectedFilePath: String? = null,
    val editorContentDraft: String = "",
    val isEditorDirty: Boolean = false,
    val staticAnalysis: StaticAnalysisReport = StaticAnalysisReport(0, emptyList()),
    val codingDirectiveDraft: String = "",
    val selectedCodingModelKey: String = "google_gemini:${ModelRouter.MODEL_GEMINI_FLASH}",
    val isCodingBusy: Boolean = false,
    val latestCodingSummary: String? = null,
    val latestSubtaskAssignments: List<CodingSubtaskAssignment> = emptyList(),

    // App Builder Studio
    val builderCategory: AppCategory = AppCategory.CHATTING,
    val builderIdeaPrompt: String = "",
    val builderAppName: String = "Myraa App",
    val builderPackageName: String = "com.myraa.app.studio",
    val builderVersionName: String = "1.0.0",
    val builderVersionCode: String = "1",
    val builderIconStyle: IconStylePreset = IconStylePreset.NEURAL_CYBER,
    val builderPermissions: Set<String> = setOf("android.permission.INTERNET"),
    val includeFirebaseRules: Boolean = true,
    val clarificationQuestions: List<BuilderClarificationQuestion> = emptyList(),
    val clarificationAnswers: Map<String, String> = emptyMap(),
    val isAskingQuestions: Boolean = false,
    val isScaffoldingWorkspace: Boolean = false,

    // Remote APK Build Server & Download
    val isSubmittingBuild: Boolean = false,
    val activeRemoteBuild: RemoteBuildJobResponse? = null,
    val isDownloadingApk: Boolean = false,
    val apkVerificationNotice: String? = null,
    val pendingExportArtifact: BuildArtifact? = null,

    // Feedback
    val statusMessage: String? = null,
    val errorMessage: String? = null
)
