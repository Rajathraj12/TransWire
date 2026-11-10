package com.example.myapplication.data.remote

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.net.HttpURLConnection
import java.net.URISyntaxException
import java.net.URL

object SignalingClient {
    private var socket: Socket? = null

    const val DEFAULT_SERVER_URL = "http://172.21.2.57:3000"
    const val EMULATOR_SERVER_URL = "http://10.0.2.2:3000"

    // For local emulator testing: "http://10.0.2.2:3000"
    // For physical device testing, use the LAN IP of the PC running Node.js or a public HTTPS tunnel URL
    @Volatile
    var serverUrl = DEFAULT_SERVER_URL

    var activeSessionId: String? = null
    
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<SignalingEvent>(
        replay = 50, 
        extraBufferCapacity = 50
    )
    val events = _events.asSharedFlow()

    fun updateServerUrl(rawUrl: String): Boolean {
        val normalized = rawUrl.trim().trimEnd('/')
        if (!normalized.startsWith("http://", ignoreCase = true) &&
            !normalized.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }
        if (normalized != serverUrl) {
            disconnect()
            serverUrl = normalized
        }
        return true
    }

    suspend fun checkServerHealth(targetUrl: String = serverUrl): Result<String> = withContext(Dispatchers.IO) {
        val base = targetUrl.trim().trimEnd('/')
        if (!base.startsWith("http://", ignoreCase = true) &&
            !base.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext Result.failure(IllegalArgumentException("URL must start with http:// or https://"))
        }
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$base/health")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3500
                readTimeout = 3500
            }
            val code = conn.responseCode
            if (code == 200) {
                Result.success("Server reachable (HTTP 200)")
            } else {
                Result.failure(Exception("Server responded with HTTP $code"))
            }
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Unable to reach server"))
        } finally {
            conn?.disconnect()
        }
    }

    fun connect() {
        if (socket?.connected() == true) return
        try {
            socket?.off()
            socket?.disconnect()
            socket = IO.socket(serverUrl)
            
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("SignalingClient", "Connected to signaling server")
                _events.tryEmit(SignalingEvent.Connected)
            }
            
            socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val detail = args.firstOrNull()?.toString() ?: "Unknown connection error"
                Log.e("SignalingClient", "Connection error: $detail")
                _events.tryEmit(SignalingEvent.ConnectionError(detail))
            }
            
            socket?.on("join-request") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val receiverId = data.optString("receiverId")
                if (receiverId.isNotEmpty()) {
                    _events.tryEmit(SignalingEvent.JoinRequest(receiverId))
                }
            }
            
            socket?.on("peer-joined") {
                _events.tryEmit(SignalingEvent.PeerJoined)
            }
            
            socket?.on("join-rejected") {
                _events.tryEmit(SignalingEvent.JoinRejected)
            }
            
            socket?.on("peer-disconnected") {
                _events.tryEmit(SignalingEvent.PeerDisconnected)
            }

            socket?.on("offer") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val sdp = data.optString("sdp")
                if (sdp.isNotEmpty()) {
                    _events.tryEmit(SignalingEvent.OfferReceived(sdp))
                }
            }
            
            socket?.on("answer") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val sdp = data.optString("sdp")
                if (sdp.isNotEmpty()) {
                    _events.tryEmit(SignalingEvent.AnswerReceived(sdp))
                }
            }
            
            socket?.on("ice-candidate") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val candidateObj = data.optJSONObject("candidate") ?: return@on
                val candidateStr = candidateObj.optString("candidate")
                if (candidateStr.isNotEmpty()) {
                    _events.tryEmit(SignalingEvent.IceCandidateReceived(
                        candidateObj.optString("sdpMid", ""),
                        candidateObj.optInt("sdpMLineIndex", 0),
                        candidateStr
                    ))
                }
            }
            
            socket?.connect()
        } catch (e: URISyntaxException) {
            Log.e("SignalingClient", "Invalid signaling URL", e)
        }
    }

    fun createSession(sessionId: String, token: String, pin: String) {
        activeSessionId = sessionId
        val data = JSONObject().apply {
            put("sessionId", sessionId)
            put("pairingToken", token)
            put("pin", pin)
        }
        socket?.emit("create-session", data, io.socket.client.Ack { args ->
            val response = args.firstOrNull() as? JSONObject ?: return@Ack
            if (!response.optBoolean("success", true)) {
                _events.tryEmit(SignalingEvent.JoinError(response.optString("error", "Failed to create session")))
            }
        })
    }

    fun joinSessionWithPin(pin: String) {
        val data = JSONObject().apply {
            put("pin", pin)
        }
        socket?.emit("join-session", data, io.socket.client.Ack { args ->
            val response = args.firstOrNull() as? JSONObject ?: return@Ack
            if (response.optBoolean("success")) {
                activeSessionId = response.optString("sessionId").takeIf { it.isNotEmpty() }
            } else {
                _events.tryEmit(SignalingEvent.JoinError(response.optString("error", "Invalid or expired PIN")))
            }
        })
    }
    
    fun joinSessionWithQr(sessionId: String, token: String) {
        val data = JSONObject().apply {
            put("sessionId", sessionId)
            put("pairingToken", token)
        }
        socket?.emit("join-session", data, io.socket.client.Ack { args ->
            val response = args.firstOrNull() as? JSONObject ?: return@Ack
            if (response.optBoolean("success")) {
                activeSessionId = response.optString("sessionId").takeIf { it.isNotEmpty() }
            } else {
                _events.tryEmit(SignalingEvent.JoinError(response.optString("error", "Invalid or expired QR session")))
            }
        })
    }

    fun leaveSession(sessionId: String? = activeSessionId) {
        val sid = sessionId ?: return
        val data = JSONObject().apply {
            put("sessionId", sid)
        }
        socket?.emit("leave-session", data)
        if (activeSessionId == sid) {
            activeSessionId = null
        }
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

    fun sendOffer(sdp: String) {
        val data = JSONObject().apply {
            put("sessionId", activeSessionId)
            put("sdp", sdp)
        }
        socket?.emit("offer", data)
    }

    fun sendAnswer(sdp: String) {
        val data = JSONObject().apply {
            put("sessionId", activeSessionId)
            put("sdp", sdp)
        }
        socket?.emit("answer", data)
    }

    fun sendIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val candidateObj = JSONObject().apply {
            put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
            put("candidate", candidate)
        }
        val data = JSONObject().apply {
            put("sessionId", activeSessionId)
            put("candidate", candidateObj)
        }
        socket?.emit("ice-candidate", data)
    }

    suspend fun getTurnCredentials(): List<PeerConnection.IceServer> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<PeerConnection.IceServer>()
        // Always add a public STUN server for direct P2P resolution
        servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        
        try {
            val querySuffix = activeSessionId?.let { "?sessionId=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
            val url = URL("$serverUrl/turn-credentials$querySuffix")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            activeSessionId?.let { conn.setRequestProperty("X-Session-Id", it) }
            
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val username = json.getString("username")
                val password = json.getString("password")
                val uris = json.getJSONArray("uris")
                
                for (i in 0 until uris.length()) {
                    servers.add(
                        PeerConnection.IceServer.builder(uris.getString(i))
                            .setUsername(username)
                            .setPassword(password)
                            .createIceServer()
                    )
                }
                Log.d("SignalingClient", "Fetched TURN server configuration")
            } else {
                Log.e("SignalingClient", "Failed to fetch TURN configuration: HTTP ${conn.responseCode}")
            }
        } catch (e: Exception) {
            Log.e("SignalingClient", "Error fetching TURN configuration", e)
        }
        
        return@withContext servers
    }

    fun disconnect() {
        activeSessionId = null
        socket?.off()
        socket?.disconnect()
        socket = null
    }
    
    fun resetEvent() {
        // Can't directly reset SharedFlow, this is usually handled by observers tracking state
    }
}

sealed class SignalingEvent {
    object Connected : SignalingEvent()
    data class ConnectionError(val message: String) : SignalingEvent()
    data class JoinRequest(val receiverId: String) : SignalingEvent()
    data class JoinError(val message: String) : SignalingEvent()
    object PeerJoined : SignalingEvent()
    object JoinRejected : SignalingEvent()
    object PeerDisconnected : SignalingEvent()
    
    data class OfferReceived(val sdp: String) : SignalingEvent()
    data class AnswerReceived(val sdp: String) : SignalingEvent()
    data class IceCandidateReceived(val sdpMid: String, val sdpMLineIndex: Int, val candidate: String) : SignalingEvent()
}
