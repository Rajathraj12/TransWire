package com.example.myapplication.presentation.transfer

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.data.remote.SignalingClient
import com.example.myapplication.data.remote.SignalingEvent
import com.example.myapplication.data.storage.FileStorageManager
import com.example.myapplication.domain.model.TransferProgress
import com.example.myapplication.domain.model.TransferredFileItem
import com.example.myapplication.presentation.components.*
import com.example.myapplication.presentation.theme.*
import com.example.myapplication.service.TransferSessionCoordinator
import java.util.concurrent.TimeUnit

@Composable
fun TransferScreen(
    isSender: Boolean,
    hostIp: String,
    isGroupOwner: Boolean,
    onNavigateHome: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: TransferViewModel = viewModel(
        factory = TransferViewModelFactory(application, isSender, hostIp, isGroupOwner)
    )
    val progress by viewModel.progress.collectAsState()
    val fileStorageManager = remember(context) { FileStorageManager(context) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(isSender, hostIp) {
        if (hostIp == "0.0.0.0") {
            SignalingClient.events.collect { event ->
                val state = viewModel.progress.value
                val readyForPeerRetry = state.isCompleted &&
                    state.canRetry &&
                    !state.isCancelled &&
                    !TransferSessionCoordinator.isTransferActive()
                if (!readyForPeerRetry) return@collect

                when {
                    event is SignalingEvent.RetryTransfer -> {
                        TransferSessionCoordinator.retryTransfer(context, notifyPeer = false)
                    }
                    !isSender && event is SignalingEvent.OfferReceived -> {
                        TransferSessionCoordinator.retryTransfer(context, notifyPeer = false)
                    }
                }
            }
        }
    }

    fun terminateRemoteSessionIfLeaving() {
        if (hostIp == "0.0.0.0") {
            SignalingClient.leaveSession()
            SignalingClient.disconnect()
        }
    }

    BackHandler {
        if (progress.isCompleted) {
            terminateRemoteSessionIfLeaving()
        }
        onNavigateBack()
    }

    Scaffold(
        topBar = {
            TwTopBar(
                title = if (isSender) "Sending Files" else "Receiving Files",
                subtitle = if (hostIp == "0.0.0.0") "Remote WebRTC P2P" else "Nearby Wi-Fi Direct",
                onBack = {
                    if (progress.isCompleted) {
                        terminateRemoteSessionIfLeaving()
                    }
                    onNavigateBack()
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (progress.isCompleted) {
                CompletedTransferView(
                    isSender = isSender,
                    progress = progress,
                    context = context,
                    onOpenFile = { item ->
                        fileStorageManager.openCompletedFile(
                            fileUriString = item.fileUri,
                            fileName = item.fileName,
                            mimeType = item.mimeType,
                            status = item.status
                        )
                    },
                    onRetry = { viewModel.retryTransfer() },
                    onHome = {
                        terminateRemoteSessionIfLeaving()
                        onNavigateHome()
                    }
                )
            } else {
                ActiveTransferView(
                    isSender = isSender,
                    progress = progress,
                    context = context,
                    onPauseResume = {
                        if (progress.isPaused) viewModel.resumeTransfer() else viewModel.pauseTransfer()
                    },
                    onCancel = { viewModel.cancelTransfer() }
                )
            }
        }
    }
}

@Composable
private fun ActiveTransferView(
    isSender: Boolean,
    progress: TransferProgress,
    context: Context,
    onPauseResume: () -> Unit,
    onCancel: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val animatedProgress by animateFloatAsState(
        targetValue = progress.fileProgress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "transferRingProgress"
    )

    val targetRingColor = when {
        progress.isPaused -> if (LocalIsDarkTheme.current) SemanticWarningDark else SemanticWarning
        isSender          -> MaterialTheme.colorScheme.primary
        else              -> MaterialTheme.colorScheme.secondary
    }
    val ringColor by animateColorAsState(targetValue = targetRingColor, animationSpec = tween(250), label = "ringColor")

    val totalFormatted       = Formatter.formatFileSize(context, progress.totalBytes)
    val transferredFormatted = Formatter.formatFileSize(context, progress.totalBytesTransferred)
    val speedFormatted       = Formatter.formatFileSize(context, progress.speedBytesPerSec) + "/s"
    val millis               = progress.estimatedTimeRemainingMs.coerceAtLeast(0L)
    val etaFormatted         = "%02d:%02d".format(
        TimeUnit.MILLISECONDS.toMinutes(millis),
        TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(TwSpacing.Large)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(TwSpacing.Default))

            // ── Dual-Layer Precision Telemetry Gauge (40 outer ticks + smooth inner arc) ──
        TwProgressRing(
            progress = animatedProgress,
            modifier = Modifier.size(236.dp),
            progressColor = ringColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 14.dp,
            tickCount = 40,
            tickTrackColor = MaterialTheme.colorScheme.outline
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(
                    shape = ShapePill,
                    color = ringColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = when {
                            progress.isPaused -> "Paused"
                            isSender          -> "Sending"
                            else              -> "Receiving"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ringColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = MonoLarge.copy(fontSize = 40.sp, lineHeight = 44.sp),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = speedFormatted,
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(TwSpacing.Large))

        Text(
            text = progress.currentFileName.ifBlank { "Connecting…" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(TwSpacing.Default))

        // ── Telemetry Card ────────────────────────────────────────────────────
        TwCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(TwSpacing.Default),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryStat(label = "Transferred", value = transferredFormatted)
                VerticalDivider(modifier = Modifier.height(38.dp), color = MaterialTheme.colorScheme.outline)
                TelemetryStat(label = "Total", value = totalFormatted)
                VerticalDivider(modifier = Modifier.height(38.dp), color = MaterialTheme.colorScheme.outline)
                TelemetryStat(label = "Remaining", value = etaFormatted)
            }
        }

        Spacer(Modifier.height(TwSpacing.Default))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TwDestructiveButton(
                text = "Cancel",
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCancel()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            )
            TwPrimaryButton(
                text = if (progress.isPaused) "Resume" else "Pause",
                onClick = onPauseResume,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                leadingIcon = if (progress.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause
            )
        }
    }
}

@Composable
private fun TelemetryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MonoMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CompletedTransferView(
    isSender: Boolean,
    progress: TransferProgress,
    context: Context,
    onOpenFile: (TransferredFileItem) -> Unit,
    onRetry: () -> Unit,
    onHome: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(progress.isCompleted) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val isSuccess = !progress.isCancelled && progress.error == null
    val stateColor = when {
        progress.isCancelled   -> MaterialTheme.colorScheme.onSurfaceVariant
        progress.error != null -> MaterialTheme.colorScheme.error
        else                   -> MaterialTheme.colorScheme.tertiary
    }
    val stateTitle = when {
        progress.isCancelled   -> "Transfer Cancelled"
        progress.error != null -> "Transfer Failed"
        else                   -> "Transfer Complete"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(TwSpacing.Large)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (progress.transferredFiles.isEmpty()) Arrangement.Center else Arrangement.Top
    ) {
        Spacer(Modifier.height(TwSpacing.Default))

        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(stateColor.copy(alpha = 0.13f))
                .border(1.dp, stateColor.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isSuccess) {
                TwAnimatedCheckmark(color = stateColor, modifier = Modifier.size(48.dp))
            } else {
                Icon(
                    imageVector = if (progress.isCancelled) Icons.Default.Cancel else Icons.Default.ErrorOutline,
                    contentDescription = stateTitle,
                    tint = stateColor,
                    modifier = Modifier.size(46.dp)
                )
            }
        }

        Spacer(Modifier.height(TwSpacing.Default))

        Text(
            text = stateTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = stateColor,
            textAlign = TextAlign.Center
        )

        if (progress.error != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = progress.error!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else if (progress.transferredFiles.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isSender) {
                    "${progress.transferredFiles.size} file(s) sent"
                } else {
                    "Saved to Downloads/Transwire"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        if (progress.transferredFiles.isNotEmpty()) {
            Spacer(Modifier.height(TwSpacing.Large))
            TwSectionLabel(
                text = if (isSender) "Sent Files (${progress.transferredFiles.size})"
                       else "Received Files (${progress.transferredFiles.size})",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(TwSpacing.Small))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(progress.transferredFiles, key = { it.fileId }) { item ->
                    CompletedFileCard(
                        item = item,
                        isSender = isSender,
                        context = context,
                        onOpen = { onOpenFile(item) }
                    )
                }
            }
        } else {
            Spacer(Modifier.height(TwSpacing.XLarge))
        }

        Spacer(Modifier.height(TwSpacing.Default))
        if (progress.canRetry) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TwOutlinedButton(
                    text = "Home",
                    onClick = onHome,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                )
                TwPrimaryButton(
                    text = "Retry",
                    onClick = onRetry,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    leadingIcon = Icons.Default.Refresh
                )
            }
        } else {
            TwPrimaryButton(
                text = "Return to Home",
                onClick = onHome,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            )
        }
    }
}

@Composable
private fun CompletedFileCard(
    item: TransferredFileItem,
    isSender: Boolean,
    context: Context,
    onOpen: () -> Unit
) {
    val isCompleted = item.status == "COMPLETED"
    val formattedSize = remember(item.size) { Formatter.formatFileSize(context, item.size) }

    TwCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TwFileIconBadge(mimeType = item.mimeType, fileName = item.fileName, size = 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        isCompleted && !isSender -> "$formattedSize • Tap to open"
                        isCompleted              -> "$formattedSize • Sent"
                        else                     -> "$formattedSize • ${item.status}"
                    },
                    style = MonoSmall,
                    color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                )
            }
            if (isCompleted) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open file",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
