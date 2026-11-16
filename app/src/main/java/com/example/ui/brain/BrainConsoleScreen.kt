package com.example.ui.brain

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ai.provider.ModelRouter
import com.example.core.security.AppCheckHealthStatus
import com.example.data.model.BrainLanguage
import com.example.data.model.BrainMessage
import com.example.data.model.BrainProject
import com.example.data.model.MessageRole
import com.example.ui.components.BrainActivityStrip
import com.example.ui.components.GlowGlassCard
import com.example.ui.components.StatusBadgeChip
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CosmicSurface
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyberBlue
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.NeonPurpleBright
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.OutlineSubtle
import com.example.ui.theme.SurfaceCard
import com.example.ui.theme.SurfaceCardElevated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrainConsoleScreen(
    viewModel: SuperBrainViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenProjectsTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val consoleState by viewModel.consoleState.collectAsStateWithLifecycle()
    val messagesState by viewModel.activeMessagesState.collectAsStateWithLifecycle()
    val activeProject by viewModel.activeProject.collectAsStateWithLifecycle()
    val freeProviders by viewModel.freeTierCatalogState.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var showAppCheckFixDialog by remember { mutableStateOf(false) }
    val appCheckDiagnostic by viewModel.appCheckStatus.collectAsStateWithLifecycle()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bitmap = decodeScaledBitmap(context, uri, maxDim = 1024)
                if (bitmap != null) {
                    viewModel.attachBitmap(bitmap)
                } else {
                    snackbarHostState.showSnackbar("Could not read selected image.")
                }
            }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull().orEmpty()
            if (spoken.isNotBlank()) {
                val merged = if (consoleState.promptDraft.isBlank()) {
                    spoken
                } else {
                    "${consoleState.promptDraft.trim()} $spoken"
                }
                viewModel.updatePromptDraft(merged)
            }
        }
    }

    val messages = (messagesState as? StreamUiState.Success)?.data.orEmpty()

    LaunchedEffect(messages.size, consoleState.streamingText) {
        if (messages.isNotEmpty() || consoleState.streamingText.isNotBlank()) {
            val targetIndex = (messages.size - 1).coerceAtLeast(0)
            listState.animateScrollToItem(targetIndex)
        }
    }

    LaunchedEffect(consoleState.statusBanner) {
        val banner = consoleState.statusBanner
        if (banner != null) {
            snackbarHostState.showSnackbar(banner)
            viewModel.clearStatusBanner()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .align(Alignment.TopCenter)
        ) {
            // Top Command Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.img_brain_avatar),
                            contentDescription = stringResource(R.string.brain_avatar_desc),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .border(1.dp, NeonPurple.copy(alpha = 0.7f), CircleShape)
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.brand_headline),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(R.string.master_brain_subtitle),
                                style = MaterialTheme.typography.labelSmall,
                                color = CyberBlue
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenProjectsTab,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("active_project_chip")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = "Active Project",
                            tint = NeonPurpleBright,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = activeProject?.name?.take(16) ?: "New Session",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                }

                // Animated Neural Core & Activity Status Strip
                val activeModelLabel = consoleState.latestRouting?.selectedModel?.modelId
                    ?: ModelRouter.MODEL_GEMINI_FLASH
                BrainActivityStrip(
                    phase = consoleState.activityPhase,
                    activeModelLabel = activeModelLabel,
                    isActive = consoleState.isGenerating
                )

                // Multilingual Preference Chips & Quick App Check Phone Fix
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val appCheckTint = when (appCheckDiagnostic.status) {
                        AppCheckHealthStatus.TOKEN_VALID -> ElectricTeal
                        AppCheckHealthStatus.TOKEN_INVALID -> CrimsonError
                        AppCheckHealthStatus.RATE_LIMITED, AppCheckHealthStatus.REFRESHING, AppCheckHealthStatus.AUTO_REGISTERING -> AmberSignal
                        else -> CyberBlue
                    }
                    val appCheckChipLabel = when (appCheckDiagnostic.status) {
                        AppCheckHealthStatus.TOKEN_VALID -> "App Check Verified"
                        AppCheckHealthStatus.TOKEN_INVALID -> "Fix App Check"
                        AppCheckHealthStatus.RATE_LIMITED -> "App Check Cooldown"
                        AppCheckHealthStatus.AUTO_REGISTERING -> "Auto-Registering..."
                        AppCheckHealthStatus.REFRESHING -> "Verifying..."
                        else -> "Debug Token"
                    }
                    FilterChip(
                        selected = appCheckDiagnostic.status == AppCheckHealthStatus.TOKEN_INVALID ||
                            appCheckDiagnostic.status == AppCheckHealthStatus.RATE_LIMITED,
                        onClick = { showAppCheckFixDialog = true },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.VerifiedUser,
                                contentDescription = "Open App Check Diagnostics",
                                tint = appCheckTint,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(
                                text = appCheckChipLabel,
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = SurfaceCardElevated,
                            labelColor = appCheckTint,
                            selectedContainerColor = appCheckTint.copy(alpha = 0.2f),
                            selectedLabelColor = appCheckTint
                        ),
                        modifier = Modifier.testTag("brain_header_appcheck_chip")
                    )

                    BrainLanguage.entries.forEach { lang ->
                        val selected = consoleState.selectedLanguage == lang
                        FilterChip(
                            selected = selected,
                            onClick = {
                                viewModel.updatePreferences(lang, consoleState.selectedRoutingPolicy)
                            },
                            label = {
                                Text(
                                    text = lang.nativeLabel,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonPurple.copy(alpha = 0.25f),
                                selectedLabelColor = NeonPurpleBright
                            ),
                            modifier = Modifier.testTag("lang_chip_${lang.wireValue.lowercase()}")
                        )
                    }
                }

                // Free-Tier Model Router Selector (Auto-Fallback across live Free models)
                val liveFreeProviders = freeProviders.filter { it.status.isOperational }
                if (liveFreeProviders.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = consoleState.selectedPreferredProviderId == null,
                            onClick = { viewModel.selectPreferredProvider(null) },
                            label = { Text("Auto Free (Fallback)", style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricTeal.copy(alpha = 0.22f),
                                selectedLabelColor = ElectricTeal
                            ),
                            modifier = Modifier.testTag("brain_provider_auto_free")
                        )
                        liveFreeProviders.forEach { prov ->
                            val isChosen = consoleState.selectedPreferredProviderId == prov.providerId
                            FilterChip(
                                selected = isChosen,
                                onClick = { viewModel.selectPreferredProvider(prov.providerId) },
                                label = { Text("${prov.providerId.displayName} (FREE)", style = MaterialTheme.typography.labelMedium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyberBlue.copy(alpha = 0.22f),
                                    selectedLabelColor = CyberBlue
                                ),
                                modifier = Modifier.testTag("brain_provider_${prov.providerId.wireId}")
                            )
                        }
                    }
                }
            }

            // Conversation & Orchestration Timeline
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (messages.isEmpty() && !consoleState.isGenerating && consoleState.streamingText.isBlank()) {
                    item(key = "empty_hero") {
                        MasterBrainWelcomeCard(
                            onStarterClick = { starterPrompt ->
                                viewModel.submitDirective(starterPrompt)
                            }
                        )
                    }
                }

                items(messages, key = { it.id.ifBlank { "${it.role}_${it.content.hashCode()}" } }) { msg ->
                    BrainMessageCard(message = msg)
                }

                if (consoleState.isGenerating || consoleState.streamingText.isNotBlank()) {
                    item(key = "live_stream") {
                        LiveStreamingCard(
                            phaseLabel = consoleState.activityPhase.statusLabel,
                            plan = consoleState.latestPlan,
                            routing = consoleState.latestRouting,
                            streamingText = consoleState.streamingText
                        )
                    }
                }

                if (messagesState is StreamUiState.Error) {
                    val msg = (messagesState as StreamUiState.Error).message
                    val isAppCheckRelated = msg.contains("App Check", ignoreCase = true) ||
                        msg.contains("AppCheck", ignoreCase = true) ||
                        msg.contains("too many attempts", ignoreCase = true)
                    item(key = "messages_error") {
                        ErrorBannerCard(
                            message = msg,
                            onDismiss = {},
                            onFixAppCheck = if (isAppCheckRelated) { { showAppCheckFixDialog = true } } else null
                        )
                    }
                }

                val err = consoleState.errorMessage
                if (err != null) {
                    val isAppCheckRelated = err.contains("App Check", ignoreCase = true) ||
                        err.contains("AppCheck", ignoreCase = true) ||
                        err.contains("too many attempts", ignoreCase = true)
                    item(key = "brain_error") {
                        ErrorBannerCard(
                            message = err,
                            onDismiss = { viewModel.clearError() },
                            onFixAppCheck = if (isAppCheckRelated) { { showAppCheckFixDialog = true } } else null
                        )
                    }
                }
            }

            if (showAppCheckFixDialog) {
                com.example.ui.providers.AppCheckDiagnosticDialog(
                    diagnostic = appCheckDiagnostic,
                    isVerifying = appCheckDiagnostic.status == AppCheckHealthStatus.REFRESHING ||
                        appCheckDiagnostic.status == AppCheckHealthStatus.AUTO_REGISTERING,
                    onDismiss = { showAppCheckFixDialog = false },
                    onVerifyAndRefresh = { viewModel.verifyAndRefreshAppCheck() },
                    onRegenerateToken = { viewModel.regenerateDebugToken(context) }
                )
            }

            // Bottom Directive Composer
            Surface(
                color = CosmicSurface,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(width = 1.dp, color = OutlineSubtle)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnimatedVisibility(visible = consoleState.attachedBitmap != null) {
                        consoleState.attachedBitmap?.let { bmp ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceCardElevated)
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "Attached visual reference",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Text(
                                    text = "Image attached for Multimodal Analysis",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CyberBlue,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { viewModel.attachBitmap(null) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove attached image",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = {
                                photoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !consoleState.isGenerating,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("attach_image_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AddPhotoAlternate,
                                contentDescription = stringResource(R.string.attach_image_desc),
                                tint = CyberBlue
                            )
                        }

                        IconButton(
                            onClick = {
                                val localeTag = when (consoleState.selectedLanguage) {
                                    BrainLanguage.BN, BrainLanguage.BN_EN -> "bn-BD"
                                    else -> "en-US"
                                }
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(
                                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                    )
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                                    putExtra(
                                        RecognizerIntent.EXTRA_PROMPT,
                                        "Speak in Bengali, English, or Banglish"
                                    )
                                }
                                try {
                                    speechLauncher.launch(intent)
                                } catch (_: ActivityNotFoundException) {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            "Speech input service unavailable on this device. Full Voice Assistant & TTS arrives in PART 07."
                                        )
                                    }
                                }
                            },
                            enabled = !consoleState.isGenerating,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("voice_input_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = stringResource(R.string.voice_input_desc),
                                tint = NeonPurpleBright
                            )
                        }

                        OutlinedTextField(
                            value = consoleState.promptDraft,
                            onValueChange = viewModel::updatePromptDraft,
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.prompt_placeholder),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            maxLines = 4,
                            enabled = !consoleState.isGenerating,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NeonPurple,
                                unfocusedBorderColor = OutlineSubtle,
                                focusedContainerColor = SurfaceCard,
                                unfocusedContainerColor = SurfaceCard
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 50.dp)
                                .testTag("brain_prompt_input")
                        )

                        Button(
                            onClick = { viewModel.submitDirective() },
                            enabled = !consoleState.isGenerating &&
                                (consoleState.promptDraft.isNotBlank() || consoleState.attachedBitmap != null),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonPurple,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier
                                .size(50.dp)
                                .testTag("send_directive_button")
                        ) {
                            if (consoleState.isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = stringResource(R.string.send_directive_desc)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MasterBrainWelcomeCard(
    onStarterClick: (String) -> Unit
) {
    val starters = listOf(
        "বাংলায় একটা Expense Tracker Android App-এর পুরো প্ল্যান আর আর্কিটেকচার দাও",
        "Plan an Android Messaging App with Google Login, Firestore Chat & AI Assistant",
        "Amar e-commerce startup er jonno Kotlin Compose + Firebase backend blueprint banai dao"
    )

    GlowGlassCard(highlightBorder = true) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(14.dp))
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_brain_hero),
                contentDescription = "MYRAA AI Super Brain neural network artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.85f)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, ObsidianBg.copy(alpha = 0.88f))
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
            ) {
                StatusBadgeChip(label = "MASTER AI BRAIN • PART 01 LIVE", accentColor = CyberBlue)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Describe your app in বাংলা, English, or Banglish",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
            }
        }

        Text(
            text = "Master AI Brain analyzes intent, decomposes subtasks, selects the best model via the Model Router, assigns specialized MYRAA agents, and saves Project Memory to Cloud Firestore.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            starters.forEachIndexed { idx, prompt ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceCardElevated,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onStarterClick(prompt) }
                        .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
                        .testTag("starter_prompt_$idx")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = NeonPurpleBright,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrainMessageCard(message: BrainMessage) {
    val isUser = message.role == MessageRole.USER.wireValue
    if (isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .border(
                        1.dp,
                        NeonPurple.copy(alpha = 0.55f),
                        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusBadgeChip(label = "USER DIRECTIVE", accentColor = NeonPurpleBright)
                        StatusBadgeChip(
                            label = BrainLanguage.fromWire(message.detectedLanguage).nativeLabel,
                            accentColor = CyberBlue
                        )
                    }
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    } else {
        GlowGlassCard(highlightBorder = true) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Psychology,
                        contentDescription = null,
                        tint = CyberBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "MYRAA SUPER BRAIN",
                        style = MaterialTheme.typography.labelLarge,
                        color = CyberBlue
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (message.selectedModelId.isNotBlank()) {
                        StatusBadgeChip(label = message.selectedModelId, accentColor = NeonPurpleBright)
                    }
                    if (message.latencyMs > 0L) {
                        StatusBadgeChip(label = "${message.latencyMs}ms", accentColor = ElectricTeal)
                    }
                }
            }

            if (message.intentSummary.isNotBlank() || message.subtasks.isNotEmpty() || message.assignedAgents.isNotEmpty()) {
                var expandedPlan by remember(message.id) { mutableStateOf(true) }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceCardElevated,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Orchestration Plan",
                                style = MaterialTheme.typography.titleMedium,
                                color = NeonPurpleBright
                            )
                            TextButton(onClick = { expandedPlan = !expandedPlan }) {
                                Text(if (expandedPlan) "Hide Plan" else "Show Plan")
                            }
                        }

                        if (expandedPlan) {
                            if (message.intentSummary.isNotBlank()) {
                                Text(
                                    text = "Goal: ${message.intentSummary}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (message.assignedAgents.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    message.assignedAgents.forEach { agent ->
                                        StatusBadgeChip(label = agent, accentColor = CyberBlue)
                                    }
                                }
                            }
                            if (message.subtasks.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    message.subtasks.forEachIndexed { idx, task ->
                                        Text(
                                            text = "${idx + 1}. $task",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveStreamingCard(
    phaseLabel: String,
    plan: com.example.ai.provider.StructuredOrchestrationPlan?,
    routing: com.example.ai.provider.ModelRoutingDecision?,
    streamingText: String
) {
    GlowGlassCard(highlightBorder = true) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = CyberBlue,
                strokeWidth = 2.dp
            )
            Text(
                text = phaseLabel,
                style = MaterialTheme.typography.titleMedium,
                color = CyberBlue
            )
            if (routing != null) {
                StatusBadgeChip(label = routing.selectedModel.modelId, accentColor = NeonPurpleBright)
            }
        }

        if (plan != null) {
            Text(
                text = "Intent: ${plan.detectedGoal}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (plan.assignedAgents.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    plan.assignedAgents.forEach { agent ->
                        StatusBadgeChip(label = agent, accentColor = ElectricTeal)
                    }
                }
            }
            if (plan.detectedRisksOrGaps.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = AmberSignal,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Detected gaps: ${plan.detectedRisksOrGaps.first()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmberSignal
                    )
                }
            }
        }

        if (streamingText.isNotBlank()) {
            Text(
                text = streamingText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        } else {
            Text(
                text = "Synthesizing response...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorBannerCard(
    message: String,
    onDismiss: () -> Unit,
    onFixAppCheck: (() -> Unit)? = null
) {
    Surface(
        color = CrimsonContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, CrimsonError.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = CrimsonError
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = CrimsonError,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss error",
                        tint = CrimsonError
                    )
                }
            }

            if (onFixAppCheck != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onFixAppCheck,
                        colors = ButtonDefaults.buttonColors(containerColor = CyberBlue, contentColor = ObsidianBg),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.minimumInteractiveComponentSize().testTag("open_appcheck_fix_modal_button")
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open App Check Phone Fix")
                    }
                }
            }
        }
    }
}

private suspend fun decodeScaledBitmap(
    context: Context,
    uri: Uri,
    maxDim: Int
): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        val largest = maxOf(boundsOpts.outWidth, boundsOpts.outHeight).coerceAtLeast(1)
        var sample = 1
        while (largest / sample > maxDim) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
    }.getOrNull()
}
