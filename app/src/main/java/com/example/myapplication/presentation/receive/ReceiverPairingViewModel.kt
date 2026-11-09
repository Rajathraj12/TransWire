package com.example.myapplication.presentation.receive

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.remote.SignalingClient
import com.example.myapplication.data.remote.SignalingEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReceiverPairingViewModel : ViewModel() {
    private val _scanState = MutableStateFlow<ReceiverState>(ReceiverState.Scanning)
    val scanState: StateFlow<ReceiverState> = _scanState.asStateFlow()

    private var failedAttempts = 0

    init {
        SignalingClient.connect()
        viewModelScope.launch {
            SignalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.PeerJoined -> {
                        _scanState.value = ReceiverState.Paired
                    }
                    is SignalingEvent.JoinRejected -> {
                        _scanState.value = ReceiverState.Error("Connection rejected by sender")
                    }
                    else -> {}
                }
            }
        }
    }

    fun handleQrScanned(qrContent: String) {
        if (_scanState.value !is ReceiverState.Scanning) return
        
        try {
            val uri = Uri.parse(qrContent)
            if (uri.scheme == "tw" && uri.host == "pair") {
                val sid = uri.getQueryParameter("sid")
                val token = uri.getQueryParameter("token")
                if (sid != null && token != null) {
                    SignalingClient.joinSessionWithQr(sid, token)
                    _scanState.value = ReceiverState.WaitingForSenderApproval
                } else {
                    _scanState.value = ReceiverState.Error("Invalid QR code format")
                }
            } else {
                _scanState.value = ReceiverState.Error("Unrecognized QR code")
            }
        } catch (e: Exception) {
            _scanState.value = ReceiverState.Error("Failed to parse QR code")
        }
    }

    fun handlePinEntered(pin: String) {
        if (_scanState.value !is ReceiverState.Scanning) return
        
        if (pin.length == 6) {
            SignalingClient.joinSessionWithPin(pin)
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
    
    override fun onCleared() {
        super.onCleared()
        SignalingClient.disconnect()
    }
}

sealed class ReceiverState {
    object Scanning : ReceiverState()
    object WaitingForSenderApproval : ReceiverState()
    object Paired : ReceiverState()
    data class Error(val message: String) : ReceiverState()
}
