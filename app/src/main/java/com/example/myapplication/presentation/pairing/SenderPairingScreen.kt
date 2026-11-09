package com.example.myapplication.presentation.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapplication.domain.model.PairingStatus

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote Transfer") },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (session == null) {
                CircularProgressIndicator()
                return@Column
            }

            if (session?.status == PairingStatus.PAIRED) {
                Text(
                    text = "Paired Successfully!",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onNavigateToTransfer) {
                    Text("Start Transfer")
                }
                return@Column
            }

            if (session?.status == PairingStatus.EXPIRED) {
                Text(
                    text = "Session Expired",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { viewModel.generateNewSession() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generate New Code")
                }
                return@Column
            }
            
            if (joinRequestReceiverId != null) {
                Text(
                    text = "Receiver wants to join",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(onClick = { viewModel.rejectRequest() }) {
                        Text("Reject")
                    }
                    Button(onClick = { viewModel.approveRequest() }) {
                        Text("Approve")
                    }
                }
                return@Column
            }

            Text(
                text = "Scan QR Code",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Ask the receiver to scan this QR code.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            Surface(
                shadowElevation = 8.dp,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                if (qrCodeBitmap != null) {
                    Image(
                        bitmap = qrCodeBitmap!!.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier
                            .size(250.dp)
                            .padding(16.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier.size(250.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Or enter PIN:",
                style = MaterialTheme.typography.bodyLarge
            )
            
            Text(
                text = session?.pin ?: "------",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp),
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Code expires in: ${timeRemaining / 60}:${String.format("%02d", timeRemaining % 60)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Waiting for receiver to join...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
