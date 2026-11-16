package com.example.builder

import com.example.ai.provider.AiProviderDescriptor
import com.example.ai.provider.BackendAiProxyClient
import com.example.ai.provider.BackendProxyAiProvider
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.ProviderConnectionStatus
import com.example.ai.provider.ProviderId
import com.example.ai.provider.ProviderLiveProbeState
import com.example.coding.AppBuildSpecification
import com.example.coding.CodingAgentOrchestrator
import com.example.data.model.AppCategory
import com.example.data.model.BrainLanguage
import com.example.data.model.BrainProject
import com.example.data.model.IconStylePreset
import com.example.data.model.ProjectStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppBuilderOrchestratorTest {

    @Test
    fun generateCompleteAppWorkspace_scaffoldsVerifiedProjectAndFallsBackCleanlyOnClarifications() = runBlocking {
        val client = BackendAiProxyClient(tokenProvider = { "jwt" })
        val proxy = BackendProxyAiProvider(ProviderId.OPENAI_GPT, client) { "" }
        // Simulate an operational proxy model with offline server during scaffold-only test
        proxy.applyProbeState(
            ProviderLiveProbeState(
                providerId = ProviderId.OPENAI_GPT,
                status = ProviderConnectionStatus.CONNECTED_PROXY,
                statusCode = "CONNECTED_LIVE",
                diagnosticMessage = "Ready",
                lastCheckedEpochMs = 1L
            )
        )
        val registry = ModelCapabilityRegistry(listOf(proxy))
        val codingAgent = CodingAgentOrchestrator(registry)
        val appBuilder = AppBuilderOrchestrator(registry, codingAgent)

        val (suggestedName, suffix, questions) = appBuilder.generateMinimalClarificationQuestions(
            ideaPrompt = "আমার ব্যবসার জন্য একটি ইনভয়েস ও কাস্টমার অ্যাপ বানাও",
            category = AppCategory.BUSINESS,
            language = BrainLanguage.BN
        )
        assertTrue(suggestedName.isNotBlank())
        assertTrue(suffix.isNotBlank())
        assertTrue(questions.size >= 2)

        val baseProject = BrainProject(id = "proj_biz_1", name = "Starter")
        val spec = AppBuildSpecification(
            appName = "Hisab Ledger",
            packageName = "com.myraa.hisab",
            versionName = "1.2.0",
            versionCode = 3,
            appCategory = AppCategory.BUSINESS,
            iconStyle = IconStylePreset.EMERALD_FINANCE,
            selectedPermissions = listOf("android.permission.INTERNET"),
            ideaSummary = "Invoice tracker and customer ledger app",
            includeFirebaseTemplate = true
        )

        val workspaceResult = appBuilder.generateCompleteAppWorkspace(
            baseProject = baseProject,
            spec = spec,
            clarificationAnswers = mapOf(questions.first().question to "Cloud Firestore + Google Sign-In"),
            language = BrainLanguage.BN_EN,
            enhanceWithAiDomainScreen = false
        )

        assertFalse(workspaceResult.staticAnalysis.hasErrors)
        assertEquals("Hisab Ledger", workspaceResult.updatedProject.name)
        assertEquals("com.myraa.hisab", workspaceResult.updatedProject.packageName)
        assertEquals(ProjectStatus.ACTIVE.wireValue, workspaceResult.updatedProject.status)
        assertEquals(workspaceResult.files.size, workspaceResult.updatedProject.fileCount)
        assertTrue(workspaceResult.files.any { it.path == "app/src/main/java/com/myraa/hisab/ui/AppHomeScreen.kt" })
        assertTrue(workspaceResult.files.any { it.path == "firestore.rules" })
    }
}
