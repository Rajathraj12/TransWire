package com.example.myapplication.presentation.receive

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.presentation.components.*
import com.example.myapplication.presentation.theme.*
import com.example.myapplication.util.BarcodeAnalyzer
import com.example.myapplication.util.CameraPreview

@Composable
fun ReceiveScreen(
    onNavigateBack: () -> Unit,
    onNavigateToNearby: () -> Unit,
    onNavigateToTransfer: () -> Unit = {},
    viewModel: ReceiverPairingViewModel = viewModel()
) {
    val scanState by viewModel.scanState.collectAsState()
    val isDecodingImage by viewModel.isDecodingImage.collectAsState()
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasCameraPermission = it }

    val qrImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.handleQrImageUri(context, it) }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 0 = Scan QR, 1 = Enter PIN, 2 = Nearby Wi-Fi Direct
    var selectedTab by remember { mutableStateOf(0) }
    var pinInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TwTopBar(
                title = "Receive Files",
                onBack = onNavigateBack
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = scanState) {
                is ReceiverState.Paired -> {
                    PairedSuccessView(
                        onStartTransfer = onNavigateToTransfer,
                        onNavigateBack = onNavigateBack
                    )
                }

                is ReceiverState.WaitingForSenderApproval -> {
                    WaitingApprovalView(onCancel = { viewModel.resetScan() })
                }

                is ReceiverState.Error -> {
                    ReceiveErrorView(
                        message = state.message,
                        onRetry = {
                            pinInput = ""
                            viewModel.resetScan()
                        }
                    )
                }

                is ReceiverState.Scanning -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Architectural Segmented Tab Header
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            TabRow(
                                selectedTabIndex = selectedTab,
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.primary
                            ) {
                                Tab(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    text = { Text("Scan QR", style = MaterialTheme.typography.labelMedium) },
                                    icon = { Icon(Icons.Default.QrCodeScanner, null, modifier = Modifier.size(18.dp)) }
                                )
                                Tab(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    text = { Text("Enter PIN", style = MaterialTheme.typography.labelMedium) },
                                    icon = { Icon(Icons.Default.Dialpad, null, modifier = Modifier.size(18.dp)) }
                                )
                                Tab(
                                    selected = selectedTab == 2,
                                    onClick = { selectedTab = 2 },
                                    text = { Text("Nearby", style = MaterialTheme.typography.labelMedium) },
                                    icon = { Icon(Icons.Default.WifiTethering, null, modifier = Modifier.size(18.dp)) }
                                )
                            }
                        }

                        AnimatedContent(
                            targetState = selectedTab,
                            transitionSpec = {
                                slideInHorizontally { if (targetState > initialState) it / 3 else -it / 3 } + fadeIn(tween(200)) togetherWith
                                slideOutHorizontally { if (targetState > initialState) -it / 3 else it / 3 } + fadeOut(tween(150))
                            },
                            label = "receiveModeTab",
                            modifier = Modifier.weight(1f)
                        ) { tab ->
                            when (tab) {
                                0 -> QrScanViewport(
                                    hasPermission = hasCameraPermission,
                                    isDecodingImage = isDecodingImage,
                                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                    onUploadQrImage = { qrImagePicker.launch("image/*") },
                                    onQrScanned = { viewModel.handleQrScanned(it) },
                                    onSwitchToPin = { selectedTab = 1 },
                                    onSwitchToNearby = onNavigateToNearby
                                )
                                1 -> PinEntryPane(
                                    pinInput = pinInput,
                                    onPinChange = { raw ->
                                        pinInput = raw.filter { it.isDigit() }.take(6)
                                    },
                                    onSubmit = { viewModel.handlePinEntered(pinInput) }
                                )
                                else -> NearbyLaunchPane(onStartNearby = onNavigateToNearby)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairedSuccessView(
    onStartTransfer: () -> Unit,
    onNavigateBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(TwSpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
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
            text = "Connected to Sender",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.tertiary
        )
        Spacer(Modifier.height(TwSpacing.Small))
        Text(
            text = "Session verified. Tap below to establish the encrypted peer channel and receive incoming files.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(TwSpacing.XLarge))
        TwSecondaryButton(
            text = "Start Receiving",
            onClick = onStartTransfer,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            leadingIcon = Icons.Default.Download
        )
        Spacer(Modifier.height(10.dp))
        TwOutlinedButton(
            text = "Cancel",
            onClick = onNavigateBack,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun WaitingApprovalView(onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(TwSpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(150.dp),
            contentAlignment = Alignment.Center
        ) {
            TwPulseRing(color = MaterialTheme.colorScheme.secondary, modifier = Modifier.fillMaxSize(), ringCount = 3)
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    strokeWidth = 3.dp
                )
            }
        }
        Spacer(Modifier.height(TwSpacing.Default))
        Text(
            text = "Waiting for Sender Approval",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(TwSpacing.Small))
        Text(
            text = "Ask the sender to tap Approve on their screen to unlock the file stream.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(TwSpacing.XLarge))
        TwDestructiveButton(
            text = "Cancel Request",
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ReceiveErrorView(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(TwSpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(44.dp)
            )
        }
        Spacer(Modifier.height(TwSpacing.Large))
        Text(
            text = "Pairing Failed",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(TwSpacing.Small))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(TwSpacing.XLarge))
        TwPrimaryButton(
            text = "Try Again",
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = Icons.Default.Refresh
        )
    }
}

@Composable
private fun QrScanViewport(
    hasPermission: Boolean,
    isDecodingImage: Boolean,
    onRequestPermission: () -> Unit,
    onUploadQrImage: () -> Unit,
    onQrScanned: (String) -> Unit,
    onSwitchToPin: () -> Unit,
    onSwitchToNearby: () -> Unit
) {
    if (!hasPermission) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(TwSpacing.Large),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                TwEmptyState(
                    title = "Camera Permission Needed",
                    subtitle = "Grant camera access to scan live, or upload a QR screenshot shared via WhatsApp or Instagram.",
                    icon = Icons.Default.CameraAlt,
                    action = "Grant Camera Access" to onRequestPermission
                )
                Spacer(Modifier.height(4.dp))
                TwSecondaryButton(
                    text = "Upload QR Image",
                    onClick = onUploadQrImage,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Default.AddPhotoAlternate
                )
                Spacer(Modifier.height(10.dp))
                TwOutlinedButton(
                    text = "Enter 6-Digit PIN Instead",
                    onClick = onSwitchToPin,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Default.Dialpad
                )
            }
            if (isDecodingImage) {
                DecodingOverlay()
            }
        }
        return
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val infiniteTransition = rememberInfiniteTransition(label = "qrScanLine")
    val scanYFraction by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanLineY"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        val analyzer = remember { BarcodeAnalyzer { onQrScanned(it) } }
        CameraPreview(analyzer = analyzer)

        // Subtle scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f))
        )

        // Custom Canvas Corner Reticle + Sweep Line
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(240.dp)) {
                val cornerLen = 30.dp.toPx()
                val strokeW = 3.5.dp.toPx()
                val w = size.width
                val h = size.height
                val cornerColor = Color.White

                // Top-left
                drawLine(cornerColor, Offset(0f, 0f), Offset(cornerLen, 0f), strokeW, StrokeCap.Round)
                drawLine(cornerColor, Offset(0f, 0f), Offset(0f, cornerLen), strokeW, StrokeCap.Round)
                // Top-right
                drawLine(cornerColor, Offset(w, 0f), Offset(w - cornerLen, 0f), strokeW, StrokeCap.Round)
                drawLine(cornerColor, Offset(w, 0f), Offset(w, cornerLen), strokeW, StrokeCap.Round)
                // Bottom-left
                drawLine(cornerColor, Offset(0f, h), Offset(cornerLen, h), strokeW, StrokeCap.Round)
                drawLine(cornerColor, Offset(0f, h), Offset(0f, h - cornerLen), strokeW, StrokeCap.Round)
                // Bottom-right
                drawLine(cornerColor, Offset(w, h), Offset(w - cornerLen, h), strokeW, StrokeCap.Round)
                drawLine(cornerColor, Offset(w, h), Offset(w, h - cornerLen), strokeW, StrokeCap.Round)

                // Sweeping beam inside frame
                val lineY = h * scanYFraction
                drawLine(
                    color = primaryColor,
                    start = Offset(12.dp.toPx(), lineY),
                    end = Offset(w - 12.dp.toPx(), lineY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        // Bottom instructions + quick switches
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(TwSpacing.Default)
                .navigationBarsPadding(),
            shape = ShapeLarge,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(TwSpacing.Default),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Point camera at QR code or upload an image",
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center
                )
                TwPrimaryButton(
                    text = "Upload QR Image",
                    onClick = onUploadQrImage,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Default.AddPhotoAlternate
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TwOutlinedButton(
                        text = "Use PIN",
                        onClick = onSwitchToPin,
                        modifier = Modifier.weight(1f),
                        leadingIcon = Icons.Default.Dialpad
                    )
                    TwSecondaryButton(
                        text = "Nearby",
                        onClick = onSwitchToNearby,
                        modifier = Modifier.weight(1f),
                        leadingIcon = Icons.Default.WifiTethering
                    )
                }
            }
        }

        if (isDecodingImage) {
            DecodingOverlay()
        }
    }
}

@Composable
private fun DecodingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        TwCard(modifier = Modifier.padding(TwSpacing.Large)) {
            Row(
                modifier = Modifier.padding(horizontal = TwSpacing.Large, vertical = TwSpacing.Medium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Reading QR code from image…",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun PinEntryPane(
    pinInput: String,
    onPinChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(TwSpacing.Large)
            .navigationBarsPadding()
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Pin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(Modifier.height(TwSpacing.Default))
        Text(text = "Enter 6-Digit PIN", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Type the temporary session PIN displayed on the sender's device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(TwSpacing.Large))

        // Real focusable BasicTextField wrapping crisp tactile PIN slots
        BasicTextField(
            value = pinInput,
            onValueChange = onPinChange,
            modifier = Modifier.focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (pinInput.length == 6) {
                        keyboardController?.hide()
                        onSubmit()
                    }
                }
            ),
            cursorBrush = SolidColor(Color.Transparent),
            textStyle = TextStyle(color = Color.Transparent),
            decorationBox = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        focusRequester.requestFocus()
                        keyboardController?.show()
                    }
                ) {
                    repeat(6) { index ->
                        val digit = pinInput.getOrNull(index)?.toString() ?: ""
                        val isActiveSlot = index == pinInput.length.coerceAtMost(5)
                        val isFilled = digit.isNotEmpty()
                        Box(
                            modifier = Modifier
                                .size(width = 46.dp, height = 56.dp)
                                .clip(ShapeSmall)
                                .background(
                                    when {
                                        isFilled     -> MaterialTheme.colorScheme.primaryContainer
                                        isActiveSlot -> MaterialTheme.colorScheme.surface
                                        else         -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                                .border(
                                    width = if (isActiveSlot) 2.dp else 1.dp,
                                    color = when {
                                        isActiveSlot -> MaterialTheme.colorScheme.primary
                                        isFilled     -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                        else         -> MaterialTheme.colorScheme.outline
                                    },
                                    shape = ShapeSmall
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = digit,
                                style = MonoLarge.copy(fontSize = 22.sp),
                                color = if (isFilled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        )

        Spacer(Modifier.height(TwSpacing.XLarge))

        TwPrimaryButton(
            text = "Join Transfer Session",
            onClick = {
                keyboardController?.hide()
                onSubmit()
            },
            enabled = pinInput.length == 6,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            leadingIcon = Icons.Default.Link
        )
    }
}

@Composable
private fun NearbyLaunchPane(onStartNearby: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(TwSpacing.Large)
            .navigationBarsPadding()
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(160.dp),
            contentAlignment = Alignment.Center
        ) {
            TwPulseRing(color = MaterialTheme.colorScheme.secondary, modifier = Modifier.fillMaxSize(), ringCount = 3)
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.WifiTethering,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        Spacer(Modifier.height(TwSpacing.Large))
        Text(
            text = "Nearby Offline Receive",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(TwSpacing.Small))
        Text(
            text = "Put this device into discoverable standby over Wi-Fi Direct. Only the sender scans; you simply accept when they connect.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(TwSpacing.XLarge))
        TwSecondaryButton(
            text = "Open Nearby Standby",
            onClick = onStartNearby,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            leadingIcon = Icons.Default.Sensors
        )
    }
}
