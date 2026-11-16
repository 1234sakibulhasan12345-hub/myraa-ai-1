package com.example.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BuildCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.coding.IssueSeverity
import com.example.coding.StaticAnalysisReport
import com.example.data.model.ProjectVersion
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
fun DiagnosticsAndVersionsPane(
    report: StaticAnalysisReport,
    versions: List<ProjectVersion>,
    isFixingBusy: Boolean,
    hasBuildError: Boolean,
    onAutoFix: () -> Unit,
    onRestoreVersion: (ProjectVersion) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlowGlassCard(highlightBorder = report.hasErrors || hasBuildError) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (report.hasErrors) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = if (report.hasErrors) CrimsonError else ElectricTeal
                    )
                    Column {
                        Text(
                            text = "Static Syntax, Import & Play Diagnostics",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Scanned ${report.scannedFileCount} files • ${report.errorCount} error(s), ${report.warningCount} warning(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (report.hasErrors) CrimsonError else ElectricTeal
                        )
                    }
                }
            }

            Button(
                onClick = onAutoFix,
                enabled = !isFixingBusy && report.scannedFileCount > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (report.hasErrors || hasBuildError) CrimsonError else NeonPurple,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("auto_fix_button")
            ) {
                if (isFixingBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Bug Fixer AI Analyzing & Repairing...")
                } else {
                    Icon(Icons.Filled.BuildCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (report.hasErrors || hasBuildError) "Auto-Fix Errors with Bug Fixer AI" else "Run AI Code Review & Cleanup Pass")
                }
            }

            if (report.issues.isEmpty()) {
                Text(
                    text = "Zero syntax, secret leak, deprecated import, or Play policy issues found across workspace files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ElectricTeal
                )
            } else {
                report.issues.forEach { issue ->
                    val isErr = issue.severity == IssueSeverity.ERROR
                    Surface(color = SurfaceCardElevated, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${issue.filePath}:${issue.lineHint}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CyberBlue
                                )
                                StatusBadgeChip(
                                    label = issue.severity.label,
                                    accentColor = if (isErr) CrimsonError else AmberSignal
                                )
                            }
                            Text(issue.message, style = MaterialTheme.typography.bodyMedium, color = if (isErr) CrimsonError else AmberSignal)
                            Text("Fix: ${issue.suggestion}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        GlowGlassCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.History, contentDescription = null, tint = CyberBlue)
                Text(
                    text = "Version History & 1-Tap Undo (${versions.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (versions.isEmpty()) {
                Text(
                    text = "Snapshots are created automatically whenever App Studio, Coding Agent, or Manual Editor modifies project files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                versions.forEach { version ->
                    Surface(color = SurfaceCardElevated, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "v${version.versionNumber} • ${version.createdByAgent}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = CyberBlue
                                )
                                Text(
                                    text = version.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${version.fileEntries.size} file snapshot(s)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { onRestoreVersion(version) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("restore_version_${version.versionNumber}")
                            ) {
                                Icon(Icons.Filled.Restore, contentDescription = "Restore Snapshot", tint = ElectricTeal, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Undo/Restore", color = ObsidianBg.takeIf { false } ?: ElectricTeal)
                            }
                        }
                    }
                }
            }
        }
    }
}
