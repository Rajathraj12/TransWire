package com.example.myapplication.data.remote

import android.util.Log
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.net.HttpURLConnection
import java.net.URISyntaxException
import java.net.URL

object SignalingClient {
    private var socket: Socket? = null

    const val DEFAULT_SERVER_URL = "https://transwire-lwbl.onrender.com"
    const val LOCAL_SERVER_URL = "http://172.21.2.57:3000"
    const val EMULATOR_SERVER_URL = "http://10.0.2.2:3000"

    // Defaults to the hosted Render signaling server; overridable in Settings for local testing
    @Volatile
    var serverUrl = DEFAULT_SERVER_URL

    var activeSessionId: String? = null

    @Volatile
    private var activePairingToken: String? = null

    @Volatile
    private var activeIsSender: Boolean = false

    @Volatile
    private var pendingCreatePayload: JSONObject? = null

    @Volatile
    private var pendingJoinPayload: JSONObject? = null

    private val bufferedWebRtcSignals = mutableListOf<SignalingEvent>()
    private var webRtcCollectorToken: Long = 0L
    private var nextCollectorToken: Long = 1L

    // replay = 0 prevents old PeerJoined / JoinError events from replaying into fresh screens
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<SignalingEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events = _events.asSharedFlow()

    fun activateWebRtcCollector(): Pair<Long, List<SignalingEvent>> = synchronized(bufferedWebRtcSignals) {
        val token = nextCollectorToken++
        webRtcCollectorToken = token
        val copy = bufferedWebRtcSignals.toList()
        bufferedWebRtcSignals.clear()
        token to copy
    }

    fun deactivateWebRtcCollector(token: Long) = synchronized(bufferedWebRtcSignals) {
        if (webRtcCollectorToken == token) {
            webRtcCollectorToken = 0L
        }
    }

    fun clearWebRtcSignals() = synchronized(bufferedWebRtcSignals) {
        bufferedWebRtcSignals.clear()
    }

    private fun recordAndEmitWebRtcSignal(event: SignalingEvent) {
        synchronized(bufferedWebRtcSignals) {
            if (webRtcCollectorToken == 0L) {
                if (event is SignalingEvent.OfferReceived) {
                    // Any older signals belong to a previous transfer attempt and must not leak into Retry
                    bufferedWebRtcSignals.clear()
                }
                if (bufferedWebRtcSignals.size >= 120) {
                    bufferedWebRtcSignals.removeAt(0)
                }
                bufferedWebRtcSignals.add(event)
            }
        }
        _events.tryEmit(event)
    }

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
            val startedAt = System.currentTimeMillis()
            val url = URL("$base/health")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 30_000
            }
            val code = conn.responseCode
            val latencyMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
            if (code == 200) {
                Result.success("Server reachable (HTTP 200 • ${latencyMs} ms)")
            } else {
                Result.failure(Exception("Server responded with HTTP $code. Wait a moment if it is waking up."))
            }
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception("Host could not be resolved. Check your internet connection or endpoint URL."))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(Exception("Timed out connecting to server. Free Render instances may take up to 30s to wake up."))
        } catch (e: java.net.ConnectException) {
            Result.failure(Exception("Connection refused by server. Verify the server is running and port is open."))
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Unable to reach server"))
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun probeTurnSummary(targetUrl: String = serverUrl): Result<String> = withContext(Dispatchers.IO) {
        val base = targetUrl.trim().trimEnd('/')
        if (!base.startsWith("http://", ignoreCase = true) &&
            !base.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext Result.failure(IllegalArgumentException("Invalid server URL"))
        }
        var conn: HttpURLConnection? = null
        try {
            val url = URL("$base/turn-credentials")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            val code = conn.responseCode
            if (code != 200) {
                return@withContext Result.failure(Exception("TURN endpoint returned HTTP $code"))
            }
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val uris = json.optJSONArray("uris")
            val count = uris?.length() ?: 0
            if (count == 0) {
                return@withContext Result.success("No TURN relays configured (STUN only)")
            }
            val schemes = (0 until count)
                .mapNotNull { uris?.optString(it)?.substringBefore(':')?.lowercase()?.takeIf { s -> s.isNotEmpty() } }
                .distinct()
                .joinToString("/")
            Result.success("$count relay endpoint(s) active ($schemes)")
        } catch (e: Exception) {
            Result.failure(Exception(e.message ?: "Unable to inspect TURN endpoint"))
        } finally {
            conn?.disconnect()
        }
    }

    fun connect() {
        if (socket?.connected() == true) return
        try {
            socket?.off()
            socket?.disconnect()
            val options = IO.Options().apply {
                timeout = 30_000L
                reconnection = true
                reconnectionAttempts = 5
                reconnectionDelay = 1_500L
            }
            socket = IO.socket(serverUrl, options)
            
            socket?.on(Socket.EVENT_CONNECT) {
                Log.d("SignalingClient", "Connected to signaling server")
                val pendingCreate = pendingCreatePayload
                val pendingJoin = pendingJoinPayload
                val sid = activeSessionId
                val token = activePairingToken
                when {
                    pendingCreate != null -> emitCreateSession(pendingCreate)
                    pendingJoin != null -> {
                        pendingJoinPayload = null
                        emitJoinSession(pendingJoin)
                    }
                    sid != null && token != null -> emitRejoinSession(sid, token, activeIsSender)
                }
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
            
            socket?.on("peer-joined") { args ->
                pendingCreatePayload = null
                pendingJoinPayload = null
                val data = args.firstOrNull() as? JSONObject
                val token = data?.optString("pairingToken")?.takeIf { it.isNotEmpty() }
                if (token != null) {
                    activePairingToken = token
                }
                _events.tryEmit(SignalingEvent.PeerJoined)
            }
            
            socket?.on("join-rejected") {
                _events.tryEmit(SignalingEvent.JoinRejected)
            }
            
            socket?.on("peer-disconnected") {
                _events.tryEmit(SignalingEvent.PeerDisconnected)
            }

            socket?.on("retry-transfer") {
                _events.tryEmit(SignalingEvent.RetryTransfer)
            }

            socket?.on("offer") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val sdp = data.optString("sdp")
                if (sdp.isNotEmpty()) {
                    recordAndEmitWebRtcSignal(SignalingEvent.OfferReceived(sdp))
                }
            }
            
            socket?.on("answer") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val sdp = data.optString("sdp")
                if (sdp.isNotEmpty()) {
                    recordAndEmitWebRtcSignal(SignalingEvent.AnswerReceived(sdp))
                }
            }
            
            socket?.on("ice-candidate") { args ->
                val data = args.firstOrNull() as? JSONObject ?: return@on
                val candidateObj = data.optJSONObject("candidate") ?: return@on
                val candidateStr = candidateObj.optString("candidate")
                if (candidateStr.isNotEmpty()) {
                    recordAndEmitWebRtcSignal(
                        SignalingEvent.IceCandidateReceived(
                            candidateObj.optString("sdpMid", ""),
                            candidateObj.optInt("sdpMLineIndex", 0),
                            candidateStr
                        )
                    )
                }
            }
            
            socket?.connect()
        } catch (e: URISyntaxException) {
            Log.e("SignalingClient", "Invalid signaling URL", e)
        }
    }

    fun createSession(sessionId: String, token: String, pin: String) {
        clearWebRtcSignals()
        activeSessionId = sessionId
        activePairingToken = token
        activeIsSender = true
        val data = JSONObject().apply {
            put("sessionId", sessionId)
            put("pairingToken", token)
            put("pin", pin)
        }
        pendingCreatePayload = data
        if (socket?.connected() == true) {
            emitCreateSession(data)
        } else {
            connect()
        }
    }

    private fun emitCreateSession(data: JSONObject) {
        val sessionId = data.optString("sessionId")
        socket?.emit("create-session", data, io.socket.client.Ack { args ->
            val response = args.firstOrNull() as? JSONObject ?: return@Ack
            if (response.optBoolean("success", false)) {
                _events.tryEmit(SignalingEvent.SessionCreated(sessionId))
            } else {
                pendingCreatePayload = null
                _events.tryEmit(SignalingEvent.JoinError(response.optString("error", "Failed to create session")))
            }
        })
    }

    fun joinSessionWithPin(pin: String) {
        clearWebRtcSignals()
        activePairingToken = null
        activeIsSender = false
        val data = JSONObject().apply {
            put("pin", pin)
        }
        if (socket?.connected() == true) {
            emitJoinSession(data)
        } else {
            pendingJoinPayload = data
            connect()
        }
    }

    fun joinSessionWithQr(sessionId: String, token: String) {
        clearWebRtcSignals()
        activePairingToken = token
        activeIsSender = false
        val data = JSONObject().apply {
            put("sessionId", sessionId)
            put("pairingToken", token)
        }
        if (socket?.connected() == true) {
            emitJoinSession(data)
        } else {
            pendingJoinPayload = data
            connect()
        }
    }

    private fun emitJoinSession(data: JSONObject) {
        socket?.emit("join-session", data, io.socket.client.Ack { args ->
            val response = args.firstOrNull() as? JSONObject ?: return@Ack
            if (response.optBoolean("success")) {
                activeSessionId = response.optString("sessionId").takeIf { it.isNotEmpty() }
            } else {
                _events.tryEmit(SignalingEvent.JoinError(response.optString("error", "Invalid or expired QR session")))
            }
        })
    }

    private fun emitRejoinSession(
        sessionId: String,
        token: String,
        isSender: Boolean,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        val data = JSONObject().apply {
            put("sessionId", sessionId)
            put("pairingToken", token)
            put("isSender", isSender)
        }
        socket?.emit("rejoin-session", data, io.socket.client.Ack { args ->
            val response = args.firstOrNull() as? JSONObject
            val ok = response?.optBoolean("success", false) ?: false
            if (!ok) {
                Log.w("SignalingClient", "Rejoin session failed: ${response?.optString("error")}")
            }
            onDone?.invoke(ok)
        })
    }

    suspend fun ensureConnected(timeoutMs: Long = 5_000L): Boolean {
        val sid = activeSessionId ?: return false
        if (socket?.connected() == true) return true
        val token = activePairingToken
        val connected = CompletableDeferred<Boolean>()
        connect()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (socket?.connected() == true) {
                if (token != null) {
                    val rejoinResult = CompletableDeferred<Boolean>()
                    emitRejoinSession(sid, token, activeIsSender) { ok -> rejoinResult.complete(ok) }
                    withTimeoutOrNull(2_000L) { rejoinResult.await() }
                }
                connected.complete(true)
                break
            }
            kotlinx.coroutines.delay(50)
        }
        return if (connected.isCompleted) connected.await() else (socket?.connected() == true)
    }

    fun sendRetryTransfer() {
        val sid = activeSessionId ?: return
        val data = JSONObject().apply {
            put("sessionId", sid)
        }
        socket?.emit("retry-transfer", data)
    }

    fun leaveSession(sessionId: String? = activeSessionId) {
        val sid = sessionId ?: return
        if (pendingCreatePayload?.optString("sessionId") == sid) {
            pendingCreatePayload = null
        }
        pendingJoinPayload = null
        clearWebRtcSignals()
        val data = JSONObject().apply {
            put("sessionId", sid)
        }
        socket?.emit("leave-session", data)
        if (activeSessionId == sid) {
            activeSessionId = null
            activePairingToken = null
            activeIsSender = false
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
        // Public STUN servers for direct P2P resolution across home/cone NATs
        servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        servers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer())
        servers.add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
        
        try {
            val querySuffix = activeSessionId?.let { "?sessionId=${java.net.URLEncoder.encode(it, "UTF-8")}" } ?: ""
            val url = URL("$serverUrl/turn-credentials$querySuffix")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
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
        activePairingToken = null
        activeIsSender = false
        pendingCreatePayload = null
        pendingJoinPayload = null
        synchronized(bufferedWebRtcSignals) {
            webRtcCollectorToken = 0L
            bufferedWebRtcSignals.clear()
        }
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
    data class SessionCreated(val sessionId: String) : SignalingEvent()
    data class ConnectionError(val message: String) : SignalingEvent()
    data class JoinRequest(val receiverId: String) : SignalingEvent()
    data class JoinError(val message: String) : SignalingEvent()
    object PeerJoined : SignalingEvent()
    object JoinRejected : SignalingEvent()
    object PeerDisconnected : SignalingEvent()
    object RetryTransfer : SignalingEvent()
    
    data class OfferReceived(val sdp: String) : SignalingEvent()
    data class AnswerReceived(val sdp: String) : SignalingEvent()
    data class IceCandidateReceived(val sdpMid: String, val sdpMLineIndex: Int, val candidate: String) : SignalingEvent()
}
