package com.example.myapplication.presentation.receive

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.remote.SignalingClient
import com.example.myapplication.data.remote.SignalingEvent
import com.example.myapplication.util.PairingShareHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReceiverPairingViewModel : ViewModel() {
    private val _scanState = MutableStateFlow<ReceiverState>(ReceiverState.Scanning)
    val scanState: StateFlow<ReceiverState> = _scanState.asStateFlow()

    private val _isDecodingImage = MutableStateFlow(false)
    val isDecodingImage: StateFlow<Boolean> = _isDecodingImage.asStateFlow()

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
                    is SignalingEvent.JoinError -> {
                        _scanState.value = ReceiverState.Error(event.message)
                    }
                    is SignalingEvent.ConnectionError -> {
                        if (_scanState.value is ReceiverState.WaitingForSenderApproval) {
                            _scanState.value = ReceiverState.Error("Signaling server unreachable")
                        }
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
                val sid = uri.getQueryParameter("sid")?.trim()
                val token = uri.getQueryParameter("token")?.trim()
                if (!sid.isNullOrEmpty() && sid.length in 10..100 && !token.isNullOrEmpty() && token.length in 16..128) {
                    SignalingClient.joinSessionWithQr(sid, token)
                    _scanState.value = ReceiverState.WaitingForSenderApproval
                } else {
                    _scanState.value = ReceiverState.Error("Invalid QR code format")
                }
            } else {
                _scanState.value = ReceiverState.Error("Unrecognized QR code")
            }
        } catch (_: Exception) {
            _scanState.value = ReceiverState.Error("Failed to parse QR code")
        }
    }

    fun handleQrImageUri(context: Context, imageUri: Uri) {
        if (_scanState.value !is ReceiverState.Scanning || _isDecodingImage.value) return
        val appContext = context.applicationContext
        viewModelScope.launch {
            _isDecodingImage.value = true
            try {
                val decoded = PairingShareHelper.decodeQrFromUri(appContext, imageUri)
                if (!decoded.isNullOrBlank()) {
                    handleQrScanned(decoded)
                } else {
                    _scanState.value = ReceiverState.Error(
                        "No TransWire QR code found in that image. Try a clearer image or enter the 6-digit PIN."
                    )
                }
            } finally {
                _isDecodingImage.value = false
            }
        }
    }

    fun handlePinEntered(pin: String) {
        if (_scanState.value !is ReceiverState.Scanning) return

        if (pin.length == 6 && pin.all { it.isDigit() }) {
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
        _isDecodingImage.value = false
        SignalingClient.leaveSession()
        SignalingClient.clearWebRtcSignals()
        _scanState.value = ReceiverState.Scanning
    }

    fun cancelPairing() {
        failedAttempts = 0
        _isDecodingImage.value = false
        SignalingClient.leaveSession()
        SignalingClient.disconnect()
        _scanState.value = ReceiverState.Scanning
    }

    override fun onCleared() {
        super.onCleared()
        if (_scanState.value !is ReceiverState.Paired) {
            SignalingClient.disconnect()
        }
    }
}

sealed class ReceiverState {
    object Scanning : ReceiverState()
    object WaitingForSenderApproval : ReceiverState()
    object Paired : ReceiverState()
    data class Error(val message: String) : ReceiverState()
}
