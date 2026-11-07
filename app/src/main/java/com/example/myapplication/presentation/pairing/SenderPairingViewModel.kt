package com.example.myapplication.presentation.pairing

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

    private var countdownJob: Job? = null

    init {
        generateNewSession()
    }

    fun generateNewSession() {
        countdownJob?.cancel()
        
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

        startCountdown()
    }

    private fun startCountdown() {
        countdownJob = viewModelScope.launch {
            while (_timeRemainingSeconds.value > 0) {
                delay(1000)
                _timeRemainingSeconds.value -= 1
            }
            // Session expired
            _session.value = _session.value?.copy(status = PairingStatus.EXPIRED)
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }
}
