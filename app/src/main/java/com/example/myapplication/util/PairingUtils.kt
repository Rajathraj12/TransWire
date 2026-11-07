package com.example.myapplication.util

import java.security.SecureRandom
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object PairingUtils {
    private val secureRandom = SecureRandom()
    
    fun generateSessionId(): String {
        return UUID.randomUUID().toString()
    }
    
    @OptIn(ExperimentalEncodingApi::class)
    fun generatePairingToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.encode(bytes)
    }
    
    fun generatePin(): String {
        val pin = secureRandom.nextInt(1000000)
        return String.format("%06d", pin) // 6-digit PIN
    }
    
    fun generateQrContent(sessionId: String, pairingToken: String): String {
        // We use a custom URI scheme to prevent accidental parsing by other apps
        return "tw://pair?sid=$sessionId&token=$pairingToken"
    }
}
