package com.example.myapplication.presentation.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.BuildConfig
import com.example.myapplication.data.db.AppDatabase
import com.example.myapplication.data.remote.SignalingClient
import com.example.myapplication.data.storage.FileStorageManager
import com.example.myapplication.presentation.components.*
import com.example.myapplication.presentation.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

object PrivacyPreferences {
    private const val PREF_NAME = "transwire_prefs"
    private const val KEY_SAVE_HISTORY = "save_transfer_history"

    fun isHistoryRetentionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SAVE_HISTORY, true)
    }

    fun setHistoryRetentionEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SAVE_HISTORY, enabled).apply()
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

    val storageInfo by produceState(initialValue = Triple(0L, 1L, 0f)) {
        value = withContext(Dispatchers.IO) {
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
                            .selectable(
                                selected = selected,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onThemeModeChange(mode)
                                },
                                role = Role.RadioButton
                            )
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
                            onClick = null
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

            Spacer(Modifier.height(TwSpacing.Large))

            // ── Privacy & Data ────────────────────────────────────────────────
            PrivacySettingsSection(context = context)

            Spacer(Modifier.height(TwSpacing.Large))

            // ── Diagnostics ───────────────────────────────────────────────────
            DiagnosticsSection(context = context, freeBytes = storageInfo.first)

            Spacer(Modifier.height(110.dp))
        }
    }
}

@Composable
private fun PrivacySettingsSection(context: Context) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var saveHistory by remember { mutableStateOf(PrivacyPreferences.isHistoryRetentionEnabled(context)) }
    var privacyStatus by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by remember { mutableStateOf(false) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear Transfer History?") },
            text = {
                Text("This removes past transfer records and temporary partial cache files. Saved files in Downloads/Transwire are kept.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        scope.launch(Dispatchers.IO) {
                            val dao = AppDatabase.getDatabase(context).transferDao()
                            val removedRows = dao.deleteNonActiveHistory()
                            val protected = dao.getResumableTempPaths().toSet()
                            FileStorageManager(context).cleanupStaleTempFiles(protected)
                            withContext(Dispatchers.Main) {
                                privacyStatus = "Cleared $removedRows history record(s) and stale cache."
                            }
                        }
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    TwSectionLabel("Privacy & Data")
    Spacer(Modifier.height(TwSpacing.Small))
    TwCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = saveHistory,
                    role = Role.Switch,
                    onValueChange = { enabled ->
                        saveHistory = enabled
                        PrivacyPreferences.setHistoryRetentionEnabled(context, enabled)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        privacyStatus = if (enabled) {
                            "Completed transfers will appear in History."
                        } else {
                            "Completed transfers will not be saved to History."
                        }
                    }
                )
                .padding(TwSpacing.Default),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PrivacyTip,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text("Save Transfer History", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Store completed records locally (cloud backup disabled)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = saveHistory, onCheckedChange = null)
        }

        TwDivider()

        Column(
            modifier = Modifier.padding(TwSpacing.Default),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TwOutlinedButton(
                    text = "Clear Cache",
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val dao = AppDatabase.getDatabase(context).transferDao()
                            val protected = dao.getResumableTempPaths().toSet()
                            FileStorageManager(context).cleanupStaleTempFiles(protected)
                            withContext(Dispatchers.Main) {
                                privacyStatus = "Temporary transfer cache cleaned."
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Default.CleaningServices
                )
                TwOutlinedButton(
                    text = "Clear History",
                    onClick = { showClearConfirm = true },
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Default.DeleteOutline
                )
            }

            privacyStatus?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsSection(context: Context, freeBytes: Long) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var isRunning by remember { mutableStateOf(false) }
    var reportText by remember { mutableStateOf<String?>(null) }
    var copiedNotice by remember { mutableStateOf(false) }

    TwSectionLabel("Diagnostics")
    Spacer(Modifier.height(TwSpacing.Small))
    TwCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(TwSpacing.Default),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsInfoRow(
                icon = Icons.Default.NetworkCheck,
                title = "Connection & Device Check",
                subtitle = "Runs safe checks without exposing tokens, PINs, or personal files."
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TwPrimaryButton(
                    text = if (isRunning) "Checking…" else "Run Diagnostics",
                    onClick = {
                        scope.launch {
                            isRunning = true
                            copiedNotice = false
                            val serverUrl = ServerPreferences.getServerUrl(context)
                            val health = SignalingClient.checkServerHealth(serverUrl)
                            val turn = SignalingClient.probeTurnSummary(serverUrl)
                            val nearbyPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
                            } else {
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                            }
                            val cameraGranted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            val wifiDirectSupported =
                                (context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager) != null

                            reportText = buildString {
                                appendLine("App: TransWire ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                                appendLine("Server: $serverUrl")
                                appendLine("Signaling: ${health.getOrElse { it.message ?: "Error" }}")
                                appendLine("TURN Relay: ${turn.getOrElse { it.message ?: "Unavailable" }}")
                                appendLine("Wi-Fi Direct Hardware: ${if (wifiDirectSupported) "Supported" else "Unavailable"}")
                                appendLine("Nearby Permission: ${if (nearbyPerm) "Granted" else "Not granted"}")
                                appendLine("Camera Permission: ${if (cameraGranted) "Granted" else "Not granted"}")
                                append("Free Space: ${Formatter.formatFileSize(context, freeBytes)}")
                            }
                            isRunning = false
                        }
                    },
                    enabled = !isRunning,
                    modifier = Modifier.weight(1f),
                    leadingIcon = Icons.Default.BugReport
                )
                if (reportText != null) {
                    TwOutlinedButton(
                        text = if (copiedNotice) "Copied" else "Copy",
                        onClick = {
                            reportText?.let {
                                clipboard.setText(AnnotatedString(it))
                                copiedNotice = true
                            }
                        },
                        modifier = Modifier.width(104.dp),
                        leadingIcon = if (copiedNotice) Icons.Default.Check else Icons.Default.ContentCopy
                    )
                }
            }

            reportText?.let { report ->
                Surface(
                    shape = ShapeSmall,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = report,
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
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
                    label = { Text("Cloud", style = MaterialTheme.typography.labelSmall) },
                    shape = ShapePill
                )
                AssistChip(
                    onClick = {
                        draftUrl = SignalingClient.LOCAL_SERVER_URL
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
                        val cleaned = draftUrl.trim().trimEnd('/')
                        if (SignalingClient.updateServerUrl(cleaned)) {
                            draftUrl = cleaned
                            ServerPreferences.setServerUrl(context, cleaned)
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
