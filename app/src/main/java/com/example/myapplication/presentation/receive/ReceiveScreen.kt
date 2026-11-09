package com.example.myapplication.presentation.receive

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.util.BarcodeAnalyzer
import com.example.myapplication.util.CameraPreview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    onNavigateBack: () -> Unit,
    onNavigateToNearby: () -> Unit,
    viewModel: ReceiverPairingViewModel = viewModel()
) {
    val scanState by viewModel.scanState.collectAsState()
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    var pinInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive Files") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = scanState) {
                is ReceiverState.Paired -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Paired Successfully!",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(onClick = { /* TODO: Move to Remote WebRTC Transfer Screen */ }) {
                            Text("Start Transfer")
                        }
                    }
                }
                is ReceiverState.WaitingForSenderApproval -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Waiting for sender approval...",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Pairing successful. Waiting for sender to confirm.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(onClick = { viewModel.resetScan() }) {
                            Text("Cancel")
                        }
                    }
                }
                is ReceiverState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.resetScan() }) {
                            Text("Try Again")
                        }
                    }
                }
                is ReceiverState.Scanning -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (hasCameraPermission) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                val analyzer = remember {
                                    BarcodeAnalyzer { barcodeValue ->
                                        viewModel.handleQrScanned(barcodeValue)
                                    }
                                }
                                CameraPreview(analyzer = analyzer)
                                
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // Target overlay
                                    Surface(
                                        modifier = Modifier.size(250.dp),
                                        color = androidx.compose.ui.graphics.Color.Transparent,
                                        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                                        shape = MaterialTheme.shapes.medium
                                    ) {}
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Camera permission is required to scan QR codes.")
                            }
                        }
                        
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shadowElevation = 8.dp,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Or enter 6-digit PIN",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = pinInput,
                                        onValueChange = { if (it.length <= 6) pinInput = it.filter { char -> char.isDigit() } },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        placeholder = { Text("123456") }
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Button(
                                        onClick = { viewModel.handlePinEntered(pinInput) },
                                        enabled = pinInput.length == 6
                                    ) {
                                        Text("Join")
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(24.dp))
                                Divider()
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                OutlinedButton(
                                    onClick = onNavigateToNearby,
                                    modifier = Modifier.fillMaxWidth().height(56.dp)
                                ) {
                                    Text("Nearby Transfer (Wi-Fi Direct)")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
