package com.example.coding

import com.example.data.model.AppCategory
import com.example.data.model.IconStylePreset
import com.example.data.model.ProjectFile
import com.example.data.repository.SuperBrainRepository

data class AppBuildSpecification(
    val appName: String,
    val packageName: String,
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val appCategory: AppCategory = AppCategory.CUSTOM,
    val iconStyle: IconStylePreset = IconStylePreset.NEURAL_CYBER,
    val selectedPermissions: List<String> = listOf("android.permission.INTERNET"),
    val ideaSummary: String = "",
    val includeFirebaseTemplate: Boolean = false
)

object AndroidProjectScaffolder {

    private val allowedPackageSegment = Regex("^[a-z][a-z0-9_]*$")

    fun sanitizePackageName(rawPackage: String, fallbackPrefix: String = "com.myraa.app"): String {
        val cleaned = rawPackage.trim().lowercase()
            .replace(Regex("[^a-z0-9.]"), "")
            .split('.')
            .filter { it.isNotBlank() && allowedPackageSegment.matches(it) }
        if (cleaned.size >= 2) {
            return cleaned.take(4).joinToString(".")
        }
        val safePrefix = fallbackPrefix.trim().lowercase()
            .replace(Regex("[^a-z0-9.]"), "")
            .ifBlank { "com.myraa.app" }
        return "$safePrefix.studio"
    }

    fun sanitizeAppTitle(rawName: String): String {
        val clean = rawName.trim()
            .replace(Regex("""[\p{So}\p{Cn}]"""), "")
            .replace(Regex("\\s+"), " ")
        return clean.ifBlank { "Myraa App" }.take(30)
    }

    fun scaffoldProject(spec: AppBuildSpecification): List<ProjectFile> {
        val safeName = sanitizeAppTitle(spec.appName)
        val safePkg = sanitizePackageName(spec.packageName)
        val pkgPath = safePkg.replace('.', '/')
        val safeVersionName = spec.versionName.trim().ifBlank { "1.0.0" }.take(32)
        val safeVersionCode = spec.versionCode.coerceIn(1, 1_000_000)

        val filteredPerms = spec.selectedPermissions
            .filter { perm ->
                perm.startsWith("android.permission.") &&
                    perm != "android.permission.REQUEST_INSTALL_PACKAGES" &&
                    perm != "android.permission.MANAGE_EXTERNAL_STORAGE" &&
                    perm != "android.permission.READ_EXTERNAL_STORAGE" &&
                    perm != "android.permission.WRITE_EXTERNAL_STORAGE" &&
                    perm != "android.permission.READ_MEDIA_IMAGES" &&
                    perm != "android.permission.READ_MEDIA_VIDEO"
            }
            .distinct()

        val permissionsXml = if (filteredPerms.isEmpty()) {
            ""
        } else {
            filteredPerms.joinToString("\n") { perm ->
                """    <uses-permission android:name="$perm" />"""
            } + "\n"
        }

        val files = mutableListOf<ProjectFile>()

        fun addFile(path: String, content: String) {
            val cleanPath = SuperBrainRepository.sanitizeRelativePath(path)
            files += ProjectFile(
                id = SuperBrainRepository.fileIdForPath(cleanPath),
                path = cleanPath,
                fileName = cleanPath.substringAfterLast('/'),
                language = SuperBrainRepository.inferLanguageFromPath(cleanPath),
                content = content,
                sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
                checksumSha256 = SuperBrainRepository.sha256Hex(content),
                updatedByAgent = "App Builder Studio"
            )
        }

        addFile(
            "gradle.properties",
            """
            |org.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
            |android.nonTransitiveRClass=true
            |kotlin.code.style=official
            |kotlin.compiler.execution.strategy=in-process
            """.trimMargin() + "\n"
        )

        addFile(
            "settings.gradle.kts",
            """
            |pluginManagement {
            |    repositories {
            |        google()
            |        mavenCentral()
            |        gradlePluginPortal()
            |    }
            |}
            |dependencyResolutionManagement {
            |    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            |    repositories {
            |        google()
            |        mavenCentral()
            |    }
            |}
            |rootProject.name = "${safeName.replace("\"", "")}"
            |include(":app")
            """.trimMargin() + "\n"
        )

        addFile(
            "build.gradle.kts",
            """
            |plugins {
            |    id("com.android.application") version "9.1.1" apply false
            |    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
            |}
            """.trimMargin() + "\n"
        )

        addFile(
            "app/build.gradle.kts",
            """
            |plugins {
            |    id("com.android.application")
            |    id("org.jetbrains.kotlin.plugin.compose")
            |}
            |
            |android {
            |    namespace = "$safePkg"
            |    compileSdk = 36
            |
            |    defaultConfig {
            |        applicationId = "$safePkg"
            |        minSdk = 24
            |        targetSdk = 36
            |        versionCode = $safeVersionCode
            |        versionName = "$safeVersionName"
            |    }
            |
            |    compileOptions {
            |        sourceCompatibility = JavaVersion.VERSION_11
            |        targetCompatibility = JavaVersion.VERSION_11
            |    }
            |
            |    buildFeatures {
            |        compose = true
            |    }
            |}
            |
            |dependencies {
            |    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
            |    implementation("androidx.core:core-ktx:1.18.0")
            |    implementation("androidx.activity:activity-compose:1.10.1")
            |    implementation("androidx.compose.material3:material3")
            |    implementation("androidx.compose.ui:ui")
            |    implementation("androidx.compose.material:material-icons-extended")
            |}
            """.trimMargin() + "\n"
        )

        addFile(
            "app/src/main/AndroidManifest.xml",
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            |$permissionsXml    <application
            |        android:allowBackup="true"
            |        android:icon="@mipmap/ic_launcher"
            |        android:roundIcon="@mipmap/ic_launcher_round"
            |        android:label="@string/app_name"
            |        android:supportsRtl="true"
            |        android:theme="@android:style/Theme.Material.NoActionBar">
            |        <activity
            |            android:name=".MainActivity"
            |            android:exported="true">
            |            <intent-filter>
            |                <action android:name="android.intent.action.MAIN" />
            |                <category android:name="android.intent.category.LAUNCHER" />
            |            </intent-filter>
            |        </activity>
            |    </application>
            |</manifest>
            """.trimMargin() + "\n"
        )

        addFile(
            "app/src/main/res/values/strings.xml",
            "<resources>\n    <string name=\"app_name\">$safeName</string>\n</resources>\n"
        )

        addFile(
            "app/src/main/res/drawable/ic_launcher_background.xml",
            """
            |<vector xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:width="108dp"
            |    android:height="108dp"
            |    android:viewportWidth="108"
            |    android:viewportHeight="108">
            |    <path
            |        android:fillColor="${spec.iconStyle.bgColorHex}"
            |        android:pathData="M0,0h108v108h-108z" />
            |    <path
            |        android:fillColor="${spec.iconStyle.accentHex}"
            |        android:fillAlpha="0.18"
            |        android:pathData="M54,14A40,40 0 1,1 14,54A40,40 0 0,1 54,14Z" />
            |</vector>
            """.trimMargin() + "\n"
        )

        addFile(
            "app/src/main/res/drawable/ic_launcher_foreground.xml",
            """
            |<vector xmlns:android="http://schemas.android.com/apk/res/android"
            |    android:width="108dp"
            |    android:height="108dp"
            |    android:viewportWidth="108"
            |    android:viewportHeight="108">
            |    <path
            |        android:fillColor="${spec.iconStyle.accentHex}"
            |        android:pathData="M54,28L76,42V66L54,80L32,66V42L54,28Z" />
            |    <path
            |        android:fillColor="#FFFFFF"
            |        android:pathData="M54,40L65,47V61L54,68L43,61V47L54,40Z" />
            |</vector>
            """.trimMargin() + "\n"
        )

        val anydpiXml = """
            |<?xml version="1.0" encoding="utf-8"?>
            |<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
            |    <background android:drawable="@drawable/ic_launcher_background" />
            |    <foreground android:drawable="@drawable/ic_launcher_foreground" />
            |</adaptive-icon>
        """.trimMargin() + "\n"
        addFile("app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml", anydpiXml)
        addFile("app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml", anydpiXml)

        val accentComposeColor = "0xFF${spec.iconStyle.accentHex.removePrefix("#")}"
        val bgComposeColor = "0xFF${spec.iconStyle.bgColorHex.removePrefix("#")}"

        addFile(
            "app/src/main/java/$pkgPath/ui/theme/AppTheme.kt",
            """
            |package $safePkg.ui.theme
            |
            |import androidx.compose.material3.MaterialTheme
            |import androidx.compose.material3.darkColorScheme
            |import androidx.compose.runtime.Composable
            |import androidx.compose.ui.graphics.Color
            |
            |private val AppDarkColors = darkColorScheme(
            |    primary = Color($accentComposeColor),
            |    background = Color($bgComposeColor),
            |    surface = Color($bgComposeColor),
            |    onBackground = Color(0xFFF5F7FF),
            |    onSurface = Color(0xFFF5F7FF)
            |)
            |
            |@Composable
            |fun GeneratedAppTheme(content: @Composable () -> Unit) {
            |    MaterialTheme(
            |        colorScheme = AppDarkColors,
            |        content = content
            |    )
            |}
            """.trimMargin() + "\n"
        )

        addFile(
            "app/src/main/java/$pkgPath/MainActivity.kt",
            """
            |package $safePkg
            |
            |import android.os.Bundle
            |import androidx.activity.ComponentActivity
            |import androidx.activity.compose.setContent
            |import androidx.activity.enableEdgeToEdge
            |import $safePkg.ui.AppHomeScreen
            |import $safePkg.ui.theme.GeneratedAppTheme
            |
            |class MainActivity : ComponentActivity() {
            |    override fun onCreate(savedInstanceState: Bundle?) {
            |        super.onCreate(savedInstanceState)
            |        enableEdgeToEdge()
            |        setContent {
            |            GeneratedAppTheme {
            |                AppHomeScreen()
            |            }
            |        }
            |    }
            |}
            """.trimMargin() + "\n"
        )

        val safeSummary = spec.ideaSummary.trim()
            .replace("\"", "'")
            .replace("\n", " ")
            .take(240)
            .ifBlank { spec.appCategory.starterHint }

        addFile(
            "app/src/main/java/$pkgPath/ui/AppHomeScreen.kt",
            """
            |package $safePkg.ui
            |
            |import androidx.compose.foundation.layout.Arrangement
            |import androidx.compose.foundation.layout.Column
            |import androidx.compose.foundation.layout.WindowInsets
            |import androidx.compose.foundation.layout.fillMaxSize
            |import androidx.compose.foundation.layout.fillMaxWidth
            |import androidx.compose.foundation.layout.padding
            |import androidx.compose.foundation.layout.safeDrawing
            |import androidx.compose.foundation.lazy.LazyColumn
            |import androidx.compose.foundation.lazy.items
            |import androidx.compose.material3.Button
            |import androidx.compose.material3.Card
            |import androidx.compose.material3.ExperimentalMaterial3Api
            |import androidx.compose.material3.MaterialTheme
            |import androidx.compose.material3.OutlinedTextField
            |import androidx.compose.material3.Scaffold
            |import androidx.compose.material3.Text
            |import androidx.compose.material3.TopAppBar
            |import androidx.compose.runtime.Composable
            |import androidx.compose.runtime.getValue
            |import androidx.compose.runtime.mutableStateListOf
            |import androidx.compose.runtime.mutableStateOf
            |import androidx.compose.runtime.remember
            |import androidx.compose.runtime.setValue
            |import androidx.compose.ui.Modifier
            |import androidx.compose.ui.unit.dp
            |
            |@OptIn(ExperimentalMaterial3Api::class)
            |@Composable
            |fun AppHomeScreen() {
            |    val items = remember {
            |        mutableStateListOf(
            |            "${spec.appCategory.displayLabel} Workspace Ready",
            |            "$safeSummary"
            |        )
            |    }
            |    var draft by remember { mutableStateOf("") }
            |
            |    Scaffold(
            |        contentWindowInsets = WindowInsets.safeDrawing,
            |        topBar = {
            |            TopAppBar(title = { Text("$safeName") })
            |        }
            |    ) { innerPadding ->
            |        Column(
            |            modifier = Modifier
            |                .fillMaxSize()
            |                .padding(innerPadding)
            |                .padding(16.dp),
            |            verticalArrangement = Arrangement.spacedBy(12.dp)
            |        ) {
            |            OutlinedTextField(
            |                value = draft,
            |                onValueChange = { draft = it },
            |                label = { Text("Add new entry") },
            |                modifier = Modifier.fillMaxWidth()
            |            )
            |            Button(
            |                onClick = {
            |                    if (draft.isNotBlank()) {
            |                        items.add(0, draft.trim())
            |                        draft = ""
            |                    }
            |                },
            |                modifier = Modifier.fillMaxWidth()
            |            ) {
            |                Text("Save Item")
            |            }
            |            LazyColumn(
            |                modifier = Modifier.weight(1f),
            |                verticalArrangement = Arrangement.spacedBy(8.dp)
            |            ) {
            |                items(items) { entry ->
            |                    Card(modifier = Modifier.fillMaxWidth()) {
            |                        Text(
            |                            text = entry,
            |                            style = MaterialTheme.typography.bodyLarge,
            |                            modifier = Modifier.padding(14.dp)
            |                        )
            |                    }
            |                }
            |            }
            |        }
            |    }
            |}
            """.trimMargin() + "\n"
        )

        if (spec.includeFirebaseTemplate) {
            addFile(
                "firestore.rules",
                """
                |rules_version = '2';
                |service cloud.firestore {
                |  match /databases/{database}/documents {
                |    match /users/{userId}/{document=**} {
                |      allow read, write: if request.auth != null && request.auth.uid == userId;
                |    }
                |    match /{document=**} {
                |      allow read, write: if false;
                |    }
                |  }
                |}
                """.trimMargin() + "\n"
            )
        }

        return files
    }
}
