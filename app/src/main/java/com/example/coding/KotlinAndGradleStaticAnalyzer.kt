package com.example.coding

import com.example.data.model.ProjectFile

enum class IssueSeverity(val label: String) {
    ERROR("ERROR"),
    WARNING("WARNING")
}

data class StaticAnalysisIssue(
    val filePath: String,
    val lineHint: Int,
    val severity: IssueSeverity,
    val message: String,
    val suggestion: String
)

data class StaticAnalysisReport(
    val scannedFileCount: Int,
    val issues: List<StaticAnalysisIssue>,
    val analysisLabel: String = "Local Static Syntax, Import, Security & Play-Policy Check"
) {
    val errorCount: Int get() = issues.count { it.severity == IssueSeverity.ERROR }
    val warningCount: Int get() = issues.count { it.severity == IssueSeverity.WARNING }
    val hasErrors: Boolean get() = errorCount > 0
}

object KotlinAndGradleStaticAnalyzer {

    private val secretPatterns = listOf(
        Regex("""AIza[0-9A-Za-z\-_]{35}""") to "Potential hardcoded Google API key detected",
        Regex("""sk-[A-Za-z0-9]{20,}""") to "Potential hardcoded OpenAI/DeepSeek secret key detected",
        Regex("""sk-ant-[A-Za-z0-9\-_]{20,}""") to "Potential hardcoded Anthropic secret key detected",
        Regex("""ghp_[A-Za-z0-9]{30,}""") to "Potential hardcoded GitHub token detected"
    )

    private val restrictedPermissions = listOf(
        "android.permission.REQUEST_INSTALL_PACKAGES" to "High-risk restricted permission disallowed by MYRAA Play policy",
        "android.permission.READ_EXTERNAL_STORAGE" to "Broad storage permission discouraged; use Android Photo Picker or SAF",
        "android.permission.WRITE_EXTERNAL_STORAGE" to "Legacy storage permission blocked; use MediaStore or SAF",
        "android.permission.READ_MEDIA_IMAGES" to "Use zero-permission Android Photo Picker instead of READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO" to "Use zero-permission Android Photo Picker instead of READ_MEDIA_VIDEO",
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "All-files access restricted by Google Play policy"
    )

    private val unsafeGradlePatterns = listOf(
        Regex("""Runtime\.getRuntime\s*\(""") to "Runtime.getRuntime() blocked in Gradle build scripts",
        Regex("""ProcessBuilder""") to "ProcessBuilder blocked in Gradle build scripts",
        Regex("""\bexec\s*\(""") to "Shell exec() blocked in Gradle build scripts",
        Regex("""System\.getenv\s*\(""") to "System.getenv() disallowed in generated app Gradle files"
    )

    fun analyze(files: List<ProjectFile>): StaticAnalysisReport {
        val issues = mutableListOf<StaticAnalysisIssue>()
        val pathSet = files.map { it.path }.toSet()

        if ("settings.gradle.kts" !in pathSet && "settings.gradle" !in pathSet) {
            issues += StaticAnalysisIssue(
                filePath = "settings.gradle.kts",
                lineHint = 1,
                severity = IssueSeverity.ERROR,
                message = "Missing settings.gradle.kts root configuration",
                suggestion = "Ensure App Builder scaffolds settings.gradle.kts with rootProject.name and include(\":app\")."
            )
        }
        if ("app/build.gradle.kts" !in pathSet && "app/build.gradle" !in pathSet) {
            issues += StaticAnalysisIssue(
                filePath = "app/build.gradle.kts",
                lineHint = 1,
                severity = IssueSeverity.ERROR,
                message = "Missing app/build.gradle.kts module build script",
                suggestion = "Include app/build.gradle.kts defining plugins, android SDKs, and dependencies."
            )
        }
        if ("app/src/main/AndroidManifest.xml" !in pathSet) {
            issues += StaticAnalysisIssue(
                filePath = "app/src/main/AndroidManifest.xml",
                lineHint = 1,
                severity = IssueSeverity.ERROR,
                message = "Missing AndroidManifest.xml",
                suggestion = "Add app/src/main/AndroidManifest.xml with MainActivity launcher declaration."
            )
        }

        var kotlinUsesInternetApi = false

        files.forEach { file ->
            // Scan all files for accidental secret leaks
            secretPatterns.forEach { (pattern, msg) ->
                if (pattern.containsMatchIn(file.content)) {
                    issues += StaticAnalysisIssue(
                        filePath = file.path,
                        lineHint = findLineNumber(file.content, pattern),
                        severity = IssueSeverity.ERROR,
                        message = msg,
                        suggestion = "Never embed API keys or secrets inside project source files; use backend env variables."
                    )
                }
            }

            when {
                file.path.endsWith(".kt") -> {
                    val content = file.content
                    if (file.path.contains("src/main/java/") && !Regex("""^\s*package\s+[a-zA-Z0-9_.]+""", RegexOption.MULTILINE).containsMatchIn(content)) {
                        issues += StaticAnalysisIssue(
                            filePath = file.path,
                            lineHint = 1,
                            severity = IssueSeverity.ERROR,
                            message = "Missing Kotlin package declaration",
                            suggestion = "Add 'package <your.package.name>' at line 1."
                        )
                    }
                    if (Regex("""com\.google\.firebase\.[a-z]+\.ktx\b""").containsMatchIn(content)) {
                        issues += StaticAnalysisIssue(
                            filePath = file.path,
                            lineHint = findLineNumber(content, Regex("""\.ktx\b""")),
                            severity = IssueSeverity.ERROR,
                            message = "Deprecated Firebase .ktx import detected (removed in Firebase BoM v34+)",
                            suggestion = "Replace com.google.firebase.*.ktx imports with com.google.firebase.* main modules."
                        )
                    }
                    if (content.contains("Schema.object(")) {
                        issues += StaticAnalysisIssue(
                            filePath = file.path,
                            lineHint = findLineNumber(content, Regex("""Schema\.object\(""")),
                            severity = IssueSeverity.ERROR,
                            message = "Invalid Firebase AI Schema method Schema.object(...)",
                            suggestion = "Use Schema.obj(...) because 'object' is reserved in Kotlin."
                        )
                    }
                    if (content.contains("OkHttpClient") || content.contains("Retrofit") || content.contains("HttpURLConnection") || content.contains("FirebaseFirestore")) {
                        kotlinUsesInternetApi = true
                    }

                    val balanceIssue = checkDelimiterBalance(content)
                    if (balanceIssue != null) {
                        issues += StaticAnalysisIssue(
                            filePath = file.path,
                            lineHint = balanceIssue.first,
                            severity = IssueSeverity.ERROR,
                            message = balanceIssue.second,
                            suggestion = "Balance curly braces {} and parentheses () before remote APK compilation."
                        )
                    }
                }

                file.path.endsWith(".gradle.kts") || file.path.endsWith(".gradle") -> {
                    unsafeGradlePatterns.forEach { (pat, msg) ->
                        if (pat.containsMatchIn(file.content)) {
                            issues += StaticAnalysisIssue(
                                filePath = file.path,
                                lineHint = findLineNumber(file.content, pat),
                                severity = IssueSeverity.ERROR,
                                message = msg,
                                suggestion = "Remove unsafe execution/environment logic; build server enforces strict Gradle sanitization."
                            )
                        }
                    }
                    if (file.path == "app/build.gradle.kts") {
                        if (!file.content.contains("namespace")) {
                            issues += StaticAnalysisIssue(
                                filePath = file.path,
                                lineHint = 1,
                                severity = IssueSeverity.ERROR,
                                message = "app/build.gradle.kts is missing 'namespace'",
                                suggestion = "Set namespace = \"<packageName>\" inside android { }."
                            )
                        }
                        if (!file.content.contains("applicationId")) {
                            issues += StaticAnalysisIssue(
                                filePath = file.path,
                                lineHint = 1,
                                severity = IssueSeverity.ERROR,
                                message = "app/build.gradle.kts is missing 'applicationId'",
                                suggestion = "Set applicationId inside defaultConfig { }."
                            )
                        }
                    }
                }

                file.path == "app/src/main/AndroidManifest.xml" -> {
                    val xml = file.content
                    restrictedPermissions.forEach { (perm, msg) ->
                        if (xml.contains(perm)) {
                            issues += StaticAnalysisIssue(
                                filePath = file.path,
                                lineHint = findLineNumber(xml, Regex(Regex.escape(perm))),
                                severity = IssueSeverity.ERROR,
                                message = "$perm: $msg",
                                suggestion = "Remove $perm to stay compliant with Google Play Developer Policy."
                            )
                        }
                    }
                    if (!xml.contains("android.intent.action.MAIN") || !xml.contains("android.intent.category.LAUNCHER")) {
                        issues += StaticAnalysisIssue(
                            filePath = file.path,
                            lineHint = 1,
                            severity = IssueSeverity.WARNING,
                            message = "AndroidManifest.xml is missing MAIN / LAUNCHER intent-filter",
                            suggestion = "Add a launcher <activity android:exported=\"true\"> so the installed APK opens cleanly."
                        )
                    }
                }
            }
        }

        if (kotlinUsesInternetApi) {
            val manifest = files.firstOrNull { it.path == "app/src/main/AndroidManifest.xml" }
            if (manifest != null && !manifest.content.contains("android.permission.INTERNET")) {
                issues += StaticAnalysisIssue(
                    filePath = manifest.path,
                    lineHint = 1,
                    severity = IssueSeverity.WARNING,
                    message = "Network/Firebase APIs used in Kotlin code, but INTERNET permission is missing in AndroidManifest.xml",
                    suggestion = "Add <uses-permission android:name=\"android.permission.INTERNET\" /> to AndroidManifest.xml."
                )
            }
        }

        return StaticAnalysisReport(
            scannedFileCount = files.size,
            issues = issues
        )
    }

    private fun checkDelimiterBalance(source: String): Pair<Int, String>? {
        var braceDepth = 0
        var parenDepth = 0
        var line = 1
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inChar = false
        var i = 0

        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else '\u0000'

            if (c == '\n') {
                line++
                inLineComment = false
                i++
                continue
            }
            if (inLineComment) {
                i++
                continue
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (inString) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inString = false
                i++
                continue
            }
            if (inChar) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '\'') inChar = false
                i++
                continue
            }

            if (c == '/' && next == '/') {
                inLineComment = true
                i += 2
                continue
            }
            if (c == '/' && next == '*') {
                inBlockComment = true
                i += 2
                continue
            }
            if (c == '"') {
                inString = true
                i++
                continue
            }
            if (c == '\'') {
                inChar = true
                i++
                continue
            }

            when (c) {
                '{' -> braceDepth++
                '}' -> {
                    braceDepth--
                    if (braceDepth < 0) return line to "Unexpected closing '}' in Kotlin source"
                }
                '(' -> parenDepth++
                ')' -> {
                    parenDepth--
                    if (parenDepth < 0) return line to "Unexpected closing ')' in Kotlin source"
                }
            }
            i++
        }

        return when {
            braceDepth > 0 -> line to "Unclosed '{' ($braceDepth unclosed brace(s))"
            parenDepth > 0 -> line to "Unclosed '(' ($parenDepth unclosed parenthesis(es))"
            else -> null
        }
    }

    private fun findLineNumber(text: String, regex: Regex): Int {
        val match = regex.find(text) ?: return 1
        return text.substring(0, match.range.first).count { it == '\n' } + 1
    }
}
