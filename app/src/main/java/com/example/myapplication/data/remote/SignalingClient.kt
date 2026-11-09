package com.example.myapplication.data.remote

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.URISyntaxException

object SignalingClient {
    private var socket: Socket? = null
    
    // For local emulator testing: "http://10.0.2.2:3000"
    // For physical device testing, use the IP of the machine running Node.js: "http://192.168.x.x:3000"
    var serverUrl = "http://10.0.2.2:3000"
    
    private val _events = MutableStateFlow<SignalingEvent?>(null)
    val events: StateFlow<SignalingEvent?> = _events.asStateFlow()

    fun connect() {
        if (socket?.connected() == true) return
        try {
            socket = IO.socket(serverUrl)
            
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("SignalingClient", "Connected to signaling server")
                _events.value = SignalingEvent.Connected
            }
            
            socket?.on("join-request") { args ->
                val data = args[0] as JSONObject
                _events.value = SignalingEvent.JoinRequest(data.getString("receiverId"))
            }
            
            socket?.on("peer-joined") {
                _events.value = SignalingEvent.PeerJoined
            }
            
            socket?.on("join-rejected") {
                _events.value = SignalingEvent.JoinRejected
            }
            
            socket?.on("peer-disconnected") {
                _events.value = SignalingEvent.PeerDisconnected
            }

            socket?.on("offer") { args ->
                val data = args[0] as JSONObject
                _events.value = SignalingEvent.OfferReceived(data.getString("sdp"))
            }
            
            socket?.on("answer") { args ->
                val data = args[0] as JSONObject
                _events.value = SignalingEvent.AnswerReceived(data.getString("sdp"))
            }
            
            socket?.on("ice-candidate") { args ->
                val data = args[0] as JSONObject
                _events.value = SignalingEvent.IceCandidateReceived(data)
            }
            
            socket?.connect()
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
    }

    fun createSession(sessionId: String, token: String, pin: String) {
        val data = JSONObject().apply {
            put("sessionId", sessionId)
            put("pairingToken", token)
            put("pin", pin)
        }
        socket?.emit("create-session", data)
    }

    fun joinSessionWithPin(pin: String) {
        val data = JSONObject().apply {
            put("pin", pin)
        }
        socket?.emit("join-session", data)
    }
    
    fun joinSessionWithQr(sessionId: String, token: String) {
        val data = JSONObject().apply {
            put("sessionId", sessionId)
            put("pairingToken", token)
        }
        socket?.emit("join-session", data)
    }

    fun approvePeer(sessionId: String, receiverId: String) {
        val data = JSONObject().apply {
            put("sessionId", sessionId)
            put("receiverId", receiverId)
        }
        socket?.emit("approve-peer", data)
    }
    
    fun rejectPeer(sessionId: String, receiverId: String) {
        val data = JSONObject().apply {
            put("sessionId", sessionId)
            put("receiverId", receiverId)
        }
        socket?.emit("reject-peer", data)
    }

    fun disconnect() {
        socket?.disconnect()
        socket = null
    }
    
    fun resetEvent() {
        _events.value = null
    }
}

sealed class SignalingEvent {
    object Connected : SignalingEvent()
    data class JoinRequest(val receiverId: String) : SignalingEvent()
    object PeerJoined : SignalingEvent()
    object JoinRejected : SignalingEvent()
    object PeerDisconnected : SignalingEvent()
    
    data class OfferReceived(val sdp: String) : SignalingEvent()
    data class AnswerReceived(val sdp: String) : SignalingEvent()
    data class IceCandidateReceived(val candidate: JSONObject) : SignalingEvent()
}
