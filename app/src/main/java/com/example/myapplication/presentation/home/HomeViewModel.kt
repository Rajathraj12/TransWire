package com.example.myapplication.presentation.home

import android.app.Application
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.db.AppDatabase
import com.example.myapplication.data.db.TransferEntity
import com.example.myapplication.data.storage.FileStorageManager
import com.example.myapplication.service.TransferSessionCoordinator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class StorageSnapshot(
    val freeBytes: Long,
    val totalBytes: Long
) {
    val usedFraction: Float
        get() = if (totalBytes > 0L) ((totalBytes - freeBytes).toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).transferDao()
    private val fileStorageManager = FileStorageManager(application)

    /** Most recent 4 transfers for the home screen activity card. */
    val recentTransfers: StateFlow<List<TransferEntity>> =
        dao.observeAllTransfers()
            .map { it.take(4) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val completedCount: StateFlow<Int> =
        dao.observeAllTransfers()
            .map { list -> list.count { it.status == "COMPLETED" } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val activeTransferProgress = TransferSessionCoordinator.progress

    fun getStorageSnapshot(): StorageSnapshot {
        val free = fileStorageManager.getAvailableStorageBytes()
        val total = try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            stat.totalBytes
        } catch (_: Exception) {
            0L
        }
        return StorageSnapshot(freeBytes = free, totalBytes = total)
    }
}
