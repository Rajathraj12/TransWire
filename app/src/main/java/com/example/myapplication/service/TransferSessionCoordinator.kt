package com.example.myapplication.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.myapplication.data.transport.TcpTransferTransport
import com.example.myapplication.data.transport.WebRtcTransferTransport
import com.example.myapplication.domain.model.SelectedFile
import com.example.myapplication.domain.model.TransferProgress
import com.example.myapplication.domain.transfer.TransferManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-scoped coordinator that binds active transfers to [TransferService] so transfers
 * survive UI backgrounding and configuration changes.
 */
object TransferSessionCoordinator {

    private const val TAG = "TransferCoordinator"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _progress = MutableStateFlow(TransferProgress("", 0f, 0L, 0L, 0L, 0L))
    val progress: StateFlow<TransferProgress> = _progress.asStateFlow()

    @Volatile
    private var activeManager: TransferManager? = null
    @Volatile
    private var activeJob: Job? = null
    @Volatile
    private var collectorJob: Job? = null
    @Volatile
    private var lastParams: TransferLaunchParams? = null
    @Volatile
    var isCurrentSessionSender: Boolean = false
        private set

    private data class TransferLaunchParams(
        val isSender: Boolean,
        val hostIp: String,
        val isGroupOwner: Boolean,
        val files: List<SelectedFile>
    )

    fun isTransferActive(): Boolean = activeJob?.isActive == true

    fun startTransfer(
        context: Context,
        isSender: Boolean,
        hostIp: String,
        isGroupOwner: Boolean,
        files: List<SelectedFile>
    ) {
        val appContext = context.applicationContext
        lastParams = TransferLaunchParams(isSender, hostIp, isGroupOwner, files)
        isCurrentSessionSender = isSender

        activeManager?.cancelTransfer()
        collectorJob?.cancel()
        activeJob?.cancel()

        _progress.value = TransferProgress(
            currentFileName = if (isSender) "Preparing files..." else "Connecting to sender...",
            fileProgress = 0f,
            totalBytesTransferred = 0L,
            totalBytes = files.sumOf { it.size },
            speedBytesPerSec = 0L,
            estimatedTimeRemainingMs = 0L
        )

        val transport = if (hostIp == "0.0.0.0") {
            WebRtcTransferTransport(appContext)
        } else {
            TcpTransferTransport()
        }

        val manager = TransferManager(
            context = appContext,
            transport = transport,
            isGroupOwner = isGroupOwner,
            hostIp = hostIp,
            isSender = isSender
        )
        activeManager = manager

        startForegroundServiceSafely(appContext)

        collectorJob = scope.launch {
            manager.progress.collect { state ->
                _progress.value = state
            }
        }

        activeJob = scope.launch {
            manager.start(files)
        }
    }

    fun retryTransfer(context: Context) {
        val params = lastParams ?: return
        startTransfer(
            context = context,
            isSender = params.isSender,
            hostIp = params.hostIp,
            isGroupOwner = params.isGroupOwner,
            files = params.files
        )
    }

    fun pauseTransfer() {
        activeManager?.pauseTransfer()
    }

    fun resumeTransfer() {
        activeManager?.resumeTransfer()
    }

    fun cancelTransfer() {
        activeManager?.cancelTransfer()
    }

    fun onProcessTaskRemoved() {
        if (isTransferActive()) {
            activeJob?.cancel()
            activeManager = null
        }
    }

    private fun startForegroundServiceSafely(appContext: Context) {
        try {
            val intent = Intent(appContext, TransferService::class.java).apply {
                action = TransferService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(appContext, intent)
            } else {
                appContext.startService(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start foreground service: ${e.message}")
        }
    }
}

