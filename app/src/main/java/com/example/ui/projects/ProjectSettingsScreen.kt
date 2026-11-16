package com.example.ui.projects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.provider.AiProviderDescriptor
import com.example.ai.provider.BackendAiProxyClient
import com.example.ai.provider.ProviderConnectionStatus
import com.example.ai.provider.ProviderId
import com.example.data.model.BuildType
import com.example.ui.components.GlowGlassCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusBadgeChip
import com.example.ui.providers.AppCheckDiagnosticsCard
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyberBlue
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.NeonPurple
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SurfaceCardElevated
import com.example.ui.theme.TextMuted
import com.example.ui.workspace.BackendConnectionStatus
import com.example.ui.workspace.EngineeringStudioViewModel
import com.example.ui.workspace.ProviderKeyVaultStatus
import com.example.ui.workspace.ProviderVaultFeedback

@Composable
fun ProjectSettingsScreen(
    studioViewModel: EngineeringStudioViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onNavigateBack)
    val context = LocalContext.current
    val state by studioViewModel.uiState.collectAsStateWithLifecycle()
    val appCheckDiagnostic by studioViewModel.appCheckStatus.collectAsStateWithLifecycle()
    val freeProviders by studioViewModel.freeTierCatalogState.collectAsStateWithLifecycle()
    val externalFreeProviders = remember(freeProviders) {
        freeProviders.filter { it.providerId != ProviderId.GOOGLE_GEMINI }
    }

    var backendUrlInput by remember(state.backendUrlDraft) { mutableStateOf(state.backendUrlDraft) }
    var packagePrefixInput by remember(state.defaultPackagePrefix) { mutableStateOf(state.defaultPackagePrefix) }
    var buildTypeInput by remember(state.preferredBuildType) { mutableStateOf(state.preferredBuildType) }

    val apiKeyDrafts = remember { mutableStateMapOf<String, String>() }
    val accountIdDrafts = remember { mutableStateMapOf<String, String>() }
    var expandedProviderWireId by remember { mutableStateOf<String?>(ProviderId.GROQ.wireId) }

    val cleanDraftUrl = backendUrlInput.trim().trimEnd('/')
    val liveUrlValidationError = remember(cleanDraftUrl) {
        if (cleanDraftUrl.isBlank()) null else BackendAiProxyClient.validateBackendUrlError(cleanDraftUrl)
    }
    val hasValidTypedUrl = cleanDraftUrl.isNotBlank() && liveUrlValidationError == null
    val hasValidSavedBackendUrl = state.backendUrlDraft.isNotBlank() &&
        BackendAiProxyClient.validateBackendUrlError(state.backendUrlDraft) == null
    val hasUnsavedUrlEdit = cleanDraftUrl != state.backendUrlDraft.trim().trimEnd('/')

    val connectionAccent = when (state.backendConnectionStatus) {
        BackendConnectionStatus.CONNECTED -> ElectricTeal
        BackendConnectionStatus.TESTING,
        BackendConnectionStatus.SAVING -> CyberBlue
        BackendConnectionStatus.NOT_CONFIGURED -> AmberSignal
        BackendConnectionStatus.INVALID_URL,
        BackendConnectionStatus.OFFLINE,
        BackendConnectionStatus.ERROR -> CrimsonError
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 840.dp)
                .align(Alignment.TopCenter)
                .testTag("settings_lazy_column"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item(key = "header") {
                SectionHeader(
                    title = "Backend & API Settings",
                    subtitle = "Encrypted Provider Key Vault, Multi-AI Proxy Server & Remote APK Build Config",
                    icon = Icons.Filled.Settings,
                    trailing = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = CyberBlue
                            )
                        }
                    }
                )
            }

            item(key = "app_check_settings_diagnostics") {
                AppCheckDiagnosticsCard(
                    diagnostic = appCheckDiagnostic,
                    isVerifying = state.isVerifyingAppCheck,
                    onVerifyAndRefresh = { studioViewModel.verifyAndRefreshAppCheckToken() },
                    onRegenerateToken = { studioViewModel.regenerateDebugToken(context) }
                )
            }

            item(key = "server_form") {
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
                            Icon(Icons.Filled.CloudDone, contentDescription = null, tint = CyberBlue)
                            Text("Remote Backend Server URL", style = MaterialTheme.typography.titleMedium)
                        }
                        StatusBadgeChip(
                            label = state.backendConnectionStatus.label,
                            accentColor = connectionAccent,
                            modifier = Modifier.testTag("backend_connection_status_chip")
                        )
                    }

                    Text(
                        text = state.backendConnectionDetail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = backendUrlInput,
                        onValueChange = { backendUrlInput = it },
                        label = { Text("HTTPS Server URL (or http://10.0.2.2:8088 for local testing)") },
                        placeholder = { Text("https://myraa-backend-xxxxx.run.app") },
                        singleLine = true,
                        isError = liveUrlValidationError != null,
                        supportingText = {
                            when {
                                liveUrlValidationError != null -> {
                                    Text(liveUrlValidationError, color = CrimsonError)
                                }
                                hasUnsavedUrlEdit && hasValidTypedUrl -> {
                                    Text("Unsaved URL — tap 'Save Backend URL' to persist & unlock API Key Vault.", color = AmberSignal)
                                }
                                hasValidSavedBackendUrl -> {
                                    Text("Active saved URL: ${state.backendUrlDraft}", color = ElectricTeal)
                                }
                                else -> {
                                    Text("Requires deployed MYRAA Cloud Run backend URL (https://...) or local emulator URL.")
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_backend_url_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                studioViewModel.saveBackendUrl(
                                    url = backendUrlInput,
                                    buildType = buildTypeInput,
                                    prefix = packagePrefixInput
                                )
                            },
                            enabled = hasValidTypedUrl && !state.isSavingBackendUrl && !state.isTestingBackendConnection,
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricTeal, contentColor = ObsidianBg),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("settings_save_url_button")
                        ) {
                            if (state.isSavingBackendUrl) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = ObsidianBg, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Saving...")
                            } else {
                                Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Save Backend URL")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                val target = cleanDraftUrl.ifBlank { state.backendUrlDraft }
                                studioViewModel.testBackendConnection(target)
                            },
                            enabled = (hasValidTypedUrl || hasValidSavedBackendUrl) &&
                                !state.isTestingBackendConnection &&
                                !state.isSavingBackendUrl,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("settings_test_connection_button")
                        ) {
                            if (state.isTestingBackendConnection) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CyberBlue, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Testing...")
                            } else {
                                Icon(Icons.Filled.NetworkCheck, contentDescription = null, tint = CyberBlue, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Connection", color = CyberBlue)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = packagePrefixInput,
                        onValueChange = { packagePrefixInput = it },
                        label = { Text("Default Android Package Prefix") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Default Remote APK Build Type", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BuildType.entries.forEach { bt ->
                            FilterChip(
                                selected = buildTypeInput == bt,
                                onClick = { buildTypeInput = bt },
                                label = { Text(bt.displayLabel) }
                            )
                        }
                    }

                    Button(
                        onClick = {
                            studioViewModel.saveBackendConfiguration(
                                url = backendUrlInput,
                                buildType = buildTypeInput,
                                prefix = packagePrefixInput
                            )
                        },
                        enabled = !state.isProbingBackend && !state.isSavingBackendUrl && liveUrlValidationError == null,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonPurple, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("settings_save_button")
                    ) {
                        if (state.isProbingBackend) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Saving & Probing Free Providers...")
                        } else {
                            Text("Save All Settings & Verify Live Connection")
                        }
                    }

                    state.errorMessage?.let { err ->
                        StatusBadgeChip(label = err, accentColor = CrimsonError)
                    }
                    state.statusMessage?.let { ok ->
                        StatusBadgeChip(label = ok, accentColor = ElectricTeal)
                    }
                }
            }

            item(key = "provider_key_vault_header") {
                GlowGlassCard(highlightBorder = true) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Key, contentDescription = null, tint = ElectricTeal)
                        Text(
                            text = "Free AI Provider API Keys (Encrypted Vault)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "Keys entered here are encrypted with AES-256-GCM inside your authenticated backend server (/v1/providers/keys). API keys are NEVER stored in the APK, device logs, or public Firestore data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!hasValidSavedBackendUrl) {
                        StatusBadgeChip(
                            label = if (hasValidTypedUrl) {
                                "Backend URL Missing (Unsaved) — tap 'Save Backend URL' above to unlock Key Vault."
                            } else {
                                "Backend URL Missing — save a valid HTTPS Backend Server URL above to store & verify API keys."
                            },
                            accentColor = AmberSignal
                        )
                    } else if (state.backendConnectionStatus == BackendConnectionStatus.OFFLINE) {
                        StatusBadgeChip(
                            label = "Backend Offline — check your server or tap 'Test Connection' before saving keys.",
                            accentColor = CrimsonError
                        )
                    }
                }
            }

            items(externalFreeProviders, key = { "vault_${it.providerId.wireId}" }) { provider ->
                val wireId = provider.providerId.wireId
                ProviderApiKeyVaultCard(
                    provider = provider,
                    expanded = expandedProviderWireId == wireId,
                    apiKeyDraft = apiKeyDrafts[wireId].orEmpty(),
                    accountIdDraft = accountIdDrafts[wireId].orEmpty(),
                    isBusy = state.keyMutationBusyProviderId == provider.providerId,
                    backendConfigured = hasValidSavedBackendUrl,
                    hasUnsavedValidUrlAbove = !hasValidSavedBackendUrl && hasValidTypedUrl,
                    backendConnectionStatus = state.backendConnectionStatus,
                    vaultFeedback = state.providerVaultFeedback[wireId],
                    onToggleExpand = {
                        expandedProviderWireId = if (expandedProviderWireId == wireId) null else wireId
                    },
                    onApiKeyChange = { apiKeyDrafts[wireId] = it },
                    onAccountIdChange = { accountIdDrafts[wireId] = it },
                    onClearDraft = { apiKeyDrafts[wireId] = "" },
                    onSaveTypedUrlAbove = {
                        studioViewModel.saveBackendUrl(
                            url = backendUrlInput,
                            buildType = buildTypeInput,
                            prefix = packagePrefixInput
                        )
                    },
                    onSaveKey = {
                        val key = apiKeyDrafts[wireId].orEmpty()
                        val acct = accountIdDrafts[wireId].orEmpty()
                        studioViewModel.saveProviderApiKey(provider.providerId, key, acct)
                    },
                    onDeleteKey = {
                        studioViewModel.deleteProviderApiKey(provider.providerId)
                    }
                )
            }

            item(key = "security_deployment_note") {
                GlowGlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = AmberSignal)
                        Text("Zero-Secret Key Isolation", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        text = "Provider API keys never leave the authenticated backend vault. Any provider without a saved key or verified handshake remains honestly marked Missing API Key / Offline and never reports false connectivity.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = CyberBlue.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = state.lastProbeSummary,
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberBlue,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderApiKeyVaultCard(
    provider: AiProviderDescriptor,
    expanded: Boolean,
    apiKeyDraft: String,
    accountIdDraft: String,
    isBusy: Boolean,
    backendConfigured: Boolean,
    hasUnsavedValidUrlAbove: Boolean,
    backendConnectionStatus: BackendConnectionStatus,
    vaultFeedback: ProviderVaultFeedback?,
    onToggleExpand: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onAccountIdChange: (String) -> Unit,
    onClearDraft: () -> Unit,
    onSaveTypedUrlAbove: () -> Unit,
    onSaveKey: () -> Unit,
    onDeleteKey: () -> Unit
) {
    var showSecret by remember(provider.providerId.wireId) { mutableStateOf(false) }

    val clearToken = vaultFeedback?.clearDraftToken ?: 0L
    LaunchedEffect(clearToken) {
        if (clearToken > 0L) {
            onClearDraft()
        }
    }

    val cleanKey = apiKeyDraft.trim()
    val cleanAccountId = accountIdDraft.trim()
    val hasValidKeyFormat = cleanKey.length >= 8 && !cleanKey.any { it.isWhitespace() }
    val hasRequiredAccountId = !provider.requiresAccountId || cleanAccountId.isNotBlank()
    val canClickSave = backendConfigured && !isBusy && hasValidKeyFormat && hasRequiredAccountId

    val inlineValidationMessage = when {
        isBusy -> "Verification in Progress — encrypting key & calling ${provider.displayName}..."
        !backendConfigured -> "Backend URL Missing — save a valid Backend Server URL above to enable."
        cleanKey.isBlank() -> "Missing API Key — paste your ${provider.providerId.envKeyName} above."
        cleanKey.any { it.isWhitespace() } -> "Invalid API Key — remove spaces or line breaks."
        cleanKey.length < 8 -> "Invalid API Key — enter at least 8 characters (${cleanKey.length}/8)."
        provider.requiresAccountId && cleanAccountId.isBlank() -> "Cloudflare Account ID is required along with API Token."
        else -> null
    }

    val displayedVaultStatus: ProviderKeyVaultStatus = when {
        isBusy -> ProviderKeyVaultStatus.VERIFICATION_IN_PROGRESS
        vaultFeedback != null -> vaultFeedback.status
        !backendConfigured -> ProviderKeyVaultStatus.BACKEND_URL_MISSING
        backendConnectionStatus == BackendConnectionStatus.OFFLINE && !provider.status.isOperational ->
            ProviderKeyVaultStatus.BACKEND_OFFLINE
        provider.status.isOperational -> ProviderKeyVaultStatus.VERIFIED
        provider.status == ProviderConnectionStatus.PROBE_ERROR -> ProviderKeyVaultStatus.INVALID_API_KEY
        provider.status == ProviderConnectionStatus.OFFLINE -> ProviderKeyVaultStatus.BACKEND_OFFLINE
        provider.status == ProviderConnectionStatus.MISSING_API_KEY -> ProviderKeyVaultStatus.MISSING_API_KEY
        else -> ProviderKeyVaultStatus.SAVE_FAILED
    }

    val isLive = provider.status.isOperational || displayedVaultStatus == ProviderKeyVaultStatus.VERIFIED
    val isWarning = displayedVaultStatus == ProviderKeyVaultStatus.MISSING_API_KEY ||
        displayedVaultStatus == ProviderKeyVaultStatus.BACKEND_URL_MISSING ||
        displayedVaultStatus == ProviderKeyVaultStatus.VERIFICATION_IN_PROGRESS ||
        provider.status == ProviderConnectionStatus.RATE_LIMITED
    val statusColor = when {
        displayedVaultStatus == ProviderKeyVaultStatus.VERIFICATION_IN_PROGRESS -> CyberBlue
        isLive -> ElectricTeal
        isWarning -> AmberSignal
        else -> CrimsonError
    }

    GlowGlassCard(highlightBorder = isLive) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    StatusBadgeChip(label = "FREE", accentColor = ElectricTeal)
                }
                Text(
                    text = provider.providerId.freeTierSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
            TextButton(
                onClick = onToggleExpand,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("vault_toggle_${provider.providerId.wireId}")
            ) {
                Text(if (expanded) "Hide" else "Configure", color = CyberBlue)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusBadgeChip(
                label = if (provider.status.isOperational) provider.status.label else displayedVaultStatus.label,
                accentColor = statusColor,
                modifier = Modifier.testTag("vault_status_${provider.providerId.wireId}")
            )
            if (provider.maskedKeyHint.isNotBlank()) {
                val label = if (provider.keySource == "USER_VAULT") {
                    "Vault: ${provider.maskedKeyHint}"
                } else {
                    "Env: ${provider.maskedKeyHint}"
                }
                StatusBadgeChip(label = label, accentColor = CyberBlue)
            }
        }

        if (expanded) {
            Surface(
                color = SurfaceCardElevated,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CyberBlue.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = apiKeyDraft,
                        onValueChange = onApiKeyChange,
                        label = { Text("${provider.providerId.envKeyName} (Encrypted at Rest)") },
                        placeholder = { Text("Paste key (never saved inside APK)") },
                        singleLine = true,
                        isError = cleanKey.isNotBlank() && !hasValidKeyFormat,
                        visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(
                                onClick = { showSecret = !showSecret },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Icon(
                                    imageVector = if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showSecret) "Hide API Key" else "Show API Key",
                                    tint = CyberBlue
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("vault_key_input_${provider.providerId.wireId}")
                    )

                    if (provider.requiresAccountId) {
                        OutlinedTextField(
                            value = accountIdDraft,
                            onValueChange = onAccountIdChange,
                            label = { Text("Cloudflare Account ID (CLOUDFLARE_ACCOUNT_ID)") },
                            placeholder = { Text("32-character Cloudflare Account ID") },
                            singleLine = true,
                            isError = cleanKey.isNotBlank() && cleanAccountId.isBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("vault_account_input_${provider.providerId.wireId}")
                        )
                    }

                    if (inlineValidationMessage != null) {
                        Text(
                            text = inlineValidationMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (!backendConfigured || cleanKey.isBlank()) AmberSignal else CrimsonError,
                            modifier = Modifier.testTag("vault_validation_${provider.providerId.wireId}")
                        )
                    }

                    if (vaultFeedback != null) {
                        val fbColor = when (vaultFeedback.status) {
                            ProviderKeyVaultStatus.VERIFIED -> ElectricTeal
                            ProviderKeyVaultStatus.VERIFICATION_IN_PROGRESS -> CyberBlue
                            ProviderKeyVaultStatus.MISSING_API_KEY,
                            ProviderKeyVaultStatus.BACKEND_URL_MISSING -> AmberSignal
                            ProviderKeyVaultStatus.BACKEND_OFFLINE,
                            ProviderKeyVaultStatus.INVALID_API_KEY,
                            ProviderKeyVaultStatus.SAVE_FAILED -> CrimsonError
                        }
                        Surface(
                            color = fbColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${vaultFeedback.status.label}: ${vaultFeedback.message}",
                                style = MaterialTheme.typography.labelSmall,
                                color = fbColor,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .testTag("vault_feedback_${provider.providerId.wireId}")
                            )
                        }
                    }

                    if (hasUnsavedValidUrlAbove) {
                        OutlinedButton(
                            onClick = onSaveTypedUrlAbove,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("vault_quick_save_url_${provider.providerId.wireId}")
                        ) {
                            Text("Save Typed Backend URL Above & Enable Key Vault", color = CyberBlue)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSaveKey,
                            enabled = canClickSave,
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricTeal, contentColor = ObsidianBg),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("vault_save_key_${provider.providerId.wireId}")
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = ObsidianBg, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Verification in Progress...")
                            } else {
                                Text("Save & Verify Key")
                            }
                        }

                        if (provider.keySource == "USER_VAULT") {
                            OutlinedButton(
                                onClick = onDeleteKey,
                                enabled = backendConfigured && !isBusy,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("vault_delete_key_${provider.providerId.wireId}")
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove Key", tint = CrimsonError, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
