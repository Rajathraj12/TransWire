package com.example.myapplication.util

import android.net.Uri
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
        // UrlSafe Base64 avoids '+' and '/' characters that Uri.getQueryParameter turns into spaces
        return Base64.UrlSafe.encode(bytes)
    }

    fun generatePin(): String {
        val pin = secureRandom.nextInt(1000000)
        return String.format("%06d", pin) // 6-digit PIN
    }

    fun generateQrContent(sessionId: String, pairingToken: String): String {
        return Uri.Builder()
            .scheme("tw")
            .authority("pair")
            .appendQueryParameter("sid", sessionId)
            .appendQueryParameter("token", pairingToken)
            .build()
            .toString()
    }
}
