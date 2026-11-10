package com.example.myapplication.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.domain.model.TransferProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TransferService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressObserverJob: Job? = null
    private var lastNotificationUpdateMs = 0L
    private val largeLogoBitmap: Bitmap? by lazy {
        runCatching { BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher) }.getOrNull()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                TransferSessionCoordinator.pauseTransfer()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                TransferSessionCoordinator.resumeTransfer()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                TransferSessionCoordinator.cancelTransfer()
                return START_NOT_STICKY
            }
        }

        val initialNotification = buildProgressNotification(TransferSessionCoordinator.progress.value)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            initialNotification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )

        startObservingProgress()
        return START_NOT_STICKY
    }

    private fun startObservingProgress() {
        if (progressObserverJob?.isActive == true) return
        progressObserverJob = serviceScope.launch {
            TransferSessionCoordinator.progress.collect { state ->
                if (state.isCompleted) {
                    handleTransferFinished(state)
                    return@collect
                }

                val now = System.currentTimeMillis()
                if (now - lastNotificationUpdateMs >= 400L || state.isPaused) {
                    lastNotificationUpdateMs = now
                    notifyIfPermitted(NOTIFICATION_ID, buildProgressNotification(state))
                }
            }
        }
    }

    private fun handleTransferFinished(state: TransferProgress) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        val isSender = TransferSessionCoordinator.isCurrentSessionSender
        val title = when {
            state.isCancelled -> "Transfer Cancelled"
            state.error != null -> "Transfer Failed"
            isSender -> "Files Sent"
            else -> "Files Received"
        }
        val body = when {
            state.isCancelled -> "The file transfer was cancelled."
            state.error != null -> state.error
            else -> {
                val count = state.transferredFiles.count { it.status == "COMPLETED" }
                if (isSender) "$count file(s) sent successfully."
                else "$count file(s) saved to Downloads/Transwire."
            }
        }

        val completionNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeLogoBitmap)
            .setContentIntent(createMainActivityPendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notifyIfPermitted(COMPLETION_NOTIFICATION_ID, completionNotification)
        progressObserverJob?.cancel()
        stopSelf()
    }

    private fun buildProgressNotification(state: TransferProgress): Notification {
        val isSender = TransferSessionCoordinator.isCurrentSessionSender
        val direction = if (isSender) "Sending" else "Receiving"
        val title = when {
            state.isPaused -> "$direction Paused"
            state.currentFileName.isNotBlank() -> "$direction: ${state.currentFileName}"
            else -> "$direction Files"
        }

        val percent = if (state.totalBytes > 0L) {
            ((state.totalBytesTransferred * 100L) / state.totalBytes).toInt().coerceIn(0, 100)
        } else {
            (state.fileProgress * 100f).toInt().coerceIn(0, 100)
        }

        val transferredText = Formatter.formatFileSize(this, state.totalBytesTransferred)
        val totalText = Formatter.formatFileSize(this, state.totalBytes)
        val speedText = if (state.speedBytesPerSec > 0L && !state.isPaused) {
            " • ${Formatter.formatFileSize(this, state.speedBytesPerSec)}/s"
        } else {
            ""
        }
        val contentText = if (state.totalBytes > 0L) {
            "$transferredText / $totalText ($percent%)$speedText"
        } else {
            "Preparing secure transfer..."
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeLogoBitmap)
            .setContentIntent(createMainActivityPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, state.totalBytes <= 0L)

        if (state.isPaused) {
            builder.addAction(
                0,
                "Resume",
                createServiceActionPendingIntent(ACTION_RESUME, 101)
            )
        } else {
            builder.addAction(
                0,
                "Pause",
                createServiceActionPendingIntent(ACTION_PAUSE, 102)
            )
        }
        builder.addAction(
            0,
            "Cancel",
            createServiceActionPendingIntent(ACTION_CANCEL, 103)
        )

        return builder.build()
    }

    private fun createMainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createServiceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TransferService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun notifyIfPermitted(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(id, notification)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        TransferSessionCoordinator.onProcessTaskRemoved()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        progressObserverJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays progress and completion status for file transfers"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "com.example.myapplication.action.START_TRANSFER"
        const val ACTION_PAUSE = "com.example.myapplication.action.PAUSE_TRANSFER"
        const val ACTION_RESUME = "com.example.myapplication.action.RESUME_TRANSFER"
        const val ACTION_CANCEL = "com.example.myapplication.action.CANCEL_TRANSFER"

        private const val CHANNEL_ID = "TransferChannel"
        private const val NOTIFICATION_ID = 1
        private const val COMPLETION_NOTIFICATION_ID = 2
    }
}
