package com.example.myapplication.presentation.settings

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.remote.SignalingClient
import com.example.myapplication.presentation.components.*
import com.example.myapplication.presentation.theme.*
import kotlinx.coroutines.launch

enum class AppThemeMode(val label: String) {
    SYSTEM("System Default"),
    LIGHT("Light Mode"),
    DARK("Dark Mode")
}

object ThemePreferences {
    private const val PREF_NAME = "transwire_prefs"
    private const val KEY_THEME = "theme_mode"

    fun getThemeMode(context: Context): AppThemeMode {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val ordinal = prefs.getInt(KEY_THEME, AppThemeMode.SYSTEM.ordinal)
        return AppThemeMode.entries.getOrElse(ordinal) { AppThemeMode.SYSTEM }
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_THEME, mode.ordinal).apply()
    }
}

object ServerPreferences {
    private const val PREF_NAME = "transwire_prefs"
    private const val KEY_SERVER_URL = "signaling_server_url"

    fun getServerUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_URL, SignalingClient.DEFAULT_SERVER_URL)
            ?.takeIf { it.isNotBlank() }
            ?: SignalingClient.DEFAULT_SERVER_URL
    }

    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER_URL, url.trim().trimEnd('/')).apply()
    }
}

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    currentThemeMode: AppThemeMode = AppThemeMode.SYSTEM,
    onThemeModeChange: (AppThemeMode) -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val storageInfo = remember {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            val fraction = if (total > 0L) ((total - free).toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
            Triple(free, total, fraction)
        } catch (_: Exception) {
            Triple(0L, 1L, 0f)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // ── Editorial Header ──────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = TwSpacing.Default, vertical = 14.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        TwDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TwSpacing.Default)
        ) {
            Spacer(Modifier.height(TwSpacing.Default))

            // ── App Identity Banner (uses real drawable/logo.png) ─────────────
            TwCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(TwSpacing.Default),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(TwSpacing.Default)
                ) {
                    TwBrandLogo(size = 56.dp, cornerRadius = 14.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "TransWire",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "Local Wi-Fi Direct and remote file transfer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(TwSpacing.Large))

            // ── Appearance ────────────────────────────────────────────────────
            TwSectionLabel("Appearance")
            Spacer(Modifier.height(TwSpacing.Small))
            TwCard(modifier = Modifier.fillMaxWidth()) {
                AppThemeMode.entries.forEachIndexed { index, mode ->
                    val selected = currentThemeMode == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onThemeModeChange(mode)
                            }
                            .padding(horizontal = TwSpacing.Default, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(ShapeSmall)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when (mode) {
                                        AppThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                                        AppThemeMode.LIGHT  -> Icons.Default.LightMode
                                        AppThemeMode.DARK   -> Icons.Default.DarkMode
                                    },
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                        RadioButton(
                            selected = selected,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onThemeModeChange(mode)
                            }
                        )
                    }
                    if (index < AppThemeMode.entries.lastIndex) TwDivider(startIndent = 62.dp)
                }
            }

            Spacer(Modifier.height(TwSpacing.Large))

            // ── Remote Signaling Server ───────────────────────────────────────
            RemoteServerSection(context = context)

            Spacer(Modifier.height(TwSpacing.Large))

            // ── Storage & Destination ─────────────────────────────────────────
            TwSectionLabel("Storage & Destination")
            Spacer(Modifier.height(TwSpacing.Small))
            TwCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(TwSpacing.Default)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Device Free Space", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${Formatter.formatFileSize(context, storageInfo.first)} / ${Formatter.formatFileSize(context, storageInfo.second)}",
                            style = MonoSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { storageInfo.third },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(ShapePill),
                        color = if (storageInfo.third > 0.9f) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                TwDivider()
                SettingsInfoRow(
                    icon = Icons.Default.Folder,
                    title = "Received Files Folder",
                    subtitle = "Downloads/Transwire (auto-renames duplicate filenames)",
                    modifier = Modifier.padding(TwSpacing.Default)
                )
            }

            Spacer(Modifier.height(TwSpacing.Large))

            // ── Transfer Engine & Security ────────────────────────────────────
            TwSectionLabel("Transfer Details")
            Spacer(Modifier.height(TwSpacing.Small))
            TwCard(modifier = Modifier.fillMaxWidth()) {
                SettingsInfoRow(
                    icon = Icons.Default.VerifiedUser,
                    title = "File Verification",
                    subtitle = "SHA-256 checksum verified on completion",
                    modifier = Modifier.padding(TwSpacing.Default)
                )
                TwDivider(startIndent = 48.dp)
                SettingsInfoRow(
                    icon = Icons.Default.Sync,
                    title = "Transfer Resume",
                    subtitle = "Interrupted transfers resume from saved progress",
                    modifier = Modifier.padding(TwSpacing.Default)
                )
                TwDivider(startIndent = 48.dp)
                SettingsInfoRow(
                    icon = Icons.Default.Lock,
                    title = "Direct Delivery",
                    subtitle = "Files move directly between devices without cloud storage",
                    modifier = Modifier.padding(TwSpacing.Default)
                )
            }

            Spacer(Modifier.height(110.dp))
        }
    }
}

@Composable
private fun SettingsInfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RemoteServerSection(context: Context) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var draftUrl by remember { mutableStateOf(ServerPreferences.getServerUrl(context)) }
    var isTesting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    val hasValidScheme = draftUrl.trim().let {
        it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
    }

    TwSectionLabel("Remote Signaling Server")
    Spacer(Modifier.height(TwSpacing.Small))
    TwCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(TwSpacing.Default),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Server Endpoint URL",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Text(
                text = "Set your hosted server URL when transferring outside your local Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = draftUrl,
                onValueChange = {
                    draftUrl = it
                    statusMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MonoSmall,
                placeholder = { Text("https://your-app.onrender.com", style = MonoSmall) },
                isError = draftUrl.isNotBlank() && !hasValidScheme,
                supportingText = if (draftUrl.isNotBlank() && !hasValidScheme) {
                    { Text("Must start with http:// or https://") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = ShapeSmall
            )

            // Quick preset chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {
                        draftUrl = SignalingClient.DEFAULT_SERVER_URL
                        statusMessage = null
                    },
                    label = { Text("Local PC", style = MaterialTheme.typography.labelSmall) },
                    shape = ShapePill
                )
                AssistChip(
                    onClick = {
                        draftUrl = SignalingClient.EMULATOR_SERVER_URL
                        statusMessage = null
                    },
                    label = { Text("Emulator", style = MaterialTheme.typography.labelSmall) },
                    shape = ShapePill
                )
            }

            AnimatedVisibility(visible = statusMessage != null) {
                statusMessage?.let { (ok, msg) ->
                    val color = if (ok) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                    Surface(
                        shape = ShapeSmall,
                        color = color.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = color
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TwOutlinedButton(
                    text = if (isTesting) "Testing…" else "Test",
                    onClick = {
                        scope.launch {
                            isTesting = true
                            val result = SignalingClient.checkServerHealth(draftUrl)
                            isTesting = false
                            statusMessage = result.fold(
                                onSuccess = { true to it },
                                onFailure = { false to (it.message ?: "Connection failed") }
                            )
                        }
                    },
                    enabled = !isTesting && hasValidScheme,
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Default.Refresh
                )
                TwPrimaryButton(
                    text = "Save URL",
                    onClick = {
                        if (SignalingClient.updateServerUrl(draftUrl)) {
                            ServerPreferences.setServerUrl(context, draftUrl)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            statusMessage = true to "Saved. Remote transfers will use this server."
                        } else {
                            statusMessage = false to "Invalid URL format"
                        }
                    },
                    enabled = hasValidScheme,
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Default.Check
                )
            }
        }
    }
}
