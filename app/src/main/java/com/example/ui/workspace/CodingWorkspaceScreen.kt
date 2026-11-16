package com.example.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.BrainLanguage
import com.example.ui.brain.StreamUiState
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadgeChip
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyberBlue
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.ObsidianBg

enum class WorkspaceSubTab(val label: String) {
    CODE_EDITOR("Code & Agent"),
    ERRORS_VERSIONS("Errors & Undo"),
    APK_BUILD("APK Build")
}

@Composable
fun CodingWorkspaceScreen(
    studioViewModel: EngineeringStudioViewModel,
    selectedLanguage: BrainLanguage,
    onOpenSettings: () -> Unit,
    initialSubTab: WorkspaceSubTab = WorkspaceSubTab.CODE_EDITOR,
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (onNavigateBack != null) {
        BackHandler(onBack = onNavigateBack)
    }
    val context = LocalContext.current
    val state by studioViewModel.uiState.collectAsStateWithLifecycle()
    val project by studioViewModel.activeProject.collectAsStateWithLifecycle()
    val filesState by studioViewModel.projectFilesState.collectAsStateWithLifecycle()
    val versionsState by studioViewModel.projectVersionsState.collectAsStateWithLifecycle()
    val jobsState by studioViewModel.buildJobsState.collectAsStateWithLifecycle()
    val artifactsState by studioViewModel.buildArtifactsState.collectAsStateWithLifecycle()
    val catalog by studioViewModel.catalogState.collectAsStateWithLifecycle()

    var activeTab by remember(initialSubTab) { mutableStateOf(initialSubTab) }

    val files = (filesState as? StreamUiState.Success)?.data.orEmpty()
    val versions = (versionsState as? StreamUiState.Success)?.data.orEmpty()
    val buildJobs = (jobsState as? StreamUiState.Success)?.data.orEmpty()
    val artifacts = (artifactsState as? StreamUiState.Success)?.data.orEmpty()
    val liveModels = catalog.flatMap { it.models }.filter { it.isLiveInCurrentBuild }

    // Zero-permission Storage Access Framework launcher for exporting verified APK binaries
    val safApkSaver = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        if (uri != null) {
            studioViewModel.downloadArtifactToSafUri(context, uri)
        } else {
            studioViewModel.clearPendingApkExport()
        }
    }

    LaunchedEffect(state.pendingExportArtifact) {
        val pending = state.pendingExportArtifact
        if (pending != null) {
            safApkSaver.launch(pending.fileName.ifBlank { "myraa-app.apk" })
        }
    }

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "header") {
                SectionHeader(
                    title = project?.name ?: "Coding Workspace",
                    subtitle = project?.let { "${it.packageName} • v${it.appVersionName} • ${files.size} files" }
                        ?: "Select or generate a project in App Studio",
                    icon = Icons.Filled.DeveloperMode,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            StatusBadgeChip(
                                label = if (state.staticAnalysis.hasErrors) "${state.staticAnalysis.errorCount} ERR" else "CLEAN",
                                accentColor = if (state.staticAnalysis.hasErrors) CrimsonError else ElectricTeal
                            )
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

            item(key = "subtab_bar") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WorkspaceSubTab.entries.forEach { tab ->
                        val label = when (tab) {
                            WorkspaceSubTab.ERRORS_VERSIONS -> "${tab.label} (${state.staticAnalysis.errorCount})"
                            WorkspaceSubTab.APK_BUILD -> "${tab.label} (${artifacts.size})"
                            else -> tab.label
                        }
                        FilterChip(
                            selected = activeTab == tab,
                            onClick = { activeTab = tab },
                            label = { Text(label) },
                            leadingIcon = if (tab == WorkspaceSubTab.APK_BUILD) {
                                { Icon(Icons.Filled.Build, contentDescription = null) }
                            } else null,
                            modifier = Modifier.testTag("workspace_tab_${tab.name.lowercase()}")
                        )
                    }
                }
            }

            if (state.errorMessage != null) {
                item(key = "error_box") {
                    Surface(
                        color = CrimsonError.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.errorMessage.orEmpty(),
                            color = CrimsonError,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            } else if (state.statusMessage != null) {
                item(key = "status_box") {
                    Surface(
                        color = CyberBlue.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(state.statusMessage.orEmpty(), color = CyberBlue, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            item(key = "active_pane_${activeTab.name}") {
                when (activeTab) {
                    WorkspaceSubTab.CODE_EDITOR -> {
                        FileTreeAndEditorPane(
                            state = state,
                            files = files,
                            liveModels = liveModels,
                            selectedLanguage = selectedLanguage,
                            onDirectiveChange = studioViewModel::updateCodingDirectiveDraft,
                            onSelectCodingModel = studioViewModel::selectCodingModel,
                            onRunCodingAgent = studioViewModel::executeCodingDirective,
                            onSelectFile = studioViewModel::selectFile,
                            onCreateFile = studioViewModel::createNewFile,
                            onDeleteFile = studioViewModel::deleteFile,
                            onEditContent = studioViewModel::updateEditorDraft,
                            onSaveContent = studioViewModel::saveCurrentFileEdit
                        )
                    }

                    WorkspaceSubTab.ERRORS_VERSIONS -> {
                        DiagnosticsAndVersionsPane(
                            report = state.staticAnalysis,
                            versions = versions,
                            isFixingBusy = state.isCodingBusy,
                            hasBuildError = !state.activeRemoteBuild?.errorSummary.isNullOrBlank(),
                            onAutoFix = studioViewModel::autoFixWorkspaceErrors,
                            onRestoreVersion = studioViewModel::restoreVersion
                        )
                    }

                    WorkspaceSubTab.APK_BUILD -> {
                        BuildAndApkPanel(
                            state = state,
                            buildJobs = buildJobs,
                            artifacts = artifacts,
                            onStartBuild = studioViewModel::startRemoteApkBuild,
                            onExportVerifiedApk = studioViewModel::prepareApkExport,
                            onOpenSettings = onOpenSettings,
                            onAutoFixBuildError = {
                                activeTab = WorkspaceSubTab.ERRORS_VERSIONS
                                studioViewModel.autoFixWorkspaceErrors()
                            }
                        )
                    }
                }
            }
        }
    }
}
