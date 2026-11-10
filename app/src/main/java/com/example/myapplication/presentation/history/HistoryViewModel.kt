package com.example.myapplication.presentation.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.db.AppDatabase
import com.example.myapplication.data.db.TransferEntity
import com.example.myapplication.data.storage.FileStorageManager
import com.example.myapplication.service.TransferSessionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val transferDao = AppDatabase.getDatabase(application).transferDao()
    private val fileStorageManager = FileStorageManager(application)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (!TransferSessionCoordinator.isTransferActive()) {
                transferDao.markOrphanedTransfersInterrupted()
                val protectedPaths = transferDao.getResumableTempPaths().toSet()
                fileStorageManager.cleanupStaleTempFiles(protectedPaths)
            }
        }
    }

    val history: StateFlow<List<TransferEntity>> = transferDao.observeAllTransfers()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )
}

class HistoryViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HistoryViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
