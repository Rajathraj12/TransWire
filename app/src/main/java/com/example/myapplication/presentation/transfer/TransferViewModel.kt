package com.example.myapplication.presentation.transfer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.transport.TcpTransferTransport
import com.example.myapplication.domain.repository.FileSelectionRepository
import com.example.myapplication.domain.transfer.TransferManager
import kotlinx.coroutines.launch

class TransferViewModel(
    application: Application,
    isSender: Boolean,
    hostIp: String,
    isGroupOwner: Boolean
) : AndroidViewModel(application) {

    private val transferManager = TransferManager(
        context = application,
        transport = TcpTransferTransport(),
        isGroupOwner = isGroupOwner,
        hostIp = hostIp,
        isSender = isSender
    )

    val progress = transferManager.progress

    init {
        viewModelScope.launch {
            val files = if (isSender) FileSelectionRepository.selectedFiles else emptyList()
            transferManager.start(files)
        }
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
