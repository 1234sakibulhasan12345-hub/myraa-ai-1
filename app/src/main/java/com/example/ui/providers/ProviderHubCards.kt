package com.example.ui.providers

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.example.ai.provider.AiProviderDescriptor
import com.example.ai.provider.ProviderConnectionStatus
import com.example.ai.provider.TokenAndCostTracker
import com.example.core.security.AppCheckDiagnosticStatus
import com.example.core.security.AppCheckHealthStatus
import com.example.data.model.UsageLog
import com.example.ui.components.GlowGlassCard
import com.example.ui.components.StatusBadgeChip
import com.example.ui.theme.AmberSignal
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyberBlue
import com.example.ui.theme.ElectricTeal
import com.example.ui.theme.NeonPurpleBright
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SurfaceCardElevated
import com.example.ui.theme.TextMuted
import kotlinx.coroutines.delay

private const val FIREBASE_APPCHECK_CONSOLE_URL =
    "https://console.firebase.google.com/project/gen-lang-client-0645829429/appcheck/apps"

@Composable
private fun rememberCooldownSecondsRemaining(nextAllowedRefreshEpochMs: Long): Int {
    var nowEpochMs by remember(nextAllowedRefreshEpochMs) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    LaunchedEffect(nextAllowedRefreshEpochMs) {
        while (System.currentTimeMillis() < nextAllowedRefreshEpochMs) {
            nowEpochMs = System.currentTimeMillis()
            delay(1000L)
        }
        nowEpochMs = System.currentTimeMillis()
    }
    return if (nowEpochMs < nextAllowedRefreshEpochMs) {
        ((nextAllowedRefreshEpochMs - nowEpochMs + 999L) / 1000L).toInt().coerceAtLeast(1)
    } else {
        0
    }
}

private fun openFirebaseAppCheckConsole(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(FIREBASE_APPCHECK_CONSOLE_URL))
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "Open Chrome and visit: $FIREBASE_APPCHECK_CONSOLE_URL", Toast.LENGTH_LONG).show()
    }
}

private fun copyDebugTokenToClipboard(context: Context, secret: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Firebase App Check Debug Token", secret)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Debug token copied to clipboard!", Toast.LENGTH_SHORT).show()
}

@Composable
internal fun UsageTelemetryCard(logs: List<UsageLog>) {
    val totalCalls = logs.size
    val successfulCalls = logs.count { it.succeeded }
    val avgLatency = if (logs.isNotEmpty()) logs.map { it.latencyMs }.average().toInt() else 0
    val totalTokens = logs.sumOf { it.promptTokens + it.completionTokens }
    val totalCostMicros = logs.sumOf { it.estimatedCostUsdMicros }

    GlowGlassCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Filled.Insights, contentDescription = null, tint = ElectricTeal)
            Text(
                text = "Live Usage, Tokens & Cost Telemetry",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TelemetryStatTile("Runs", "$totalCalls", Modifier.weight(1f))
            TelemetryStatTile("Success", if (totalCalls == 0) "--" else "${(successfulCalls * 100) / totalCalls}%", Modifier.weight(1f))
            TelemetryStatTile("Avg ms", if (totalCalls == 0) "--" else "${avgLatency}ms", Modifier.weight(1f))
            TelemetryStatTile("Tokens", "$totalTokens", Modifier.weight(1f))
            TelemetryStatTile("Est USD", TokenAndCostTracker.formatUsdMicros(totalCostMicros), Modifier.weight(1f))
        }
    }
}

@Composable
private fun TelemetryStatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = SurfaceCardElevated, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = CyberBlue, maxLines = 1)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProviderDescriptorCard(provider: AiProviderDescriptor) {
    val isLive = provider.status.isOperational
    val isWarning = provider.status == ProviderConnectionStatus.MISSING_API_KEY ||
        provider.status == ProviderConnectionStatus.RATE_LIMITED ||
        provider.status == ProviderConnectionStatus.PLANNED_PART_02
    val accent = when {
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = when {
                        isLive -> Icons.Filled.CheckCircle
                        isWarning -> Icons.Filled.Lock
                        else -> Icons.Filled.ErrorOutline
                    },
                    contentDescription = provider.status.label,
                    tint = accent
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (provider.maskedKeyHint.isNotBlank()) {
                        val sourceLabel = when (provider.keySource) {
                            "USER_VAULT" -> "Vault Key: ${provider.maskedKeyHint}"
                            "SERVER_ENV" -> "Server Env: ${provider.maskedKeyHint}"
                            "FIREBASE_APP_CHECK" -> provider.maskedKeyHint
                            else -> provider.maskedKeyHint
                        }
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = CyberBlue
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (provider.isFreeProvider) {
                    StatusBadgeChip(label = "FREE", accentColor = ElectricTeal)
                }
                StatusBadgeChip(label = provider.status.label, accentColor = accent)
            }
        }

        if (provider.providerId.freeTierSummary.isNotBlank()) {
            Text(
                text = provider.providerId.freeTierSummary,
                style = MaterialTheme.typography.labelMedium,
                color = CyberBlue
            )
        }

        Text(
            text = provider.securityNote,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                isLive -> ElectricTeal
                isWarning -> TextMuted
                else -> CrimsonError
            }
        )

        provider.models.forEach { model ->
            Surface(color = SurfaceCardElevated, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (model.isFreeTier) {
                                StatusBadgeChip(label = "FREE", accentColor = ElectricTeal)
                            }
                            StatusBadgeChip(
                                label = if (model.isLiveInCurrentBuild) "LIVE" else provider.status.label,
                                accentColor = if (model.isLiveInCurrentBuild) ElectricTeal else accent
                            )
                        }
                    }
                    Text(model.modelId, style = MaterialTheme.typography.labelMedium, color = CyberBlue)
                    Text(model.roleSummary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (model.freeTierLimitsSummary.isNotBlank()) {
                        Text(
                            text = model.freeTierLimitsSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        model.capabilities.forEach { cap ->
                            StatusBadgeChip(label = cap.label, accentColor = NeonPurpleBright)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppCheckDiagnosticsCard(
    diagnostic: AppCheckDiagnosticStatus,
    isVerifying: Boolean,
    onVerifyAndRefresh: () -> Unit,
    onRegenerateToken: () -> Unit = {},
    onOpenSettings: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    val accent = when (diagnostic.status) {
        AppCheckHealthStatus.TOKEN_VALID -> ElectricTeal
        AppCheckHealthStatus.TOKEN_INVALID -> CrimsonError
        AppCheckHealthStatus.RATE_LIMITED -> AmberSignal
        AppCheckHealthStatus.AUTO_REGISTERING, AppCheckHealthStatus.REFRESHING -> CyberBlue
        else -> CyberBlue
    }
    val isHealthy = diagnostic.status == AppCheckHealthStatus.TOKEN_VALID
    val isBusy = isVerifying ||
        diagnostic.status == AppCheckHealthStatus.AUTO_REGISTERING ||
        diagnostic.status == AppCheckHealthStatus.REFRESHING
    val hasFailure = diagnostic.status == AppCheckHealthStatus.TOKEN_INVALID || diagnostic.status == AppCheckHealthStatus.RATE_LIMITED
    val cooldownSecondsRemaining = rememberCooldownSecondsRemaining(diagnostic.nextAllowedRefreshEpochMs)
    val isCooldown = cooldownSecondsRemaining > 0

    GlowGlassCard(highlightBorder = isHealthy || hasFailure) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.VerifiedUser,
                    contentDescription = "Firebase App Check Status",
                    tint = accent
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Firebase App Check Attestation",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${diagnostic.providerName} • ${if (diagnostic.isDebugBuild) "DEBUG" else "RELEASE"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyberBlue
                    )
                }
            }
            StatusBadgeChip(
                label = diagnostic.status.label,
                accentColor = accent
            )
        }

        // Automatic Registration Pipeline Status
        Surface(
            color = if (diagnostic.autoRegisteredWithBackend || isHealthy) {
                ElectricTeal.copy(alpha = 0.10f)
            } else {
                SurfaceCardElevated
            },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    if (diagnostic.autoRegisteredWithBackend || isHealthy) ElectricTeal.copy(alpha = 0.45f) else CyberBlue.copy(alpha = 0.30f),
                    RoundedCornerShape(12.dp)
                )
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
                        text = "AUTOMATIC REGISTRATION",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (diagnostic.autoRegisteredWithBackend) ElectricTeal else CyberBlue
                    )
                    StatusBadgeChip(
                        label = when {
                            diagnostic.autoRegisteredWithBackend -> "BACKEND SYNCED"
                            diagnostic.backendUrlConfigured -> "AUTO MODE READY"
                            !diagnostic.isDebugBuild -> "PLAY INTEGRITY"
                            else -> "NEEDS BACKEND URL"
                        },
                        accentColor = when {
                            diagnostic.autoRegisteredWithBackend -> ElectricTeal
                            diagnostic.backendUrlConfigured || !diagnostic.isDebugBuild -> CyberBlue
                            else -> AmberSignal
                        }
                    )
                }
                Text(
                    text = when {
                        !diagnostic.isDebugBuild ->
                            "Release builds use PlayIntegrityAppCheckProviderFactory automatically via Google Play Console distribution."
                        diagnostic.autoRegisteredWithBackend && !diagnostic.autoRegistrationSummary.isNullOrBlank() ->
                            diagnostic.autoRegistrationSummary
                        diagnostic.backendUrlConfigured ->
                            "Zero-copy automation active: MYRAA registers this phone's debug token via backend Admin API and verifies Firebase acceptance on launch."
                        else ->
                            "Configure your deployed MYRAA Backend URL (with server-side FIREBASE_SERVICE_ACCOUNT_JSON) in Settings so debug tokens register automatically with zero copy/paste."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (diagnostic.isDebugBuild && !diagnostic.backendUrlConfigured && onOpenSettings != null) {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("appcheck_open_backend_settings_button")
                    ) {
                        Text("Configure Backend URL for Auto-Registration", color = CyberBlue)
                    }
                }
            }
        }

        // Active Debug Token Box (Diagnostic & fallback inspection)
        if (diagnostic.isDebugBuild && !diagnostic.activeDebugSecret.isNullOrBlank()) {
            Surface(
                color = SurfaceCardElevated,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CyberBlue.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
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
                            text = "DEVICE DEBUG TOKEN UUID",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyberBlue
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = {
                                    copyDebugTokenToClipboard(context, diagnostic.activeDebugSecret)
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("copy_debug_token_button")
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = ElectricTeal)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy", color = ElectricTeal, style = MaterialTheme.typography.labelLarge)
                            }

                            TextButton(
                                onClick = onRegenerateToken,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("reset_debug_token_button")
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = AmberSignal)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reset / New", color = AmberSignal, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }

                    Surface(
                        color = ObsidianBg,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = diagnostic.activeDebugSecret,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = Color.White,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }

        Text(
            text = diagnostic.lastMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = if (hasFailure) CrimsonError else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { openFirebaseAppCheckConsole(context) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("open_firebase_console_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberBlue)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open Console", color = CyberBlue)
            }

            OutlinedButton(
                onClick = { showDialog = true },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("view_phone_guide_button")
            ) {
                Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonPurpleBright)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Setup Details", color = NeonPurpleBright)
            }
        }

        Button(
            onClick = onVerifyAndRefresh,
            enabled = !isBusy && !isCooldown,
            colors = ButtonDefaults.buttonColors(containerColor = CyberBlue, contentColor = ObsidianBg),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("verify_appcheck_button")
        ) {
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = ObsidianBg, strokeWidth = 2.dp)
            } else {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Auto-Register and Verify App Check Token",
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            val btnLabel = when {
                diagnostic.status == AppCheckHealthStatus.AUTO_REGISTERING -> "Auto-Registering Token..."
                isBusy -> "Verifying App Check Token..."
                isCooldown -> "Cooldown Active (${cooldownSecondsRemaining}s)"
                diagnostic.backendUrlConfigured -> "Auto-Register & Verify Token"
                else -> "Verify & Refresh Token"
            }
            Text(btnLabel)
        }
    }

    if (showDialog) {
        AppCheckDiagnosticDialog(
            diagnostic = diagnostic,
            isVerifying = isBusy,
            onDismiss = { showDialog = false },
            onVerifyAndRefresh = onVerifyAndRefresh,
            onRegenerateToken = onRegenerateToken
        )
    }
}

@Composable
fun AppCheckDiagnosticDialog(
    diagnostic: AppCheckDiagnosticStatus,
    isVerifying: Boolean,
    onDismiss: () -> Unit,
    onVerifyAndRefresh: () -> Unit,
    onRegenerateToken: () -> Unit
) {
    val context = LocalContext.current
    val cooldownSecondsRemaining = rememberCooldownSecondsRemaining(diagnostic.nextAllowedRefreshEpochMs)
    val isCooldown = cooldownSecondsRemaining > 0
    val isBusy = isVerifying ||
        diagnostic.status == AppCheckHealthStatus.AUTO_REGISTERING ||
        diagnostic.status == AppCheckHealthStatus.REFRESHING

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = ElectricTeal)
                Text("Firebase App Check Automation")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Project: gen-lang-client-0645829429\nPackage: com.aistudio.myraa.qxkrmp\nStatus: ${diagnostic.status.label}\nBackend Auto-Registrar: ${if (diagnostic.backendUrlConfigured) "Configured" else "Not configured"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = CyberBlue
                )

                if (diagnostic.status == AppCheckHealthStatus.RATE_LIMITED || isCooldown) {
                    Surface(
                        color = AmberSignal.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = if (isCooldown) {
                                "Rate Limit Cooldown: ${cooldownSecondsRemaining}s remaining. Tap 'Reset Token' to generate a fresh UUID and clear attempt limits immediately."
                            } else {
                                "Firebase reported Too many attempts earlier. Tap 'Reset Token' or wait for cooldown before retrying."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberSignal,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                Text(
                    text = "How Automatic Registration Works:",
                    style = MaterialTheme.typography.titleSmall,
                    color = ElectricTeal
                )
                Text(
                    text = "• Automatic (Recommended): Deploy /backend/ with FIREBASE_SERVICE_ACCOUNT_JSON (Firebase App Check Admin role) and save the HTTPS URL in Settings. MYRAA registers this phone's token automatically on launch via Firebase's official debugTokens.create API.\n" +
                        "• Play Store Production Track: Installing a release build from Google Play Internal/Production testing uses PlayIntegrityAppCheckProviderFactory automatically with zero debug tokens.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!diagnostic.activeDebugSecret.isNullOrBlank()) {
                    Text(
                        text = "Device Debug Token UUID (Fallback Copy):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        color = SurfaceCardElevated,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = diagnostic.activeDebugSecret,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color.White
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = onRegenerateToken,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.minimumInteractiveComponentSize().testTag("dialog_reset_debug_token_button")
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = AmberSignal)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Reset Token", color = AmberSignal, style = MaterialTheme.typography.labelMedium)
                                }
                                TextButton(
                                    onClick = { copyDebugTokenToClipboard(context, diagnostic.activeDebugSecret) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.minimumInteractiveComponentSize().testTag("dialog_copy_debug_token_button")
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = ElectricTeal)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy Token", color = ElectricTeal, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }

                OutlinedButton(
                    onClick = { openFirebaseAppCheckConsole(context) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("dialog_open_firebase_console_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberBlue)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open Console (Manual Fallback)", color = CyberBlue)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onVerifyAndRefresh,
                enabled = !isBusy && !isCooldown,
                colors = ButtonDefaults.buttonColors(containerColor = CyberBlue, contentColor = ObsidianBg),
                modifier = Modifier.minimumInteractiveComponentSize().testTag("dialog_verify_appcheck_button")
            ) {
                Text(
                    when {
                        diagnostic.status == AppCheckHealthStatus.AUTO_REGISTERING -> "Auto-Registering..."
                        isBusy -> "Verifying..."
                        isCooldown -> "Wait (${cooldownSecondsRemaining}s)"
                        diagnostic.backendUrlConfigured -> "Auto-Register & Verify"
                        else -> "Verify & Refresh"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Text("Close")
            }
        }
    )
}

