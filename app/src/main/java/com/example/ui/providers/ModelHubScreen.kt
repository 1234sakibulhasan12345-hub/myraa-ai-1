package com.example.ui.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ai.provider.TokenAndCostTracker
import com.example.data.model.RoutingPolicy
import com.example.ui.brain.StreamUiState
import com.example.ui.brain.SuperBrainViewModel
import com.example.ui.components.GlowGlassCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadgeChip
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyberBlue
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeonPurpleBright
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.SurfaceCardElevated
import com.example.ui.workspace.EngineeringStudioViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelHubScreen(
    viewModel: SuperBrainViewModel,
    studioViewModel: EngineeringStudioViewModel,
    onOpenProjectSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val consoleState by viewModel.consoleState.collectAsStateWithLifecycle()
    val usageState by viewModel.usageLogsState.collectAsStateWithLifecycle()
    val providers by studioViewModel.freeTierCatalogState.collectAsStateWithLifecycle()
    val studioState by studioViewModel.uiState.collectAsStateWithLifecycle()
    val appCheckDiagnostic by studioViewModel.appCheckStatus.collectAsStateWithLifecycle()
    val logs = (usageState as? StreamUiState.Success)?.data.orEmpty()
    val allFreeModels = providers.flatMap { it.models }.filter { it.isFreeTier }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 880.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "header") {
                SectionHeader(
                    title = stringResource(R.string.nav_providers),
                    subtitle = "Verified Free-Tier AI Models Only • Auto-Fallback, Vault Keys & Honest Live Status",
                    icon = Icons.Filled.Hub,
                    trailing = {
                        OutlinedButton(
                            onClick = onOpenProjectSettings,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("model_hub_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Backend Settings",
                                tint = CyberBlue,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Backend")
                        }
                    }
                )
            }

            item(key = "proxy_connection_status") {
                GlowGlassCard(highlightBorder = studioState.backendUrlDraft.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "External Multi-AI Proxy Server",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = studioState.backendUrlDraft.ifBlank { "No remote server URL configured" },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (studioState.backendUrlDraft.isNotBlank()) CyberBlue else AmberSignal
                            )
                        }
                        Button(
                            onClick = { studioViewModel.verifyBackendAndProviders() },
                            enabled = !studioState.isProbingBackend,
                            colors = ButtonDefaults.buttonColors(containerColor = NeonPurple),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("verify_backend_button")
                        ) {
                            if (studioState.isProbingBackend) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "Verify Live Connection", modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Probe")
                        }
                    }
                    Text(
                        text = studioState.lastProbeSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item(key = "app_check_diagnostics") {
                val context = androidx.compose.ui.platform.LocalContext.current
                AppCheckDiagnosticsCard(
                    diagnostic = appCheckDiagnostic,
                    isVerifying = studioState.isVerifyingAppCheck,
                    onVerifyAndRefresh = { studioViewModel.verifyAndRefreshAppCheckToken() },
                    onRegenerateToken = { studioViewModel.regenerateDebugToken(context) },
                    onOpenSettings = onOpenProjectSettings
                )
            }

            item(key = "parallel_compare_card") {
                GlowGlassCard(highlightBorder = true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null, tint = CyberBlue)
                        Text(
                            text = "Parallel Multi-AI Comparison & Consensus",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    OutlinedTextField(
                        value = studioState.comparisonPrompt,
                        onValueChange = studioViewModel::updateComparisonPrompt,
                        label = { Text("Prompt to evaluate side-by-side (বাংলা / EN / Code)") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("compare_prompt_input")
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val liveProviderMap = providers.associateBy { it.providerId }
                        allFreeModels.forEach { m ->
                            val key = "${m.providerId.wireId}:${m.modelId}"
                            val selected = key in studioState.selectedCompareModelKeys
                            val provStatus = liveProviderMap[m.providerId]?.status
                            FilterChip(
                                selected = selected,
                                onClick = { studioViewModel.toggleCompareModel(m) },
                                label = {
                                    Text(
                                        if (m.isLiveInCurrentBuild) {
                                            "FREE • ${m.displayName}"
                                        } else {
                                            "${m.displayName} (${provStatus?.label ?: "Offline"})"
                                        }
                                    )
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { studioViewModel.runParallelComparison(consoleState.selectedLanguage) },
                            enabled = studioState.comparisonPrompt.isNotBlank() && !studioState.isComparing,
                            colors = ButtonDefaults.buttonColors(containerColor = CyberBlue, contentColor = ObsidianBg),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("run_compare_button")
                        ) {
                            Text(if (studioState.isComparing) "Running..." else "Run Parallel")
                        }
                        OutlinedButton(
                            onClick = { studioViewModel.synthesizeConsensus(consoleState.selectedLanguage) },
                            enabled = studioState.parallelResponses.any { it.errorMessage == null } && !studioState.isComparing,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("synthesize_consensus_button")
                        ) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = ElectricTeal, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Consensus")
                        }
                    }

                    studioState.parallelResponses.forEach { resp ->
                        Surface(
                            color = SurfaceCardElevated,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${resp.providerName} • ${resp.displayModelName}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (resp.errorMessage == null) CyberBlue else CrimsonError
                                    )
                                    StatusBadgeChip(
                                        label = if (resp.errorMessage == null) {
                                            "${resp.latencyMs}ms • ${resp.promptTokens + resp.completionTokens}t"
                                        } else {
                                            "OFFLINE"
                                        },
                                        accentColor = if (resp.errorMessage == null) ElectricTeal else CrimsonError
                                    )
                                }
                                Text(
                                    text = resp.errorMessage ?: resp.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (resp.errorMessage == null) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        CrimsonError
                                    }
                                )
                            }
                        }
                    }

                    studioState.consensusResult?.let { consensus ->
                        Surface(
                            color = ElectricTeal.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, ElectricTeal.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Synthesized Engineering Consensus",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = ElectricTeal
                                    )
                                    StatusBadgeChip(
                                        label = TokenAndCostTracker.formatUsdMicros(consensus.totalEstimatedCostUsdMicros),
                                        accentColor = CyberBlue
                                    )
                                }
                                Text(
                                    text = consensus.consensusText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            item(key = "routing_policy") {
                GlowGlassCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Speed, contentDescription = null, tint = CyberBlue)
                        Text(
                            text = "Active Routing Policy",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    RoutingPolicy.entries.forEach { policy ->
                        val selected = consoleState.selectedRoutingPolicy == policy
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) NeonPurple.copy(alpha = 0.16f) else SurfaceCardElevated)
                                .border(
                                    width = 1.dp,
                                    color = if (selected) NeonPurple else OutlineSubtle,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    viewModel.updatePreferences(consoleState.selectedLanguage, policy)
                                }
                                .padding(12.dp)
                                .testTag("routing_policy_${policy.wireValue.lowercase()}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = { viewModel.updatePreferences(consoleState.selectedLanguage, policy) },
                                colors = RadioButtonDefaults.colors(selectedColor = NeonPurpleBright)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(policy.displayLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text(policy.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            item(key = "telemetry_summary") {
                UsageTelemetryCard(logs = logs)
            }

            items(providers, key = { it.providerId.wireId }) { provider ->
                ProviderDescriptorCard(provider = provider)
            }
        }
    }
}
