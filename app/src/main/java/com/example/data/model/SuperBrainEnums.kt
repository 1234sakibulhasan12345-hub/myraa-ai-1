package com.example.data.model

enum class BrainLanguage(val wireValue: String, val displayLabel: String, val nativeLabel: String) {
    AUTO("AUTO", "Auto Detect", "Auto (বাংলা / EN)"),
    EN("EN", "English", "English"),
    BN("BN", "Bengali", "বাংলা"),
    BN_EN("BN_EN", "Bangla-English", "Banglish / Mixed");

    companion object {
        fun fromWire(value: String): BrainLanguage =
            entries.firstOrNull { it.wireValue == value } ?: AUTO
    }
}

enum class RoutingPolicy(val wireValue: String, val displayLabel: String, val description: String) {
    AUTO_BALANCED(
        "AUTO_BALANCED",
        "Auto Orchestrate",
        "Routes complex planning & architecture to flagship models and fast tasks to low-latency tiers"
    ),
    QUALITY_DEFAULT(
        "QUALITY_DEFAULT",
        "Deep Reasoning",
        "Prioritizes maximum architectural & coding accuracy across live providers"
    ),
    ULTRA_FAST(
        "ULTRA_FAST",
        "Ultra Fast",
        "Prioritizes Flash-Lite or fastest available endpoint for sub-second iterations"
    );

    companion object {
        fun fromWire(value: String): RoutingPolicy =
            entries.firstOrNull { it.wireValue == value } ?: AUTO_BALANCED
    }
}

enum class TargetFramework(val wireValue: String, val displayLabel: String) {
    ANDROID_COMPOSE("ANDROID_COMPOSE", "Android (Kotlin + Compose)"),
    WEB_APP("WEB_APP", "Web App (React / Next.js)"),
    BACKEND_API("BACKEND_API", "Backend & Database API"),
    FULLSTACK_SYSTEM("FULLSTACK_SYSTEM", "Full-Stack Multi-Platform");

    companion object {
        fun fromWire(value: String): TargetFramework =
            entries.firstOrNull { it.wireValue == value } ?: ANDROID_COMPOSE
    }
}

enum class ProjectStatus(val wireValue: String, val displayLabel: String) {
    PLANNING("PLANNING", "Planning"),
    ACTIVE("ACTIVE", "Active"),
    PAUSED("PAUSED", "Paused"),
    ARCHIVED("ARCHIVED", "Archived (Locked)");

    companion object {
        fun fromWire(value: String): ProjectStatus =
            entries.firstOrNull { it.wireValue == value } ?: PLANNING
    }
}

enum class MessageRole(val wireValue: String) {
    USER("USER"),
    BRAIN("BRAIN");

    companion object {
        fun fromWire(value: String): MessageRole =
            entries.firstOrNull { it.wireValue == value } ?: BRAIN
    }
}

enum class AppCategory(
    val wireValue: String,
    val displayLabel: String,
    val starterHint: String
) {
    CHATTING("CHATTING", "Chatting App", "Google Login, Realtime Firestore Chat, Profile & AI Reply"),
    SOCIAL("SOCIAL", "Social Media", "Feed, Profiles, Posts, Likes & Real-time Comments"),
    AI_ASSISTANT("AI_ASSISTANT", "AI Assistant", "Multilingual Chat, Vision Analysis & Task Tools"),
    UTILITY("UTILITY", "Utility App", "Calculator, Habit Tracker, Scanner & Smart Organizer"),
    EDUCATION("EDUCATION", "Education App", "Flashcards, Interactive Quizzes & Progress Analytics"),
    BUSINESS("BUSINESS", "Business & CRM", "Invoices, Inventory, Team Tasks & Dashboard"),
    ECOMMERCE("ECOMMERCE", "E-Commerce", "Product Catalog, Cart, Wishlist & Order Tracking"),
    GAME("GAME", "Casual Game", "Canvas Arcade / Puzzle Game with Scoreboards"),
    CUSTOM("CUSTOM", "Custom Android App", "Tailored Multi-Screen Jetpack Compose Application");

    companion object {
        fun fromWire(value: String): AppCategory =
            entries.firstOrNull { it.wireValue == value } ?: CUSTOM
    }
}

enum class BuildType(val wireValue: String, val displayLabel: String) {
    DEBUG("DEBUG", "Debug APK"),
    RELEASE("RELEASE", "Release APK (Server Signed)");

    companion object {
        fun fromWire(value: String): BuildType =
            entries.firstOrNull { it.wireValue == value } ?: DEBUG
    }
}

enum class BuildJobStatus(val wireValue: String, val displayLabel: String, val isTerminal: Boolean) {
    QUEUED("QUEUED", "Queued on Build Server", false),
    PREPARING_WORKSPACE("PREPARING_WORKSPACE", "Preparing Isolated Workspace", false),
    RUNNING_GRADLE("RUNNING_GRADLE", "Running Gradle Assemble", false),
    VALIDATING_APK("VALIDATING_APK", "Validating Signed APK", false),
    SUCCEEDED("SUCCEEDED", "Build Succeeded • Verified APK", true),
    FAILED("FAILED", "Build Failed", true);

    companion object {
        fun fromWire(value: String): BuildJobStatus =
            entries.firstOrNull { it.wireValue == value } ?: QUEUED
    }
}

enum class IconStylePreset(
    val wireValue: String,
    val displayLabel: String,
    val bgColorHex: String,
    val accentHex: String
) {
    NEURAL_CYBER("NEURAL_CYBER", "Neural Cyber", "#0D0E21", "#7C4DFF"),
    AURORA_CHAT("AURORA_CHAT", "Aurora Chat", "#071E26", "#00E5FF"),
    EMERALD_FINANCE("EMERALD_FINANCE", "Emerald Pulse", "#082019", "#1DE9B6"),
    CRIMSON_ARCADE("CRIMSON_ARCADE", "Crimson Arcade", "#260917", "#FF4081"),
    SLATE_PRODUCTIVITY("SLATE_PRODUCTIVITY", "Cobalt Studio", "#0F172A", "#38BDF8");

    companion object {
        fun fromWire(value: String): IconStylePreset =
            entries.firstOrNull { it.wireValue == value } ?: NEURAL_CYBER
    }
}

data class AppPermissionSpec(
    val manifestName: String,
    val shortLabel: String,
    val rationale: String
)

object PlayCompliantPermissions {
    val available: List<AppPermissionSpec> = listOf(
        AppPermissionSpec("android.permission.INTERNET", "Internet", "Network & Firebase API access"),
        AppPermissionSpec("android.permission.ACCESS_NETWORK_STATE", "Network State", "Check online connectivity"),
        AppPermissionSpec("android.permission.POST_NOTIFICATIONS", "Notifications", "Runtime alert & chat notifications (API 33+)"),
        AppPermissionSpec("android.permission.CAMERA", "Camera", "In-app photo/QR capture"),
        AppPermissionSpec("android.permission.RECORD_AUDIO", "Microphone", "Voice input & audio notes"),
        AppPermissionSpec("android.permission.ACCESS_COARSE_LOCATION", "Approximate Location", "Coarse city-level location")
    )
}

enum class BrainActivityPhase(val statusLabel: String, val detailLabel: String) {
    IDLE("Core Standby", "Ready for Bengali, English, or Bangla-English directives"),
    ANALYZING_INTENT("Analyzing Intent", "Detecting language & extracting actual user goal"),
    SELECTING_MODEL("Routing Model", "Selecting optimal provider & fallback via Model Hub"),
    PLANNING_SUBTASKS("Decomposing Task", "Breaking project into ordered architectural subtasks"),
    COORDINATING_AGENTS("Assigning Agents", "Mapping specialized agent roles to shared context"),
    STREAMING_RESPONSE("Synthesizing Output", "Streaming verified response across live providers"),
    READY("Orchestration Ready", "Saved to encrypted Cloud Firestore Workspace"),
    ERROR("Attention Required", "Error surfaced transparently from runtime layer")
}

data class AgentBlueprint(
    val id: String,
    val title: String,
    val responsibility: String,
    val partNumber: String,
    val isLiveInPart01: Boolean
)

object AgentCatalog {
    val allAgents: List<AgentBlueprint> = listOf(
        AgentBlueprint("master_planner", "Master Planner Agent", "Understands multilingual intent & decomposes software plans", "PART 01-06", true),
        AgentBlueprint("model_router", "Model Routing Orchestrator", "Selects providers, runs parallel comparisons & synthesizes consensus", "PART 01-02", true),
        AgentBlueprint("coding_agent", "Coding Agent", "Generates & modifies Kotlin, XML, Gradle & project files in workspace", "PART 03", true),
        AgentBlueprint("android_dev", "Android App Developer Agent", "Scaffolds Jetpack Compose UI, ViewModels & Material 3 screens", "PART 03-04", true),
        AgentBlueprint("web_dev", "Web Developer Agent", "Generates responsive React, Next.js & HTML/CSS project files", "PART 03", true),
        AgentBlueprint("backend_dev", "Backend Developer Agent", "Designs Firebase backends, proxy APIs & data pipelines", "PART 02-06", true),
        AgentBlueprint("uiux_agent", "UI/UX Design Agent", "Constructs Material 3 themes, adaptive icons & adaptive layouts", "PART 04", true),
        AgentBlueprint("database_agent", "Database Agent", "Authors Firestore schemas, zero-trust rules & test suites", "PART 04-06", true),
        AgentBlueprint("code_review", "Code Review Agent", "Runs static analysis, dependency checks & error detection", "PART 03", true),
        AgentBlueprint("bug_fixer", "Bug Fixer Agent", "Repairs detected syntax, dependency & Gradle build errors with restore points", "PART 03-05", true),
        AgentBlueprint("testing_agent", "Testing Agent", "Scaffolds JUnit/Robolectric tests & parses build/test results", "PART 03-05", true),
        AgentBlueprint("security_agent", "Security Agent", "Enforces zero-trust rules, App Check & backend secret isolation", "PART 01-06", true),
        AgentBlueprint("build_docs", "Build & APK Agent", "Dispatches isolated Linux Gradle builds & verifies downloadable APKs", "PART 05", true)
    )
}
