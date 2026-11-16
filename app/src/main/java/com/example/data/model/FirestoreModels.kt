package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class UserWorkspace(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "Commander",
    val preferredLanguage: String = BrainLanguage.AUTO.wireValue,
    val routingPolicy: String = RoutingPolicy.AUTO_BALANCED.wireValue,
    val activeProjectId: String? = null,
    val backendServiceUrl: String = "",
    val preferredBuildType: String = BuildType.DEBUG.wireValue,
    val defaultPackagePrefix: String = "com.myraa.app",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BrainProject(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "",
    val targetFramework: String = TargetFramework.ANDROID_COMPOSE.wireValue,
    val requirementsSummary: String = "",
    val detectedLanguage: String = BrainLanguage.AUTO.wireValue,
    val architectureSummary: String = "",
    val completedFeatures: List<String> = emptyList(),
    val pendingFeatures: List<String> = emptyList(),
    val status: String = ProjectStatus.PLANNING.wireValue,
    val appCategory: String = AppCategory.CUSTOM.wireValue,
    val packageName: String = "com.myraa.generated.app",
    val appVersionName: String = "1.0.0",
    val appVersionCode: Int = 1,
    val iconStyle: String = IconStylePreset.NEURAL_CYBER.wireValue,
    val selectedPermissions: List<String> = listOf("android.permission.INTERNET"),
    val latestBuildJobId: String? = null,
    val latestBuildStatus: String = "",
    val fileCount: Int = 0,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BrainMessage(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val role: String = MessageRole.USER.wireValue,
    val content: String = "",
    val detectedLanguage: String = BrainLanguage.AUTO.wireValue,
    val selectedProviderId: String = "",
    val selectedModelId: String = "",
    val intentSummary: String = "",
    val assignedAgents: List<String> = emptyList(),
    val subtasks: List<String> = emptyList(),
    val latencyMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ProjectFile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val path: String = "",
    val fileName: String = "",
    val language: String = "kotlin",
    val content: String = "",
    val sizeBytes: Long = 0L,
    val checksumSha256: String = "",
    val updatedByAgent: String = "Coding Agent",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ProjectVersion(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val versionNumber: Int = 1,
    val summary: String = "",
    val createdByAgent: String = "Coding Agent",
    val fileEntries: List<Map<String, String>> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BuildJob(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val status: String = BuildJobStatus.QUEUED.wireValue,
    val buildType: String = BuildType.DEBUG.wireValue,
    val appName: String = "",
    val packageName: String = "",
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val targetServerUrl: String = "",
    val startedByModel: String = "gemini-3.8-flash",
    val logSummary: String = "",
    val errorSummary: String = "",
    val durationMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class BuildArtifact(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val jobId: String = "",
    val fileName: String = "",
    val downloadUrl: String = "",
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val buildType: String = BuildType.DEBUG.wireValue,
    val apkVerified: Boolean = false,
    val expiresAtEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class UsageLog(
    @DocumentId val id: String = "",
    val userId: String = "",
    val projectId: String = "",
    val providerId: String = "",
    val modelId: String = "",
    val taskRole: String = "",
    val inputLanguage: String = BrainLanguage.AUTO.wireValue,
    val estimatedInputChars: Long = 0L,
    val estimatedOutputChars: Long = 0L,
    val latencyMs: Long = 0L,
    val succeeded: Boolean = true,
    val promptTokens: Long = 0L,
    val completionTokens: Long = 0L,
    val estimatedCostUsdMicros: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null
)
