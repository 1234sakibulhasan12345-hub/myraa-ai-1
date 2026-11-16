package com.example.ui.projects

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.BrainLanguage
import com.example.data.model.BrainProject
import com.example.data.model.ProjectStatus
import com.example.data.model.TargetFramework
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

@Composable
fun ProjectsMemoryScreen(
    viewModel: SuperBrainViewModel,
    onResumeInBrain: () -> Unit,
    onOpenWorkspace: () -> Unit = {},
    onOpenBuildConsole: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenArchitectureStudio: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val projectsState by viewModel.projectsState.collectAsStateWithLifecycle()
    val consoleState by viewModel.consoleState.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var projectPendingDelete by remember { mutableStateOf<BrainProject?>(null) }

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
                    title = stringResource(R.string.nav_projects),
                    subtitle = "Persistent Project Memory in Cloud Firestore (zero secrets stored)",
                    icon = Icons.Filled.Memory,
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onOpenArchitectureStudio,
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("open_architecture_button")
                            ) {
                                Icon(Icons.Filled.Architecture, contentDescription = "Architecture & Security Studio", tint = NeonPurpleBright)
                            }
                            IconButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("projects_settings_button")
                            ) {
                                Icon(Icons.Filled.Settings, contentDescription = "Backend & Build Settings", tint = CyberBlue)
                            }
                            Button(
                                onClick = { showCreateDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonPurple,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("new_project_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "Create Project",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("New")
                            }
                        }
                    }
                )
            }

            when (val state = projectsState) {
                is StreamUiState.Loading -> {
                    item(key = "loading") {
                        GlowGlassCard {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = CyberBlue,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Syncing Project Memory from Firestore...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                is StreamUiState.Error -> {
                    item(key = "error") {
                        GlowGlassCard {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = CrimsonError
                            )
                        }
                    }
                }

                is StreamUiState.Success -> {
                    val projects = state.data
                    if (projects.isEmpty()) {
                        item(key = "empty") {
                            GlowGlassCard(highlightBorder = true) {
                                Icon(
                                    imageVector = Icons.Filled.FolderSpecial,
                                    contentDescription = null,
                                    tint = CyberBlue,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "No Project Memory Yet",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Create a project workspace or send your first prompt in the Master AI Brain tab. Requirements, subtasks, framework choice, and history persist across sessions.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Button(
                                    onClick = { showCreateDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = NeonPurple)
                                ) {
                                    Text("Create First Project")
                                }
                            }
                        }
                    } else {
                        items(projects, key = { it.id }) { project ->
                            val isActive = project.id == consoleState.activeProjectId
                            ProjectMemoryCard(
                                project = project,
                                isActive = isActive,
                                onResume = {
                                    viewModel.selectProject(project.id)
                                    onResumeInBrain()
                                },
                                onOpenCode = {
                                    viewModel.selectProject(project.id)
                                    onOpenWorkspace()
                                },
                                onOpenBuilds = {
                                    viewModel.selectProject(project.id)
                                    onOpenBuildConsole()
                                },
                                onArchive = { viewModel.archiveProject(project.id) },
                                onRequestDelete = { projectPendingDelete = project }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, framework, reqs ->
                showCreateDialog = false
                viewModel.createNewProject(name, framework, reqs)
                onResumeInBrain()
            }
        )
    }

    projectPendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { projectPendingDelete = null },
            title = { Text("Delete Project Memory?") },
            text = {
                Text("This permanently deletes '${target.name}' from your Cloud Firestore workspace.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProject(target.id)
                        projectPendingDelete = null
                    }
                ) {
                    Text("Delete", color = CrimsonError)
                }
            },
            dismissButton = {
                TextButton(onClick = { projectPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectMemoryCard(
    project: BrainProject,
    isActive: Boolean,
    onResume: () -> Unit,
    onOpenCode: () -> Unit,
    onOpenBuilds: () -> Unit,
    onArchive: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val statusEnum = ProjectStatus.fromWire(project.status)
    val isArchived = statusEnum == ProjectStatus.ARCHIVED
    val statusColor = when (statusEnum) {
        ProjectStatus.ACTIVE -> ElectricTeal
        ProjectStatus.PLANNING -> CyberBlue
        ProjectStatus.PAUSED -> AmberSignal
        ProjectStatus.ARCHIVED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    GlowGlassCard(highlightBorder = isActive) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${TargetFramework.fromWire(project.targetFramework).displayLabel} • ${project.packageName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberBlue
                )
            }

            if (isActive) {
                StatusBadgeChip(label = "ACTIVE SESSION", accentColor = NeonPurpleBright)
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatusBadgeChip(label = statusEnum.displayLabel, accentColor = statusColor)
            StatusBadgeChip(
                label = BrainLanguage.fromWire(project.detectedLanguage).nativeLabel,
                accentColor = CyberBlue
            )
            StatusBadgeChip(
                label = "${project.fileCount} Files",
                accentColor = ElectricTeal
            )
            if (project.latestBuildStatus.isNotBlank()) {
                StatusBadgeChip(
                    label = "Build: ${project.latestBuildStatus}",
                    accentColor = AmberSignal
                )
            }
        }

        if (project.requirementsSummary.isNotBlank()) {
            Text(
                text = project.requirementsSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (project.architectureSummary.isNotBlank()) {
            Surface(
                color = SurfaceCardElevated,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Architecture: ${project.architectureSummary}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onResume,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("resume_project_button_${project.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Resume in Brain",
                        tint = ElectricTeal,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Brain")
                }
                OutlinedButton(
                    onClick = onOpenCode,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.Code, contentDescription = "Open Workspace", tint = CyberBlue, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Code")
                }
                IconButton(
                    onClick = onOpenBuilds,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.Build, contentDescription = "APK Build History", tint = NeonPurpleBright)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!isArchived) {
                    IconButton(
                        onClick = onArchive,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Archive,
                            contentDescription = "Archive & Lock Project",
                            tint = AmberSignal
                        )
                    }
                }
                IconButton(
                    onClick = onRequestDelete,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Delete Project",
                        tint = CrimsonError
                    )
                }
            }
        }
    }
}
