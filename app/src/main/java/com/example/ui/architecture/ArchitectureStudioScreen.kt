package com.example.ui.architecture

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.security.appCheckDiagnosticFlow
import com.example.core.security.signOutUser
import com.example.data.model.AgentBlueprint
import com.example.data.model.AgentCatalog
import com.example.ui.components.GlowGlassCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadgeChip
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyberBlue
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.NeonPurpleBright
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SurfaceCardElevated

private data class RoadmapPhase(
    val part: String,
    val title: String,
    val summary: String,
    val isCompletedOrActive: Boolean
)

private val PLATFORM_ROADMAP = listOf(
    RoadmapPhase("PART 01", "Core Architecture, Main UI, Master AI Brain & Secure Backend", "Live with Gemini AI Logic, App Check, Google Sign-In, Model Router & Firestore Memory.", true),
    RoadmapPhase("PART 02", "Multi-AI Model Hub & Authenticated Backend Proxy", "Honest live proxy connection for OpenAI GPT, Claude, DeepSeek & Meta Llama with parallel comparison, consensus & cost telemetry.", true),
    RoadmapPhase("PART 03", "Multi-Agent Coding Agent, Editor & Error Auto-Fixer", "File tree, monospace code editor, static analyzer, Bug Fixer AI & 1-tap version restore.", true),
    RoadmapPhase("PART 04", "MYRAA AI App Builder Studio", "Multilingual intake, minimal clarifications, adaptive icon style, least-privilege permissions & Android Compose project generator.", true),
    RoadmapPhase("PART 05", "Remote Linux Gradle APK Build Server & Download", "Isolated Gradle + Android SDK server, signed/verified APK checks & zero-permission SAF APK download.", true),
    RoadmapPhase("PART 06", "Cloud Firestore Zero-Trust Rules & Storage Security", "Additive user-owned subcollections, validated build_artifacts & live deployed security rules.", true),
    RoadmapPhase("PART 07", "AI Voice Assistant & Media Studio", "Streaming speech synthesis, voice interruption & Nano Banana studio.", false),
    RoadmapPhase("PART 08", "Full QA, Security Hardening & Production Readiness", "End-to-end Play Release review & production rollout.", false)
)

@Composable
fun ArchitectureStudioScreen(
    userDisplayName: String,
    userEmail: String,
    userId: String,
    onOpenSettings: () -> Unit = {},
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (onNavigateBack != null) {
        BackHandler(onBack = onNavigateBack)
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val appCheckDiagnostic by appCheckDiagnosticFlow.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "header") {
                SectionHeader(
                    title = stringResource(R.string.nav_architecture),
                    subtitle = "Security Foundation, 13-Agent Roster & Phased Build Status",
                    icon = Icons.Filled.Architecture,
                    trailing = {
                        Row {
                            IconButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(Icons.Filled.Settings, contentDescription = "Backend & Build Settings", tint = CyberBlue)
                            }
                            if (onNavigateBack != null) {
                                IconButton(
                                    onClick = onNavigateBack,
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = CyberBlue)
                                }
                            }
                        }
                    }
                )
            }

            item(key = "security_card") {
                GlowGlassCard(highlightBorder = true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VerifiedUser,
                            contentDescription = null,
                            tint = ElectricTeal
                        )
                        Text(
                            text = "Secure Backend & Session Foundation",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    SecurityInfoRow(label = "Signed-In Account", value = "$userDisplayName (${userEmail.ifBlank { "Google Account" }})")
                    SecurityInfoRow(label = "User Namespace", value = "users/${userId.take(10)}...")
                    SecurityInfoRow(label = "Firebase Project", value = "gen-lang-client-0645829429 (Dedicated)")
                    SecurityInfoRow(
                        label = "App Check Attestation",
                        value = "${appCheckDiagnostic.providerName} (${appCheckDiagnostic.status.label})"
                    )
                    SecurityInfoRow(label = "Client API Keys", value = "None (Zero-Trust App Check + Auth)")

                    OutlinedButton(
                        onClick = { signOutUser(credentialManager, scope) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonError),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("sign_out_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Sign Out",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sign Out of MYRAA Workspace")
                    }
                }
            }

            item(key = "agents_header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Groups,
                        contentDescription = null,
                        tint = NeonPurpleBright
                    )
                    Text(
                        text = "13 Specialized MYRAA Agents",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            items(AgentCatalog.allAgents, key = { it.id }) { agent ->
                AgentRosterCard(agent = agent)
            }

            item(key = "roadmap_header") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = CyberBlue
                    )
                    Text(
                        text = "Phased Platform Roadmap (Honest Status)",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            items(PLATFORM_ROADMAP, key = { it.part }) { phase ->
                Surface(
                    color = SurfaceCardElevated,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = phase.part,
                                style = MaterialTheme.typography.labelMedium,
                                color = CyberBlue
                            )
                            StatusBadgeChip(
                                label = if (phase.isCompletedOrActive) "ACTIVE NOW" else "UPCOMING",
                                accentColor = if (phase.isCompletedOrActive) ElectricTeal else AmberSignal
                            )
                        }
                        Text(
                            text = phase.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = phase.summary,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = CyberBlue
        )
    }
}

@Composable
private fun AgentRosterCard(agent: AgentBlueprint) {
    Surface(
        color = SurfaceCardElevated,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = agent.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = agent.responsibility,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            StatusBadgeChip(
                label = if (agent.isLiveInPart01) "ACTIVE • ${agent.partNumber}" else agent.partNumber,
                accentColor = if (agent.isLiveInPart01) ElectricTeal else NeonPurpleBright
            )
        }
    }
}
