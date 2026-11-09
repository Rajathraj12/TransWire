package com.example.myapplication.presentation.transfer

import android.app.Application
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isSender) "Sending Files" else "Receiving Files") },
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            
            if (progress.isCompleted) {
                if (progress.error != null) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Error",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Transfer Failed",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(text = progress.error!!, textAlign = TextAlign.Center)
                } else {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Success",
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Transfer Complete",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = onNavigateHome) {
                    Text("Return to Home")
                }
                return@Column
            }

            Spacer(modifier = Modifier.height(32.dp))

            CircularProgressIndicator(
                progress = { progress.fileProgress },
                modifier = Modifier.size(120.dp),
                strokeWidth = 8.dp
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            Text(
                text = progress.currentFileName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(32.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val totalFormatted = Formatter.formatFileSize(context, progress.totalBytes)
                    val currentFormatted = Formatter.formatFileSize(context, progress.totalBytesTransferred)
                    val speedFormatted = Formatter.formatFileSize(context, progress.speedBytesPerSec) + "/s"
                    
                    val millis = progress.estimatedTimeRemainingMs
                    val etaFormatted = String.format(
                        "%02d:%02d",
                        TimeUnit.MILLISECONDS.toMinutes(millis),
                        TimeUnit.MILLISECONDS.toSeconds(millis) -
                                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))
                    )

                    TransferStatRow("Overall Progress", "$currentFormatted / $totalFormatted")
                    TransferStatRow("Transfer Speed", speedFormatted)
                    TransferStatRow("Time Remaining", etaFormatted)
                }
            }
        }
    }
}

@Composable
fun TransferStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
