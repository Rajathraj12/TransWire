package com.example.myapplication.domain.model

data class PairingSession(
    val sessionId: String,
    val pairingToken: String,
    val pin: String,
    val expiresAt: Long,
    val status: PairingStatus = PairingStatus.PENDING
)

enum class PairingStatus {
    PENDING,
    PAIRED,
    EXPIRED,
    FAILED
}
