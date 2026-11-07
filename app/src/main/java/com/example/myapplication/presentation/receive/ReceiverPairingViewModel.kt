package com.example.myapplication.presentation.receive

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ReceiverPairingViewModel : ViewModel() {
    private val _scanState = MutableStateFlow<ReceiverState>(ReceiverState.Scanning)
    val scanState: StateFlow<ReceiverState> = _scanState.asStateFlow()

    private var failedAttempts = 0

    fun handleQrScanned(qrContent: String) {
        if (_scanState.value !is ReceiverState.Scanning) return
        
        // Basic validation for the phase 2 prototype
        if (qrContent.startsWith("tw://pair?sid=")) {
            _scanState.value = ReceiverState.WaitingForSenderApproval
        } else {
            // Invalid QR
            _scanState.value = ReceiverState.Error("Invalid QR code format")
        }
    }

    fun handlePinEntered(pin: String) {
        if (_scanState.value !is ReceiverState.Scanning) return
        
        if (pin.length == 6) {
            // In a real implementation, we would send this to signaling server.
            // For now, simulate success or fail
            _scanState.value = ReceiverState.WaitingForSenderApproval
        } else {
            failedAttempts++
            if (failedAttempts >= 3) {
                _scanState.value = ReceiverState.Error("Too many failed attempts. Try again later.")
            }
        }
    }
    
    fun resetScan() {
        failedAttempts = 0
        _scanState.value = ReceiverState.Scanning
    }
}

sealed class ReceiverState {
    object Scanning : ReceiverState()
    object WaitingForSenderApproval : ReceiverState()
    data class Error(val message: String) : ReceiverState()
}
