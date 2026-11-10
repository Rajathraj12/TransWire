package com.example.myapplication.presentation.nearby

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.presentation.components.*
import com.example.myapplication.presentation.theme.*

@Composable
fun NearbyDiscoveryScreen(
    isSender: Boolean,
    onNavigateToTransfer: (String, Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: NearbyDiscoveryViewModel = viewModel()
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val connectionInfo by viewModel.connectionInfo.collectAsState()
    val thisDeviceName by viewModel.thisDeviceName.collectAsState()
    val error by viewModel.error.collectAsState()

    var hasAutoStartedSender by remember { mutableStateOf(false) }
    var connectingAddress by remember { mutableStateOf<String?>(null) }

    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    var hasPermissions by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
    }

    LaunchedEffect(hasPermissions) {
        if (!hasPermissions) {
            permissionLauncher.launch(requiredPermissions)
        } else {
            viewModel.startDiscovery()
        }
    }

    LaunchedEffect(error) {
        if (error != null) {
            connectingAddress = null
        }
    }

    // Sender auto-enters TransferScreen once Wi-Fi Direct group forms and waits for receiver acceptance
    LaunchedEffect(isSender, connectionInfo) {
        val info = connectionInfo
        if (isSender && !hasAutoStartedSender && info != null && info.groupFormed && info.groupOwnerAddress != null) {
            hasAutoStartedSender = true
            onNavigateToTransfer(info.groupOwnerAddress?.hostAddress ?: "", info.isGroupOwner)
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopDiscovery() }
    }

    Scaffold(
        topBar = {
            TwTopBar(
                title = if (isSender) "Find Receiver Nearby" else "Receive Nearby",
                subtitle = if (isSender) "Wi-Fi Direct offline discovery" else "Discoverable standby",
                onBack = onNavigateBack,
                actions = {
                    if (isSender && hasPermissions && !isDiscovering && connectionInfo == null) {
                        IconButton(onClick = { viewModel.startDiscovery() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Scan again")
                        }
                    }
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
            if (!hasPermissions) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(TwSpacing.Large),
                    contentAlignment = Alignment.Center
                ) {
                    TwEmptyState(
                        title = "Nearby Permission Required",
                        subtitle = "TransWire needs Wi-Fi Direct / Location permission to discover nearby devices offline.",
                        icon = Icons.Default.WifiOff,
                        action = "Grant Permissions" to { permissionLauncher.launch(requiredPermissions) }
                    )
                }
                return@Scaffold
            }

            if (connectionInfo != null && connectionInfo!!.groupFormed) {
                val info = connectionInfo!!
                val ip = info.groupOwnerAddress?.hostAddress ?: ""
                NearbyConnectedView(
                    isSender = isSender,
                    onConfirm = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onNavigateToTransfer(ip, info.isGroupOwner)
                    },
                    onDecline = {
                        hasAutoStartedSender = false
                        connectingAddress = null
                        viewModel.disconnect()
                        viewModel.startDiscovery()
                    }
                )
                return@Scaffold
            }

            Column(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(visible = error != null, enter = fadeIn(), exit = fadeOut()) {
                    error?.let { msg ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(TwSpacing.Default),
                            shape = ShapeDefault,
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(TwSpacing.Default),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                if (isSender) {
                    SenderDiscoveryView(
                        isDiscovering = isDiscovering,
                        peers = peers,
                        connectingAddress = connectingAddress,
                        onConnect = { address ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            connectingAddress = address
                            viewModel.connectTo(address)
                        },
                        onRescan = {
                            connectingAddress = null
                            viewModel.startDiscovery()
                        }
                    )
                } else {
                    ReceiverStandbyView(
                        thisDeviceName = thisDeviceName,
                        isDiscovering = isDiscovering,
                        onMakeDiscoverable = { viewModel.startDiscovery() }
                    )
                }
            }
        }
    }
}

@Composable
private fun SenderDiscoveryView(
    isDiscovering: Boolean,
    peers: List<android.net.wifi.p2p.WifiP2pDevice>,
    connectingAddress: String?,
    onConnect: (String) -> Unit,
    onRescan: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isDiscovering) {
                TwPulseRing(color = primaryColor, modifier = Modifier.size(200.dp), ringCount = 3)
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(primaryColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        Text(
            text = when {
                connectingAddress != null -> "Establishing Wi-Fi Direct link…"
                isDiscovering             -> "Scanning for nearby receivers…"
                peers.isEmpty()           -> "No nearby receivers found"
                else                      -> "${peers.size} receiver${if (peers.size > 1) "s" else ""} ready"
            },
            style = MaterialTheme.typography.titleSmall,
            color = if (connectingAddress != null || isDiscovering) primaryColor
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = TwSpacing.Large)
        )

        Spacer(Modifier.height(TwSpacing.Default))

        if (peers.isEmpty() && !isDiscovering) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = TwSpacing.Large),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Ask the receiver to open\nReceive → Nearby Standby on their phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(TwSpacing.Default))
                    TwOutlinedButton("Scan Again", onRescan, leadingIcon = Icons.Default.Refresh)
                }
            }
        } else {
            if (peers.isNotEmpty()) {
                TwSectionLabel(
                    text = "Available Receivers",
                    modifier = Modifier.padding(horizontal = TwSpacing.Default)
                )
                Spacer(Modifier.height(TwSpacing.Small))
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = TwSpacing.Default, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(peers, key = { it.deviceAddress }) { peer ->
                    val isConnecting = connectingAddress == peer.deviceAddress
                    PeerDeviceCard(
                        peer = peer,
                        isConnecting = isConnecting,
                        onConnect = { onConnect(peer.deviceAddress) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiverStandbyView(
    thisDeviceName: String?,
    isDiscovering: Boolean,
    onMakeDiscoverable: () -> Unit
) {
    val secondaryColor = MaterialTheme.colorScheme.secondary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(TwSpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier.size(196.dp),
            contentAlignment = Alignment.Center
        ) {
            if (isDiscovering) {
                TwPulseRing(color = secondaryColor, modifier = Modifier.fillMaxSize(), ringCount = 3)
            }
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(secondaryColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.WifiTethering,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
            }
        }

        Spacer(Modifier.height(TwSpacing.Large))

        Text(
            text = "Waiting for Sender",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(TwSpacing.Small))

        if (!thisDeviceName.isNullOrBlank()) {
            Surface(
                shape = ShapePill,
                color = MaterialTheme.colorScheme.secondaryContainer,
                border = androidx.compose.foundation.BorderStroke(1.dp, secondaryColor.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        tint = secondaryColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = thisDeviceName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        Text(
            text = "Your device is visible to nearby senders.\nOnce a sender connects, you can accept to start receiving files.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (!isDiscovering) {
            Spacer(Modifier.height(TwSpacing.Large))
            TwOutlinedButton(
                text = "Make Discoverable Again",
                onClick = onMakeDiscoverable,
                leadingIcon = Icons.Default.Refresh
            )
        }
    }
}

@Composable
private fun NearbyConnectedView(
    isSender: Boolean,
    onConfirm: () -> Unit,
    onDecline: () -> Unit
) {
    val accent = if (isSender) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(TwSpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f))
                .border(1.dp, accent.copy(alpha = 0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(44.dp)
            )
        }
        Spacer(Modifier.height(TwSpacing.Large))
        Text(
            text = if (isSender) "Connected to Receiver" else "Incoming Nearby Transfer",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(TwSpacing.Small))
        Text(
            text = if (isSender)
                "Ready to send files.\nWaiting for receiver to accept."
            else
                "A nearby sender wants to send files to this device.\nAccept to begin receiving.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(TwSpacing.XLarge))
        if (isSender) {
            TwPrimaryButton(
                text = "Start Sending",
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                leadingIcon = Icons.AutoMirrored.Filled.Send
            )
        } else {
            TwSecondaryButton(
                text = "Accept & Receive Files",
                onClick = onConfirm,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                leadingIcon = Icons.Default.Download
            )
        }
        Spacer(Modifier.height(10.dp))
        TwDestructiveButton(
            text = if (isSender) "Disconnect" else "Decline",
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PeerDeviceCard(
    peer: android.net.wifi.p2p.WifiP2pDevice,
    isConnecting: Boolean,
    onConnect: () -> Unit
) {
    TwCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = if (isConnecting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        onClick = onConnect
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(TwSpacing.Default),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(ShapeSmall)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = "Receiver Device",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.deviceName.ifBlank { "Nearby Device" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (isConnecting) "Connecting…" else "Tap to connect • ${peer.deviceAddress}",
                    style = MonoSmall,
                    color = if (isConnecting) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Connect",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
