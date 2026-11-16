package com.example.ui.builder

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AppCategory
import com.example.data.model.BrainLanguage
import com.example.data.model.IconStylePreset
import com.example.data.model.PlayCompliantPermissions
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
fun AppBuilderStudioScreen(
    studioViewModel: EngineeringStudioViewModel,
    selectedLanguage: BrainLanguage,
    onNavigateToWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by studioViewModel.uiState.collectAsStateWithLifecycle()
    var enhanceWithAi by remember { mutableStateOf(true) }

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
                    title = "MYRAA App Studio",
                    subtitle = "Describe any Android app in বাংলা, EN, or Banglish to generate a real compilable project",
                    icon = Icons.Filled.Android
                )
            }

            if (state.errorMessage != null) {
                item(key = "err_banner") {
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
            }

            item(key = "idea_and_category") {
                GlowGlassCard(highlightBorder = true) {
                    Text(
                        text = "1. App Concept & Category",
                        style = MaterialTheme.typography.titleMedium,
                        color = CyberBlue
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AppCategory.entries.forEach { cat ->
                            FilterChip(
                                selected = state.builderCategory == cat,
                                onClick = { studioViewModel.updateBuilderFields(category = cat) },
                                label = { Text(cat.displayLabel) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = state.builderIdeaPrompt,
                        onValueChange = { studioViewModel.updateBuilderFields(ideaPrompt = it) },
                        label = { Text("Describe screens, users & features (EN / বাংলা / Banglish)") },
                        placeholder = { Text(state.builderCategory.starterHint) },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("builder_idea_input")
                    )

                    OutlinedButton(
                        onClick = { studioViewModel.fetchMinimalClarifications(selectedLanguage) },
                        enabled = state.builderIdeaPrompt.isNotBlank() && !state.isAskingQuestions,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("builder_clarify_button")
                    ) {
                        if (state.isAskingQuestions) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = CyberBlue)
                        } else {
                            Icon(Icons.Filled.HelpOutline, contentDescription = null, tint = CyberBlue, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Analyze & Ask Minimal Clarifications")
                    }
                }
            }

            if (state.clarificationQuestions.isNotEmpty()) {
                item(key = "clarifications") {
                    GlowGlassCard {
                        Text(
                            text = "Quick Design Clarifications",
                            style = MaterialTheme.typography.titleMedium,
                            color = ElectricTeal
                        )
                        state.clarificationQuestions.forEach { q ->
                            val answer = state.clarificationAnswers[q.id].orEmpty()
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(q.question, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    q.suggestedOptions.forEach { opt ->
                                        FilterChip(
                                            selected = answer == opt,
                                            onClick = { studioViewModel.updateClarificationAnswer(q.id, opt) },
                                            label = { Text(opt) }
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = answer,
                                    onValueChange = { studioViewModel.updateClarificationAnswer(q.id, it) },
                                    label = { Text("Your preference") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            item(key = "identity_and_icon") {
                GlowGlassCard {
                    Text(
                        text = "2. Android Identity, Version & Adaptive Icon",
                        style = MaterialTheme.typography.titleMedium,
                        color = CyberBlue
                    )
                    OutlinedTextField(
                        value = state.builderAppName,
                        onValueChange = { studioViewModel.updateBuilderFields(appName = it.take(30)) },
                        label = { Text("App Name (Max 30 chars, Play compliant)") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("builder_app_name_input")
                    )
                    OutlinedTextField(
                        value = state.builderPackageName,
                        onValueChange = { studioViewModel.updateBuilderFields(packageName = it) },
                        label = { Text("Application Package ID") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("builder_package_input")
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = state.builderVersionName,
                            onValueChange = { studioViewModel.updateBuilderFields(versionName = it) },
                            label = { Text("Version Name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.builderVersionCode,
                            onValueChange = { studioViewModel.updateBuilderFields(versionCode = it.filter(Char::isDigit)) },
                            label = { Text("Version Code") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text("Adaptive Launcher Icon Preset", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconStylePreset.entries.forEach { preset ->
                            val selected = state.builderIconStyle == preset
                            val bg = Color(android.graphics.Color.parseColor(preset.bgColorHex))
                            val accent = Color(android.graphics.Color.parseColor(preset.accentHex))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) NeonPurple.copy(alpha = 0.2f) else SurfaceCardElevated)
                                    .border(1.dp, if (selected) accent else OutlineSubtle, RoundedCornerShape(12.dp))
                                    .clickable { studioViewModel.updateBuilderFields(iconStyle = preset) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(bg)
                                        .border(2.dp, accent, CircleShape)
                                )
                                Text(preset.displayLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }

            item(key = "permissions_and_generate") {
                GlowGlassCard(highlightBorder = true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Security, contentDescription = null, tint = ElectricTeal)
                        Text(
                            text = "3. Play-Compliant Least-Privilege Permissions",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    PlayCompliantPermissions.available.forEach { spec ->
                        val checked = spec.manifestName in state.builderPermissions
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { studioViewModel.toggleBuilderPermission(spec.manifestName) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { studioViewModel.toggleBuilderPermission(spec.manifestName) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(spec.shortLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                                Text(spec.rationale, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Include Zero-Trust Firestore Rules", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Switch(
                            checked = state.includeFirebaseRules,
                            onCheckedChange = { studioViewModel.updateBuilderFields(includeFirebase = it) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Customize Home UI via AI Agent", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text("Falls back safely to base template if AI snippet has syntax issues", style = MaterialTheme.typography.labelSmall, color = AmberSignal)
                        }
                        Switch(checked = enhanceWithAi, onCheckedChange = { enhanceWithAi = it })
                    }

                    Button(
                        onClick = {
                            studioViewModel.generateAppWorkspace(
                                language = selectedLanguage,
                                enhanceWithAi = enhanceWithAi,
                                onGeneratedOpenWorkspace = onNavigateToWorkspace
                            )
                        },
                        enabled = !state.isScaffoldingWorkspace && state.builderAppName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple, contentColor = Color.White),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("builder_generate_button")
                    ) {
                        if (state.isScaffoldingWorkspace) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Generating Android Project...")
                        } else {
                            Icon(Icons.Filled.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Generate Real Android Project Workspace")
                        }
                    }

                    state.statusMessage?.let { note ->
                        StatusBadgeChip(label = note, accentColor = NeonPurpleBright)
                    }
                }
            }
        }
    }
}
