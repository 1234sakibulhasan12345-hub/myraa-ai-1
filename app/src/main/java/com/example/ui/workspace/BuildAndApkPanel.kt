package com.example.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.BuildArtifact
import com.example.data.model.BuildJob
import com.example.data.model.BuildJobStatus
import com.example.data.model.BuildType
import com.example.ui.components.GlowGlassCard
import com.example.ui.components.StatusBadgeChip
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyberBlue
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SurfaceCardElevated

@Composable
fun BuildAndApkPanel(
    state: EngineeringStudioUiState,
    buildJobs: List<BuildJob>,
    artifacts: List<BuildArtifact>,
    onStartBuild: (BuildType) -> Unit,
    onExportVerifiedApk: (BuildArtifact) -> Unit,
    onOpenSettings: () -> Unit,
    onAutoFixBuildError: () -> Unit
) {
    var selectedBuildType by remember(state.preferredBuildType) { mutableStateOf(state.preferredBuildType) }
    val remoteJob = state.activeRemoteBuild
    val serverConfigured = state.backendUrlDraft.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlowGlassCard(highlightBorder = true) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Build, contentDescription = null, tint = CyberBlue)
                    Column {
                        Text("Remote Linux Gradle APK Server", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = if (serverConfigured) state.backendUrlDraft else "Backend Build Server URL not configured",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (serverConfigured) ElectricTeal else AmberSignal
                        )
                    }
                }
                OutlinedButton(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = "Configure Server", modifier = Modifier.size(16.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BuildType.entries.forEach { bt ->
                    FilterChip(
                        selected = selectedBuildType == bt,
                        onClick = { selectedBuildType = bt },
                        label = { Text(bt.displayLabel) }
                    )
                }
            }

            Button(
                onClick = { onStartBuild(selectedBuildType) },
                enabled = !state.isSubmittingBuild && serverConfigured,
                colors = ButtonDefaults.buttonColors(containerColor = NeonPurple, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("run_remote_build_button")
            ) {
                if (state.isSubmittingBuild || (remoteJob != null && !remoteJob.status.isTerminal)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(remoteJob?.status?.displayLabel ?: "Submitting Build...")
                } else {
                    Text("Dispatch Real Remote Gradle Build (${selectedBuildType.wireValue})")
                }
            }

            if (!serverConfigured) {
                Text(
                    text = "Real APK compilation runs on the isolated Node.js + Android SDK server (/backend/). Configure your HTTPS or emulator URL in Settings to enable genuine Gradle APK builds. No fake APK outputs are generated.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AmberSignal
                )
            }

            state.apkVerificationNotice?.let { notice ->
                StatusBadgeChip(label = notice, accentColor = ElectricTeal)
            }
        }

        if (remoteJob != null) {
            val succeeded = remoteJob.status == BuildJobStatus.SUCCEEDED
            val failed = remoteJob.status == BuildJobStatus.FAILED
            GlowGlassCard(highlightBorder = succeeded || failed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Terminal, contentDescription = null, tint = if (failed) CrimsonError else CyberBlue)
                        Text("Live Build Job: ${remoteJob.jobId.take(12)}", style = MaterialTheme.typography.titleMedium)
                    }
                    StatusBadgeChip(
                        label = remoteJob.status.displayLabel,
                        accentColor = when {
                            succeeded -> ElectricTeal
                            failed -> CrimsonError
                            else -> AmberSignal
                        }
                    )
                }

                if (failed && remoteJob.errorSummary.isNotBlank()) {
                    Text(remoteJob.errorSummary, style = MaterialTheme.typography.bodyMedium, color = CrimsonError)
                    OutlinedButton(
                        onClick = onAutoFixBuildError,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Text("Send Error Logs to Bug Fixer AI", color = AmberSignal)
                    }
                }

                if (remoteJob.logs.isNotBlank()) {
                    Surface(
                        color = ObsidianBg,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 260.dp)
                    ) {
                        Text(
                            text = remoteJob.logs,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(10.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }

        GlowGlassCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Verified, contentDescription = null, tint = ElectricTeal)
                Text("Verified APK Artifacts (${artifacts.size})", style = MaterialTheme.typography.titleMedium)
            }

            if (artifacts.isEmpty()) {
                Text(
                    text = "Only server-built, signature & SHA-256 verified APK files appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                artifacts.forEach { artifact ->
                    val verifiedReady = artifact.apkVerified && artifact.sizeBytes > 0L && artifact.sha256.length == 64
                    Surface(color = SurfaceCardElevated, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(artifact.fileName, style = MaterialTheme.typography.titleMedium, color = ElectricTeal)
                                StatusBadgeChip(
                                    label = "${artifact.buildType} • ${artifact.sizeBytes / 1024} KB",
                                    accentColor = CyberBlue
                                )
                            }
                            Text(
                                text = "SHA-256: ${artifact.sha256.take(32)}...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { onExportVerifiedApk(artifact) },
                                enabled = verifiedReady && !state.isDownloadingApk,
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricTeal, contentColor = ObsidianBg),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .testTag("download_apk_${artifact.jobId}")
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = "Save Verified APK", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (state.isDownloadingApk) "Verifying & Saving APK..." else "Download & Save Verified APK (SAF)")
                            }
                        }
                    }
                }
            }
        }

        if (buildJobs.isNotEmpty()) {
            GlowGlassCard {
                Text("Build History (${buildJobs.size})", style = MaterialTheme.typography.titleMedium)
                buildJobs.take(8).forEach { job ->
                    val st = BuildJobStatus.fromWire(job.status)
                    Surface(color = SurfaceCardElevated, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${job.appName} v${job.versionName} (${job.buildType})", style = MaterialTheme.typography.labelLarge)
                                Text("${job.durationMs / 1000}s • ${job.packageName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusBadgeChip(
                                label = st.wireValue,
                                accentColor = if (st == BuildJobStatus.SUCCEEDED) ElectricTeal else if (st == BuildJobStatus.FAILED) CrimsonError else AmberSignal
                            )
                        }
                    }
                }
            }
        }
    }
}
