package com.example.myapplication.presentation.pairing

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.remote.SignalingClient
import com.example.myapplication.data.remote.SignalingEvent
import com.example.myapplication.domain.model.PairingSession
import com.example.myapplication.domain.model.PairingStatus
import com.example.myapplication.util.PairingUtils
import com.example.myapplication.util.QRCodeGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SenderPairingViewModel : ViewModel() {
    private val _session = MutableStateFlow<PairingSession?>(null)
    val session: StateFlow<PairingSession?> = _session.asStateFlow()

    private val _qrCodeBitmap = MutableStateFlow<Bitmap?>(null)
    val qrCodeBitmap: StateFlow<Bitmap?> = _qrCodeBitmap.asStateFlow()

    private val _timeRemainingSeconds = MutableStateFlow(0L)
    val timeRemainingSeconds: StateFlow<Long> = _timeRemainingSeconds.asStateFlow()

    private val _joinRequestReceiverId = MutableStateFlow<String?>(null)
    val joinRequestReceiverId: StateFlow<String?> = _joinRequestReceiverId.asStateFlow()

    private val _isSessionReady = MutableStateFlow(false)
    val isSessionReady: StateFlow<Boolean> = _isSessionReady.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private var countdownJob: Job? = null

    init {
        SignalingClient.connect()

        viewModelScope.launch {
            SignalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.Connected -> {
                        _connectionError.value = null
                    }
                    is SignalingEvent.SessionCreated -> {
                        if (event.sessionId == _session.value?.sessionId) {
                            _isSessionReady.value = true
                            _connectionError.value = null
                        }
                    }
                    is SignalingEvent.ConnectionError -> {
                        _connectionError.value = "Unable to reach signaling server. Check internet or Settings."
                    }
                    is SignalingEvent.JoinRequest -> {
                        _joinRequestReceiverId.value = event.receiverId
                    }
                    is SignalingEvent.PeerJoined -> {
                        // Clear temporary pairing credentials from active UI state once paired
                        _qrCodeBitmap.value = null
                        _joinRequestReceiverId.value = null
                        _session.value = _session.value?.copy(
                            status = PairingStatus.PAIRED,
                            pairingToken = "",
                            pin = ""
                        )
                    }
                    is SignalingEvent.JoinError -> {
                        _isSessionReady.value = false
                        _session.value = _session.value?.copy(status = PairingStatus.EXPIRED)
                    }
                    else -> {}
                }
            }
        }

        generateNewSession()
    }

    fun generateNewSession() {
        countdownJob?.cancel()
        _joinRequestReceiverId.value = null
        _isSessionReady.value = false
        _connectionError.value = null
        _session.value?.sessionId?.let { previousId ->
            if (_session.value?.status != PairingStatus.PAIRED) {
                SignalingClient.leaveSession(previousId)
            }
        }

        val sessionId = PairingUtils.generateSessionId()
        val pairingToken = PairingUtils.generatePairingToken()
        val pin = PairingUtils.generatePin()
        val expiresInSeconds = 120L // 2 minutes expiry
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000)

        val newSession = PairingSession(
            sessionId = sessionId,
            pairingToken = pairingToken,
            pin = pin,
            expiresAt = expiresAt
        )

        _session.value = newSession
        _timeRemainingSeconds.value = expiresInSeconds

        viewModelScope.launch {
            val qrContent = PairingUtils.generateQrContent(sessionId, pairingToken)
            _qrCodeBitmap.value = QRCodeGenerator.generateQrCode(qrContent)
        }

        SignalingClient.createSession(sessionId, pairingToken, pin)

        startCountdown()
    }

    fun retryConnection() {
        val current = _session.value
        if (current != null && current.status == PairingStatus.PENDING && _timeRemainingSeconds.value > 0) {
            _connectionError.value = null
            SignalingClient.connect()
            SignalingClient.createSession(current.sessionId, current.pairingToken, current.pin)
        } else {
            generateNewSession()
        }
    }

    fun approveRequest() {
        val receiverId = _joinRequestReceiverId.value
        val sessionId = _session.value?.sessionId
        if (receiverId != null && sessionId != null) {
            SignalingClient.approvePeer(sessionId, receiverId)
            _joinRequestReceiverId.value = null
        }
    }

    fun rejectRequest() {
        val receiverId = _joinRequestReceiverId.value
        val sessionId = _session.value?.sessionId
        if (receiverId != null && sessionId != null) {
            SignalingClient.rejectPeer(sessionId, receiverId)
            _joinRequestReceiverId.value = null
        }
    }

    private fun startCountdown() {
        countdownJob = viewModelScope.launch {
            while (_timeRemainingSeconds.value > 0 && _session.value?.status != PairingStatus.PAIRED) {
                delay(1000)
                _timeRemainingSeconds.value -= 1
            }
            if (_session.value?.status != PairingStatus.PAIRED) {
                _qrCodeBitmap.value = null
                _isSessionReady.value = false
                _session.value?.sessionId?.let { SignalingClient.leaveSession(it) }
                _session.value = _session.value?.copy(
                    status = PairingStatus.EXPIRED,
                    pairingToken = "",
                    pin = ""
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        if (_session.value?.status != PairingStatus.PAIRED) {
            _session.value?.sessionId?.let { SignalingClient.leaveSession(it) }
            SignalingClient.disconnect()
        }
    }
}
