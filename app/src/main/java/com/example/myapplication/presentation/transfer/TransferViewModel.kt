package com.example.myapplication.presentation.transfer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.domain.repository.FileSelectionRepository
import com.example.myapplication.service.TransferSessionCoordinator

class TransferViewModel(
    application: Application,
    isSender: Boolean,
    hostIp: String,
    isGroupOwner: Boolean
) : AndroidViewModel(application) {

    val progress = TransferSessionCoordinator.progress

    init {
        val files = if (isSender) FileSelectionRepository.selectedFiles else emptyList()
        TransferSessionCoordinator.startTransfer(
            context = application,
            isSender = isSender,
            hostIp = hostIp,
            isGroupOwner = isGroupOwner,
            files = files
        )
    }

    fun retryTransfer() {
        TransferSessionCoordinator.retryTransfer(getApplication())
    }

    fun cancelTransfer() {
        TransferSessionCoordinator.cancelTransfer()
    }

    fun pauseTransfer() {
        TransferSessionCoordinator.pauseTransfer()
    }

    fun resumeTransfer() {
        TransferSessionCoordinator.resumeTransfer()
    }
}

class TransferViewModelFactory(
    private val application: Application,
    private val isSender: Boolean,
    private val hostIp: String,
    private val isGroupOwner: Boolean
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TransferViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TransferViewModel(application, isSender, hostIp, isGroupOwner) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
