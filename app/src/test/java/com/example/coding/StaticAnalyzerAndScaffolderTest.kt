package com.example.coding

import com.example.ai.provider.AiModelDescriptor
import com.example.ai.provider.AiProviderDescriptor
import com.example.ai.provider.BackendAiProxyClient
import com.example.ai.provider.BackendProxyAiProvider
import com.example.ai.provider.ModelCapabilityRegistry
import com.example.ai.provider.ProviderConnectionStatus
import com.example.ai.provider.ProviderId
import com.example.ai.provider.ProviderLiveProbeState
import com.example.data.model.AppCategory
import com.example.data.model.BrainLanguage
import com.example.data.model.BrainProject
import com.example.data.model.IconStylePreset
import com.example.data.model.ProjectFile
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StaticAnalyzerAndScaffolderTest {

    @Test
    fun scaffolder_generatesCleanPlayCompliantProjectPassingStaticAnalysis() {
        val spec = AppBuildSpecification(
            appName = "  MYRAA 🚀 Super Very Long Chat Application Title Beyond Limit  ",
            packageName = "Com.Myraa.Bangla Chat!!",
            versionName = "2.1.0",
            versionCode = 4,
            appCategory = AppCategory.CHATTING,
            iconStyle = IconStylePreset.AURORA_CHAT,
            selectedPermissions = listOf(
                "android.permission.INTERNET",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.REQUEST_INSTALL_PACKAGES", // must be stripped!
                "android.permission.READ_MEDIA_IMAGES" // must be stripped!
            ),
            ideaSummary = "Bengali and English realtime team chat app",
            includeFirebaseTemplate = true
        )

        val files = AndroidProjectScaffolder.scaffoldProject(spec)
        val pathSet = files.map { it.path }.toSet()

        assertTrue("gradle.properties" in pathSet)
        assertTrue("settings.gradle.kts" in pathSet)
        assertTrue("build.gradle.kts" in pathSet)
        assertTrue("app/build.gradle.kts" in pathSet)
        assertTrue("app/src/main/AndroidManifest.xml" in pathSet)
        assertTrue("app/src/main/res/drawable/ic_launcher_foreground.xml" in pathSet)
        assertTrue("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml" in pathSet)
        assertTrue("firestore.rules" in pathSet)

        val stringsXml = files.first { it.path == "app/src/main/res/values/strings.xml" }.content
        assertFalse(stringsXml.contains("🚀"))

        val manifest = files.first { it.path == "app/src/main/AndroidManifest.xml" }.content
        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("android.permission.POST_NOTIFICATIONS"))
        assertFalse(manifest.contains("REQUEST_INSTALL_PACKAGES"))
        assertFalse(manifest.contains("READ_MEDIA_IMAGES"))

        val buildGradle = files.first { it.path == "app/build.gradle.kts" }.content
        assertFalse(buildGradle.contains("System.getenv"))

        val report = KotlinAndGradleStaticAnalyzer.analyze(files)
        assertFalse(
            "Clean scaffold should have 0 static errors, found: ${report.issues}",
            report.hasErrors
        )
    }

    @Test
    fun staticAnalyzer_catchesDelimiterErrorsDeprecatedKtxSecretsAndUnsafeGradle() {
        val validFiles = AndroidProjectScaffolder.scaffoldProject(
            AppBuildSpecification(
                appName = "SampleApp",
                packageName = "com.myraa.sample"
            )
        )
        val broken = validFiles.map { file ->
            when {
                file.path.endsWith("AppHomeScreen.kt") -> file.copy(
                    content = file.content + "\nfun broken() {\n    val apiKey = \"AIzaSyA123456789012345678901234567890123\"\n"
                )
                file.path.endsWith("MainActivity.kt") -> file.copy(
                    content = "package com.myraa.sample\nimport com.google.firebase.auth.ktx.auth\nfun bad() { Schema.object() }\n"
                )
                file.path == "app/build.gradle.kts" -> file.copy(
                    content = file.content + "\nRuntime.getRuntime().exec(\"whoami\")\n"
                )
                file.path == "app/src/main/AndroidManifest.xml" -> file.copy(
                    content = file.content.replace(
                        "android.permission.INTERNET",
                        "android.permission.REQUEST_INSTALL_PACKAGES"
                    )
                )
                else -> file
            }
        }

        val report = KotlinAndGradleStaticAnalyzer.analyze(broken)
        assertTrue(report.hasErrors)
        val msgs = report.issues.joinToString("\n") { it.message }
        assertTrue(msgs.contains("Unclosed '{'"))
        assertTrue(msgs.contains("Google API key"))
        assertTrue(msgs.contains("Deprecated Firebase .ktx"))
        assertTrue(msgs.contains("Schema.object"))
        assertTrue(msgs.contains("Runtime.getRuntime()"))
        assertTrue(msgs.contains("REQUEST_INSTALL_PACKAGES"))
    }

    @Test
    fun codingAgentOrchestrator_generatesAndAutoRepairsWorkspaceFiles() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var callCount = 0
        server.createContext("/v1/ai/generate") { ex ->
            callCount++
            val responseJson = if (callCount == 1) {
                """
                {
                  "providerId": "openai_gpt",
                  "modelId": "gpt-4.1-mini",
                  "text": "{\"technicalPlanSummary\":\"Added TaskItem\",\"subtasks\":[{\"title\":\"Create Screen\",\"agent\":\"Coding Agent\"}],\"files\":[{\"path\":\"app/src/main/java/com/myraa/demo/ui/AppHomeScreen.kt\",\"content\":\"package com.myraa.demo.ui\\nfun brokenFeature() { val x = 1\"}]}"
                }
                """.trimIndent()
            } else {
                """
                {
                  "providerId": "openai_gpt",
                  "modelId": "gpt-4.1-mini",
                  "text": "{\"technicalPlanSummary\":\"Repaired brace\",\"subtasks\":[{\"title\":\"Fix Brace\",\"agent\":\"Bug Fixer Agent\"}],\"files\":[{\"path\":\"app/src/main/java/com/myraa/demo/ui/AppHomeScreen.kt\",\"content\":\"package com.myraa.demo.ui\\nimport androidx.compose.runtime.Composable\\n@Composable fun AppHomeScreen() { }\"}]}"
                }
                """.trimIndent()
            }
            val bytes = responseJson.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val client = BackendAiProxyClient(tokenProvider = { "test_jwt" })
            val proxy = BackendProxyAiProvider(ProviderId.OPENAI_GPT, client) { baseUrl }
            proxy.applyProbeState(
                ProviderLiveProbeState(
                    providerId = ProviderId.OPENAI_GPT,
                    status = ProviderConnectionStatus.CONNECTED_PROXY,
                    statusCode = "CONNECTED_LIVE",
                    diagnosticMessage = "Connected",
                    lastCheckedEpochMs = 1L
                )
            )
            val registry = ModelCapabilityRegistry(listOf(proxy))
            val orchestrator = CodingAgentOrchestrator(registry)

            val initialFiles = AndroidProjectScaffolder.scaffoldProject(
                AppBuildSpecification(appName = "DemoApp", packageName = "com.myraa.demo")
            )
            val project = BrainProject(id = "p1", name = "DemoApp", packageName = "com.myraa.demo")
            val gptModel: AiModelDescriptor = proxy.descriptor.models.first()

            val genResult = orchestrator.executeCodingDirective(
                directive = "Add task screen",
                project = project,
                existingFiles = initialFiles,
                language = BrainLanguage.EN,
                preferredModel = gptModel,
                backendServerUrl = baseUrl
            )
            assertTrue(genResult.staticAnalysis.hasErrors)

            val fixResult = orchestrator.runAutoBugFixPass(
                project = project,
                existingFiles = genResult.mergedProjectFiles,
                errorLogsOrReport = genResult.staticAnalysis.issues.joinToString("\n") { it.message },
                preferredModel = gptModel,
                backendServerUrl = baseUrl
            )
            assertFalse(
                "Expected Bug Fixer AI to clear static errors, got: ${fixResult.staticAnalysis.issues}",
                fixResult.staticAnalysis.hasErrors
            )
            assertEquals("Repaired brace", fixResult.summary)
        } finally {
            server.stop(0)
        }
    }
}
