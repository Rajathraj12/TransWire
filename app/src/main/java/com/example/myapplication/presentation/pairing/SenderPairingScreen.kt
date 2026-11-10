package com.example.myapplication.presentation.pairing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.domain.model.PairingStatus
import com.example.myapplication.presentation.components.*
import com.example.myapplication.presentation.theme.*
import com.example.myapplication.util.PairingShareHelper

@Composable
fun SenderPairingScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTransfer: () -> Unit,
    viewModel: SenderPairingViewModel = viewModel()
) {
    val session by viewModel.session.collectAsState()
    val qrCodeBitmap by viewModel.qrCodeBitmap.collectAsState()
    val timeRemaining by viewModel.timeRemainingSeconds.collectAsState()
    val joinRequestReceiverId by viewModel.joinRequestReceiverId.collectAsState()
    val isSessionReady by viewModel.isSessionReady.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(session?.status) {
        if (session?.status == PairingStatus.PAIRED || session?.status == PairingStatus.EXPIRED) {
            PairingShareHelper.cleanupSharedQrFile(context)
        }
    }

    DisposableEffect(Unit) {
        onDispose { PairingShareHelper.cleanupSharedQrFile(context) }
    }

    Scaffold(
        topBar = {
            TwTopBar(
                title = "Remote Pairing",
                onBack = onNavigateBack
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = TwSpacing.Large, vertical = TwSpacing.Default),
            contentAlignment = Alignment.Center
        ) {
            when {
                session == null -> {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }

                session?.status == PairingStatus.PAIRED -> {
                    PairedReadyState(onNavigateToTransfer = onNavigateToTransfer)
                }

                session?.status == PairingStatus.EXPIRED -> {
                    ExpiredSessionState(onGenerate = { viewModel.generateNewSession() })
                }

                joinRequestReceiverId != null -> {
                    IncomingJoinRequestCard(
                        onApprove = { viewModel.approveRequest() },
                        onReject = { viewModel.rejectRequest() }
                    )
                }

                else -> {
                    QrPinPassTicket(
                        qrCodeBitmap = qrCodeBitmap,
                        pin = session?.pin ?: "------",
                        timeRemaining = timeRemaining,
                        isSessionReady = isSessionReady,
                        connectionError = connectionError,
                        onRetryConnection = { viewModel.retryConnection() }
                    )
                }
            }
        }
    }
}

@Composable
private fun PairedReadyState(onNavigateToTransfer: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center
        ) {
            TwAnimatedCheckmark(color = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.height(TwSpacing.Large))
        Text(
            text = "Receiver Paired",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.tertiary
        )
        Spacer(Modifier.height(TwSpacing.Small))
        Text(
            text = "Secure signaling complete. Tap below to begin streaming your files directly.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(TwSpacing.XLarge))
        TwPrimaryButton(
            text = "Start Transfer",
            onClick = onNavigateToTransfer,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            leadingIcon = Icons.AutoMirrored.Filled.Send
        )
    }
}

@Composable
private fun ExpiredSessionState(onGenerate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.TimerOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(42.dp)
            )
        }
        Spacer(Modifier.height(TwSpacing.Large))
        Text(
            text = "Pairing Code Expired",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(TwSpacing.Small))
        Text(
            text = "For security, pairing tokens expire after 2 minutes. Generate a fresh code to continue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(TwSpacing.XLarge))
        TwPrimaryButton(
            text = "Generate New Code",
            onClick = onGenerate,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            leadingIcon = Icons.Default.Refresh
        )
    }
}

@Composable
private fun IncomingJoinRequestCard(
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    TwCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(TwSpacing.Large),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(110.dp),
                contentAlignment = Alignment.Center
            ) {
                TwPulseRing(color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize(), ringCount = 2)
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(Modifier.height(TwSpacing.Default))
            Text(
                text = "Approve Receiver?",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(TwSpacing.Small))
            Text(
                text = "A receiver entered your pairing credentials and wants to connect to this transfer session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(TwSpacing.Large))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TwDestructiveButton(
                    text = "Reject",
                    onClick = onReject,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                )
                TwPrimaryButton(
                    text = "Approve",
                    onClick = onApprove,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    leadingIcon = Icons.Default.Check
                )
            }
        }
    }
}

@Composable
private fun QrPinPassTicket(
    qrCodeBitmap: android.graphics.Bitmap?,
    pin: String,
    timeRemaining: Long,
    isSessionReady: Boolean,
    connectionError: String?,
    onRetryConnection: () -> Unit
) {
    val expiryFraction = (timeRemaining / 120f).coerceIn(0f, 1f)
    val primaryColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var copiedPin by remember { mutableStateOf(false) }

    LaunchedEffect(copiedPin) {
        if (copiedPin) {
            delay(2000)
            copiedPin = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TwSpacing.Default)
    ) {
        Text(
            text = "On the receiver, open Receive and scan this code or enter the PIN.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        // Architectural QR Ticket Card (crisp white QR field ensures optimal scanner contrast)
        TwCard(
            modifier = Modifier.fillMaxWidth(),
            shape = ShapeLarge
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(TwSpacing.Large),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(236.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // White backing card for QR legibility in both light and dark modes
                    Surface(
                        modifier = Modifier.size(216.dp),
                        shape = ShapeDefault,
                        color = Color.White
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (qrCodeBitmap != null) {
                                Image(
                                    bitmap = qrCodeBitmap.asImageBitmap(),
                                    contentDescription = "Session QR Code",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(14.dp)
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = CobaltBlue
                                )
                            }
                        }
                    }

                    // Corner framing brackets
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val len = 20.dp.toPx()
                        val w = size.width
                        val h = size.height
                        val stroke = 3.dp.toPx()
                        drawLine(primaryColor, Offset(0f, 0f), Offset(len, 0f), stroke, StrokeCap.Round)
                        drawLine(primaryColor, Offset(0f, 0f), Offset(0f, len), stroke, StrokeCap.Round)
                        drawLine(primaryColor, Offset(w, 0f), Offset(w - len, 0f), stroke, StrokeCap.Round)
                        drawLine(primaryColor, Offset(w, 0f), Offset(w, len), stroke, StrokeCap.Round)
                        drawLine(primaryColor, Offset(0f, h), Offset(len, h), stroke, StrokeCap.Round)
                        drawLine(primaryColor, Offset(0f, h), Offset(0f, h - len), stroke, StrokeCap.Round)
                        drawLine(primaryColor, Offset(w, h), Offset(w - len, h), stroke, StrokeCap.Round)
                        drawLine(primaryColor, Offset(w, h), Offset(w, h - len), stroke, StrokeCap.Round)
                    }
                }

                Spacer(Modifier.height(TwSpacing.Large))
                TwDivider()
                Spacer(Modifier.height(TwSpacing.Default))

                Text(
                    text = "Session PIN",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 312.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    pin.padEnd(6, '-').take(6).forEachIndexed { index, ch ->
                        if (index == 3) {
                            Box(
                                modifier = Modifier
                                    .width(8.dp)
                                    .height(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("·", style = MonoLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(ShapeSmall)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), ShapeSmall),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = ch.toString(),
                                style = MonoLarge.copy(fontSize = 20.sp),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Surface(
                    shape = ShapePill,
                    color = if (copiedPin) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .clip(ShapePill)
                        .clickable {
                            clipboard.setText(AnnotatedString(pin))
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            copiedPin = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (copiedPin) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = if (copiedPin) MaterialTheme.colorScheme.tertiary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (copiedPin) "Copied" else "Copy PIN",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (copiedPin) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TwOutlinedButton(
                        text = "Share QR",
                        onClick = {
                            val bmp = qrCodeBitmap ?: return@TwOutlinedButton
                            scope.launch { PairingShareHelper.shareQrCode(context, bmp, pin) }
                        },
                        enabled = qrCodeBitmap != null,
                        modifier = Modifier.weight(1f),
                        leadingIcon = Icons.Default.QrCode2
                    )
                    TwPrimaryButton(
                        text = "Share PIN",
                        onClick = { PairingShareHelper.sharePin(context, pin, timeRemaining) },
                        enabled = pin.length == 6 && pin.all(Char::isDigit),
                        modifier = Modifier.weight(1f),
                        leadingIcon = Icons.Default.Share
                    )
                }
            }
        }

        // Countdown telemetry footer
        TwCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = TwSpacing.Default, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val gaugeColor = if (expiryFraction > 0.25f) primaryColor else MaterialTheme.colorScheme.error
                TwProgressRing(
                    progress = expiryFraction,
                    modifier = Modifier.size(40.dp),
                    progressColor = gaugeColor,
                    strokeWidth = 4.dp
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = gaugeColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Expires in ${timeRemaining / 60}:${"%02d".format(timeRemaining % 60)}",
                        style = MonoMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            connectionError != null && !isSessionReady -> connectionError
                            !isSessionReady -> "Registering code with server…"
                            else -> "Waiting for receiver…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connectionError != null && !isSessionReady) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (connectionError != null && !isSessionReady) {
                    TextButton(onClick = onRetryConnection) {
                        Text("Retry", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
