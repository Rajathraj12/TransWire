package com.example.myapplication.presentation.home

import android.text.format.Formatter
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.data.db.TransferEntity
import com.example.myapplication.data.storage.FileStorageManager
import com.example.myapplication.domain.model.TransferProgress
import com.example.myapplication.presentation.components.*
import com.example.myapplication.presentation.theme.*
import com.example.myapplication.service.TransferSessionCoordinator

@Composable
fun HomeScreen(
    onNavigateToSend: () -> Unit,
    onNavigateToReceive: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToNearbyReceive: () -> Unit = onNavigateToReceive,
    viewModel: HomeViewModel = viewModel()
) {
    val recentTransfers by viewModel.recentTransfers.collectAsState()
    val completedCount by viewModel.completedCount.collectAsState()
    val activeProgress by viewModel.activeTransferProgress.collectAsState()
    val isTransferRunning = TransferSessionCoordinator.isTransferActive() && !activeProgress.isCompleted

    val context = LocalContext.current
    val fileStorageManager = remember(context) { FileStorageManager(context) }
    val storageSnapshot by produceState(initialValue = StorageSnapshot(0L, 0L)) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            viewModel.getStorageSnapshot()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        // ── Editorial Brand Header ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TwSpacing.Default, vertical = TwSpacing.Default),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TwBrandLogo(size = 42.dp, cornerRadius = 12.dp)
                Column {
                    Text(
                        text = "TransWire",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // ── Signal & Wire Conduit Console (Custom Canvas Visualization) ───────
        SignalConduitCard(
            storageSnapshot = storageSnapshot,
            completedCount = completedCount,
            isTransferRunning = isTransferRunning,
            activeProgress = activeProgress,
            context = context,
            modifier = Modifier.padding(horizontal = TwSpacing.Default)
        )

        Spacer(Modifier.height(TwSpacing.Default))

        // ── Primary Send & Receive Action Matrix ──────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TwSpacing.Default),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PrimaryTransferActionCard(
                title = "Send",
                subtitle = "Select files to share nearby or remotely",
                icon = Icons.Default.ArrowUpward,
                containerColor = MaterialTheme.colorScheme.primary,
                onContainerColor = MaterialTheme.colorScheme.onPrimary,
                onClick = onNavigateToSend,
                modifier = Modifier.weight(1f)
            )
            PrimaryTransferActionCard(
                title = "Receive",
                subtitle = "Scan QR, enter PIN, or accept nearby",
                icon = Icons.Default.ArrowDownward,
                containerColor = MaterialTheme.colorScheme.secondary,
                onContainerColor = MaterialTheme.colorScheme.onSecondary,
                onClick = onNavigateToReceive,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Quick Nearby Receive Strip ────────────────────────────────────────
        TwCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TwSpacing.Default),
            onClick = onNavigateToNearbyReceive
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TwSpacing.Default, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(ShapeSmall)
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiTethering,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Standby for Nearby Sender",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Make this device visible via Wi-Fi Direct without internet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open Nearby Standby",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(TwSpacing.Large))

        // ── Recent Transfers / Quick Guide ────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TwSpacing.Default)
        ) {
            if (recentTransfers.isNotEmpty()) {
                TwSectionLabel(
                    text = "Recent Activity",
                    action = "View all" to onNavigateToHistory
                )
                Spacer(Modifier.height(TwSpacing.Small))
                TwCard(modifier = Modifier.fillMaxWidth()) {
                    recentTransfers.forEachIndexed { index, item ->
                        HomeRecentTransferRow(
                            item = item,
                            context = context,
                            onClick = {
                                fileStorageManager.openCompletedFile(
                                    fileUriString = item.fileUri,
                                    fileName = item.fileName,
                                    mimeType = item.mimeType,
                                    status = item.status
                                )
                            }
                        )
                        if (index < recentTransfers.lastIndex) {
                            TwDivider(startIndent = 68.dp)
                        }
                    }
                }
            } else {
                TwSectionLabel(text = "How It Works")
                Spacer(Modifier.height(TwSpacing.Small))
                TwCard(modifier = Modifier.fillMaxWidth()) {
                    HowItWorksRow(
                        icon = Icons.Default.Wifi,
                        iconTint = MaterialTheme.colorScheme.tertiary,
                        iconBg = MaterialTheme.colorScheme.tertiaryContainer,
                        title = "Nearby Offline (Wi-Fi Direct)",
                        body = "Sender searches for nearby devices; receiver stays on standby and accepts."
                    )
                    TwDivider(startIndent = 64.dp)
                    HowItWorksRow(
                        icon = Icons.Default.QrCode2,
                        iconTint = MaterialTheme.colorScheme.primary,
                        iconBg = MaterialTheme.colorScheme.primaryContainer,
                        title = "Remote Internet (WebRTC P2P)",
                        body = "Pair via QR code or 6-digit PIN. Files stream directly between devices."
                    )
                }
            }
        }

        Spacer(Modifier.height(104.dp))
    }
}

// ─── Signal & Wire Conduit Console ────────────────────────────────────────────

@Composable
private fun SignalConduitCard(
    storageSnapshot: StorageSnapshot,
    completedCount: Int,
    isTransferRunning: Boolean,
    activeProgress: TransferProgress,
    context: android.content.Context,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val outlineColor = MaterialTheme.colorScheme.outline

    val packetPos = if (isTransferRunning) {
        val infiniteTransition = rememberInfiniteTransition(label = "conduit")
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "packet"
        ).value
    } else {
        0.5f
    }

    val freeFormatted = remember(storageSnapshot.freeBytes) {
        if (storageSnapshot.freeBytes > 0L) Formatter.formatFileSize(context, storageSnapshot.freeBytes)
        else "—"
    }

    TwCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(TwSpacing.Default)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isTransferRunning) primaryColor else tertiaryColor)
                    )
                    Text(
                        text = if (isTransferRunning) "Transfer in progress" else "Ready to transfer",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isTransferRunning) primaryColor else MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = if (completedCount > 0) "$completedCount completed" else freeFormatted + " free",
                    style = MonoSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(14.dp))

            // Animated wire trace between device endpoints
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp)
            ) {
                val y = size.height / 2f
                val leftX = 14.dp.toPx()
                val rightX = size.width - 14.dp.toPx()
                val span = (rightX - leftX).coerceAtLeast(1f)

                // Base dashed conduit line
                drawLine(
                    color = outlineColor,
                    start = Offset(leftX, y),
                    end = Offset(rightX, y),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()), 0f),
                    cap = StrokeCap.Round
                )

                // Active trace behind traveling signal pulse
                val pulseX = leftX + span * packetPos
                val trailStart = (pulseX - 42.dp.toPx()).coerceAtLeast(leftX)
                drawLine(
                    color = if (isTransferRunning) primaryColor else tertiaryColor,
                    start = Offset(trailStart, y),
                    end = Offset(pulseX, y),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Left node (This device)
                drawCircle(color = primaryColor, radius = 6.dp.toPx(), center = Offset(leftX, y))
                drawCircle(
                    color = primaryColor.copy(alpha = 0.25f),
                    radius = 11.dp.toPx(),
                    center = Offset(leftX, y),
                    style = Stroke(1.5.dp.toPx())
                )

                // Traveling signal diamond/dot
                drawCircle(
                    color = if (isTransferRunning) primaryColor else tertiaryColor,
                    radius = 4.dp.toPx(),
                    center = Offset(pulseX, y)
                )

                // Right node (Peer)
                drawCircle(color = tertiaryColor, radius = 6.dp.toPx(), center = Offset(rightX, y))
                drawCircle(
                    color = tertiaryColor.copy(alpha = 0.25f),
                    radius = 11.dp.toPx(),
                    center = Offset(rightX, y),
                    style = Stroke(1.5.dp.toPx())
                )
            }

            Spacer(Modifier.height(12.dp))

            if (isTransferRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeProgress.currentFileName.ifBlank { "Transferring…" },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${(activeProgress.fileProgress * 100).toInt()}%",
                        style = MonoMedium,
                        color = primaryColor
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { activeProgress.fileProgress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(ShapePill),
                    color = primaryColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChannelTag(label = "Wi-Fi Direct", color = tertiaryColor)
                        ChannelTag(label = "WebRTC P2P", color = primaryColor)
                    }
                    Text(
                        text = "$freeFormatted free",
                        style = MonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelTag(label: String, color: Color) {
    Surface(
        shape = ShapePill,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ─── Primary Send & Receive Action Tiles ──────────────────────────────────────

@Composable
private fun PrimaryTransferActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    containerColor: Color,
    onContainerColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh),
        label = "actionTileScale"
    )

    Box(
        modifier = modifier
            .height(164.dp)
            .scale(scale)
            .clip(ShapeLarge)
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(TwSpacing.Default),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(onContainerColor.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = onContainerColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = onContainerColor
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainerColor.copy(alpha = 0.86f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HomeRecentTransferRow(
    item: TransferEntity,
    context: android.content.Context,
    onClick: () -> Unit
) {
    val formattedSize = Formatter.formatFileSize(context, item.totalBytes)
    val isCompleted = item.status == "COMPLETED"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = TwSpacing.Default, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TwFileIconBadge(mimeType = item.mimeType, fileName = item.fileName, size = 40.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (item.isIncoming) Icons.AutoMirrored.Filled.CallReceived else Icons.AutoMirrored.Filled.CallMade,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = if (isCompleted && item.isIncoming) "$formattedSize • Tap to open" else formattedSize,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TwStatusChip(status = item.status)
            if (isCompleted) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open file",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun HowItWorksRow(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    body: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TwSpacing.Default, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(ShapeSmall)
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
