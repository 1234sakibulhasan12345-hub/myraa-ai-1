package com.example.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.provider.AiModelDescriptor
import com.example.data.model.BrainLanguage
import com.example.data.model.ProjectFile
import com.example.ui.components.GlowGlassCard
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FileTreeAndEditorPane(
    state: EngineeringStudioUiState,
    files: List<ProjectFile>,
    liveModels: List<AiModelDescriptor>,
    selectedLanguage: BrainLanguage,
    onDirectiveChange: (String) -> Unit,
    onSelectCodingModel: (AiModelDescriptor) -> Unit,
    onRunCodingAgent: (BrainLanguage) -> Unit,
    onSelectFile: (ProjectFile) -> Unit,
    onCreateFile: (String) -> Unit,
    onDeleteFile: (ProjectFile) -> Unit,
    onEditContent: (String) -> Unit,
    onSaveContent: () -> Unit
) {
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFilePath by remember { mutableStateOf("app/src/main/java/com/myraa/app/NewHelper.kt") }
    val activeFile = files.firstOrNull { it.path == state.selectedFilePath }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlowGlassCard(highlightBorder = true) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.SmartToy, contentDescription = null, tint = CyberBlue)
                Text("Multi-AI Coding Agent", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }

            OutlinedTextField(
                value = state.codingDirectiveDraft,
                onValueChange = onDirectiveChange,
                label = { Text("Instruction (e.g. 'Add login screen' or 'Fix Gradle deps')") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("coding_directive_input")
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                liveModels.forEach { m ->
                    val key = "${m.providerId.wireId}:${m.modelId}"
                    FilterChip(
                        selected = state.selectedCodingModelKey == key,
                        onClick = { onSelectCodingModel(m) },
                        label = { Text("${m.displayName} (${m.providerId.wireId.substringBefore('_')})") }
                    )
                }
            }

            Button(
                onClick = { onRunCodingAgent(selectedLanguage) },
                enabled = state.codingDirectiveDraft.isNotBlank() && !state.isCodingBusy,
                colors = ButtonDefaults.buttonColors(containerColor = NeonPurple, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("run_coding_agent_button")
            ) {
                if (state.isCodingBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Coding Agent Generating Files...")
                } else {
                    Text("Plan, Generate & Save Code Changes")
                }
            }

            state.latestCodingSummary?.let { summary ->
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = ElectricTeal)
            }
            if (state.latestSubtaskAssignments.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.latestSubtaskAssignments.forEach { step ->
                        StatusBadgeChip(label = "${step.assignedAgent}: ${step.subtaskTitle}", accentColor = NeonPurpleBright)
                    }
                }
            }
        }

        GlowGlassCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Code, contentDescription = null, tint = ElectricTeal)
                    Text("File Tree (${files.size} files)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                OutlinedButton(
                    onClick = { showNewFileDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New File", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New File")
                }
            }

            if (files.isEmpty()) {
                Text(
                    text = "Workspace has no files yet. Open App Studio or run a Coding Agent instruction above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    files.forEach { file ->
                        val selected = file.path == state.selectedFilePath
                        Surface(
                            color = if (selected) NeonPurple.copy(alpha = 0.22f) else SurfaceCardElevated,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .border(1.dp, if (selected) CyberBlue else OutlineSubtle, RoundedCornerShape(10.dp))
                                .clickable { onSelectFile(file) }
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(file.fileName, style = MaterialTheme.typography.labelMedium, color = if (selected) CyberBlue else MaterialTheme.colorScheme.onSurface)
                                Text(file.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (activeFile != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(activeFile.path, style = MaterialTheme.typography.labelMedium, color = CyberBlue)
                        Text(
                            text = "Updated by ${activeFile.updatedByAgent} • ${activeFile.sizeBytes} bytes" +
                                if (state.isEditorDirty) " • Unsaved edits" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isEditorDirty) AmberSignal else ElectricTeal
                        )
                    }
                    Row {
                        IconButton(
                            onClick = { onDeleteFile(activeFile) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete File", tint = CrimsonError)
                        }
                        Button(
                            onClick = onSaveContent,
                            enabled = state.isEditorDirty,
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricTeal, contentColor = ObsidianBg),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("save_file_button")
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = "Save File", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save")
                        }
                    }
                }

                OutlinedTextField(
                    value = state.editorContentDraft,
                    onValueChange = onEditContent,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 420.dp)
                        .background(ObsidianBg, RoundedCornerShape(12.dp))
                        .testTag("code_editor_field")
                )
            }
        }
    }

    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("Create Workspace File") },
            text = {
                OutlinedTextField(
                    value = newFilePath,
                    onValueChange = { newFilePath = it },
                    label = { Text("Relative POSIX path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNewFileDialog = false
                        onCreateFile(newFilePath)
                    },
                    enabled = newFilePath.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) { Text("Cancel") }
            }
        )
    }
}
