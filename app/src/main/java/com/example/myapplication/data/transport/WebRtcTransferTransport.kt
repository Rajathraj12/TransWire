package com.example.myapplication.data.transport

import android.content.Context
import android.util.Log
import com.example.myapplication.data.remote.SignalingClient
import com.example.myapplication.data.remote.SignalingEvent
import com.example.myapplication.domain.model.TransferMessage
import com.example.myapplication.domain.transport.TransferTransport
import com.example.myapplication.util.MessageSerializer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

class WebRtcTransferTransport(
    private val context: Context
) : TransferTransport {

    private val tag = "WebRTC"
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var signalingJob: Job? = null

    private val messageChannel = Channel<TransferMessage>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeMutex = Mutex()
    private val iceLock = Any()
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()

    @Volatile
    private var remoteDescriptionSet = false

    @Volatile
    private var closedReason: Throwable? = null

    companion object {
        private const val CONNECT_TIMEOUT_MS = 45_000L
        private const val READ_TIMEOUT_MS = 30_000L
    }

    private fun initFactory() {
        if (peerConnectionFactory == null) {
            val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initializationOptions)

            val options = PeerConnectionFactory.Options()
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory()
        }
    }

    private fun failChannel(cause: Throwable) {
        if (closedReason == null) {
            closedReason = cause
        }
        messageChannel.close(cause)
    }

    private fun handleDataChannelMessage(buffer: DataChannel.Buffer) {
        if (!buffer.binary) return
        val bytes = ByteArray(buffer.data.remaining())
        buffer.data.get(bytes)
        try {
            messageChannel.trySend(MessageSerializer.deserialize(bytes))
        } catch (e: Exception) {
            Log.w(tag, "Rejected invalid transfer frame: ${e.message}")
            failChannel(IOException("Protocol validation failed: ${e.message}", e))
        }
    }

    private suspend fun awaitCreateOffer(pc: PeerConnection): SessionDescription {
        val deferred = CompletableDeferred<SessionDescription>()
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) deferred.complete(desc)
                else deferred.completeExceptionally(IOException("Empty SDP offer"))
            }

            override fun onCreateFailure(error: String?) {
                deferred.completeExceptionally(IOException("Offer failed: $error"))
            }
        }, MediaConstraints())
        return withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() }
    }

    private suspend fun awaitCreateAnswer(pc: PeerConnection): SessionDescription {
        val deferred = CompletableDeferred<SessionDescription>()
        pc.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc != null) deferred.complete(desc)
                else deferred.completeExceptionally(IOException("Empty SDP answer"))
            }

            override fun onCreateFailure(error: String?) {
                deferred.completeExceptionally(IOException("Answer failed: $error"))
            }
        }, MediaConstraints())
        return withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() }
    }

    private suspend fun awaitSetLocalDescription(pc: PeerConnection, desc: SessionDescription) {
        val deferred = CompletableDeferred<Unit>()
        pc.setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                deferred.complete(Unit)
            }

            override fun onSetFailure(error: String?) {
                deferred.completeExceptionally(IOException("Failed setting local SDP: $error"))
            }
        }, desc)
        withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() }
    }

    private suspend fun awaitSetRemoteDescription(pc: PeerConnection, desc: SessionDescription) {
        val deferred = CompletableDeferred<Unit>()
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                deferred.complete(Unit)
            }

            override fun onSetFailure(error: String?) {
                deferred.completeExceptionally(IOException("Failed setting remote SDP: $error"))
            }
        }, desc)
        withTimeout(CONNECT_TIMEOUT_MS) { deferred.await() }

        val toAdd: List<IceCandidate>
        synchronized(iceLock) {
            remoteDescriptionSet = true
            toAdd = pendingRemoteIceCandidates.toList()
            pendingRemoteIceCandidates.clear()
        }
        toAdd.forEach { pc.addIceCandidate(it) }
    }

    private fun handleRemoteIceCandidate(candidate: IceCandidate) {
        val pc = peerConnection ?: return
        val shouldAddNow: Boolean
        synchronized(iceLock) {
            if (remoteDescriptionSet) {
                shouldAddNow = true
            } else {
                pendingRemoteIceCandidates.add(candidate)
                shouldAddNow = false
            }
        }
        if (shouldAddNow) {
            pc.addIceCandidate(candidate)
        }
    }

    private fun hasTurnServer(iceServers: List<PeerConnection.IceServer>): Boolean =
        iceServers.any { server ->
            server.urls.any { it.startsWith("turn:", ignoreCase = true) || it.startsWith("turns:", ignoreCase = true) }
        }

    private fun buildConnectionFailureMessage(hasTurn: Boolean, detail: String): String {
        return if (!hasTurn) {
            "Could not connect peer-to-peer across these networks (no TURN relay configured on server). Configure TURN on your backend or use Nearby Wi-Fi Direct."
        } else {
            detail
        }
    }

    override suspend fun initAsServer(port: Int) {
        initFactory()
        closedReason = null
        remoteDescriptionSet = false
        synchronized(iceLock) { pendingRemoteIceCandidates.clear() }

        val iceServers = SignalingClient.getTurnCredentials()
        val hasTurn = hasTurnServer(iceServers)
        val dcDeferred = CompletableDeferred<Unit>()

        val pc = peerConnectionFactory?.createPeerConnection(
            iceServers,
            object : SimplePeerConnectionObserver() {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        SignalingClient.sendIceCandidate(it.sdpMid, it.sdpMLineIndex, it.sdp)
                    }
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    Log.d(tag, "ICE Connection State Server: $newState")
                    if (newState == PeerConnection.IceConnectionState.FAILED ||
                        newState == PeerConnection.IceConnectionState.CLOSED
                    ) {
                        val err = IOException(buildConnectionFailureMessage(hasTurn, "WebRTC ICE connection $newState"))
                        dcDeferred.completeExceptionally(err)
                        failChannel(err)
                    }
                }
            }
        ) ?: throw IOException("Failed to create PeerConnection")
        peerConnection = pc

        val dcInit = DataChannel.Init().apply { ordered = true }
        dataChannel = pc.createDataChannel("fileTransfer", dcInit)
        dataChannel?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}
            override fun onStateChange() {
                when (dataChannel?.state()) {
                    DataChannel.State.OPEN -> dcDeferred.complete(Unit)
                    DataChannel.State.CLOSED -> {
                        val err = IOException("WebRTC DataChannel closed")
                        dcDeferred.completeExceptionally(err)
                        failChannel(err)
                    }
                    else -> {}
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                handleDataChannelMessage(buffer)
            }
        })

        // Start signaling listener BEFORE sending offer so early answer/ICE signals are never missed
        suspend fun processServerSignal(event: SignalingEvent) {
            when (event) {
                is SignalingEvent.AnswerReceived -> {
                    val answer = SessionDescription(SessionDescription.Type.ANSWER, event.sdp)
                    awaitSetRemoteDescription(pc, answer)
                }
                is SignalingEvent.IceCandidateReceived -> {
                    val ice = IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
                    handleRemoteIceCandidate(ice)
                }
                is SignalingEvent.PeerDisconnected -> {
                    val err = IOException("Receiver disconnected from signaling session")
                    dcDeferred.completeExceptionally(err)
                    failChannel(err)
                }
                else -> {}
            }
        }

        val buffered = SignalingClient.drainBufferedWebRtcSignals()
        signalingJob = scope.launch {
            for (event in buffered) {
                processServerSignal(event)
            }
            SignalingClient.events.collect { event ->
                processServerSignal(event)
            }
        }

        val offerDesc = awaitCreateOffer(pc)
        awaitSetLocalDescription(pc, offerDesc)
        SignalingClient.sendOffer(offerDesc.description)

        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                dcDeferred.await()
            }
        } catch (_: TimeoutCancellationException) {
            throw SocketTimeoutException(
                buildConnectionFailureMessage(hasTurn, "Timed out establishing WebRTC data channel")
            )
        }
    }

    override suspend fun initAsClient(host: String, port: Int) {
        initFactory()
        closedReason = null
        remoteDescriptionSet = false
        synchronized(iceLock) { pendingRemoteIceCandidates.clear() }

        val iceServers = SignalingClient.getTurnCredentials()
        val hasTurn = hasTurnServer(iceServers)
        val dcDeferred = CompletableDeferred<Unit>()

        val pc = peerConnectionFactory?.createPeerConnection(
            iceServers,
            object : SimplePeerConnectionObserver() {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let {
                        SignalingClient.sendIceCandidate(it.sdpMid, it.sdpMLineIndex, it.sdp)
                    }
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    Log.d(tag, "ICE Connection State Client: $newState")
                    if (newState == PeerConnection.IceConnectionState.FAILED ||
                        newState == PeerConnection.IceConnectionState.CLOSED
                    ) {
                        val err = IOException(buildConnectionFailureMessage(hasTurn, "WebRTC ICE connection $newState"))
                        dcDeferred.completeExceptionally(err)
                        failChannel(err)
                    }
                }

                override fun onDataChannel(dc: DataChannel?) {
                    dc?.let {
                        dataChannel = it
                        it.registerObserver(object : DataChannel.Observer {
                            override fun onBufferedAmountChange(p0: Long) {}
                            override fun onStateChange() {
                                when (it.state()) {
                                    DataChannel.State.OPEN -> dcDeferred.complete(Unit)
                                    DataChannel.State.CLOSED -> {
                                        val err = IOException("WebRTC DataChannel closed")
                                        dcDeferred.completeExceptionally(err)
                                        failChannel(err)
                                    }
                                    else -> {}
                                }
                            }

                            override fun onMessage(buffer: DataChannel.Buffer) {
                                handleDataChannelMessage(buffer)
                            }
                        })
                    }
                }
            }
        ) ?: throw IOException("Failed to create PeerConnection")
        peerConnection = pc

        suspend fun processClientSignal(event: SignalingEvent) {
            when (event) {
                is SignalingEvent.OfferReceived -> {
                    val offer = SessionDescription(SessionDescription.Type.OFFER, event.sdp)
                    awaitSetRemoteDescription(pc, offer)
                    val answerDesc = awaitCreateAnswer(pc)
                    awaitSetLocalDescription(pc, answerDesc)
                    SignalingClient.sendAnswer(answerDesc.description)
                }
                is SignalingEvent.IceCandidateReceived -> {
                    val ice = IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
                    handleRemoteIceCandidate(ice)
                }
                is SignalingEvent.PeerDisconnected -> {
                    val err = IOException("Sender disconnected from signaling session")
                    dcDeferred.completeExceptionally(err)
                    failChannel(err)
                }
                else -> {}
            }
        }

        // Process buffered offer & early ICE candidates emitted while user tapped Start Receiving
        val buffered = SignalingClient.drainBufferedWebRtcSignals()
        signalingJob = scope.launch {
            for (event in buffered) {
                processClientSignal(event)
            }
            SignalingClient.events.collect { event ->
                processClientSignal(event)
            }
        }

        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                dcDeferred.await()
            }
        } catch (_: TimeoutCancellationException) {
            throw SocketTimeoutException(
                buildConnectionFailureMessage(hasTurn, "Timed out establishing WebRTC data channel")
            )
        }
    }

    override suspend fun writeMessage(message: TransferMessage) = writeMutex.withLock {
        val dc = dataChannel ?: throw IOException("DataChannel not open")
        if (dc.state() != DataChannel.State.OPEN) {
            throw IOException("DataChannel is ${dc.state()}")
        }

        val waitStart = System.currentTimeMillis()
        while (dc.bufferedAmount() > 1024 * 1024) { // 1MB Backpressure limit
            if (dc.state() != DataChannel.State.OPEN) {
                throw IOException("DataChannel closed while buffering")
            }
            if (System.currentTimeMillis() - waitStart > READ_TIMEOUT_MS) {
                throw SocketTimeoutException("Timed out draining WebRTC send buffer")
            }
            delay(10)
        }

        val bytes = MessageSerializer.serialize(message)
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.flip()

        val dcBuffer = DataChannel.Buffer(buffer, true)
        if (!dc.send(dcBuffer)) {
            throw IOException("DataChannel send failed")
        }
    }

    override suspend fun readMessage(): TransferMessage {
        closedReason?.let { throw it }
        return try {
            withTimeout(READ_TIMEOUT_MS) {
                messageChannel.receive()
            }
        } catch (_: TimeoutCancellationException) {
            throw SocketTimeoutException("Timed out waiting for peer message")
        } catch (e: ClosedReceiveChannelException) {
            throw (closedReason ?: IOException("Connection closed by peer", e))
        }
    }

    override suspend fun flush() {
        val dc = dataChannel ?: return
        val deadline = System.currentTimeMillis() + 5_000L
        while (dc.state() == DataChannel.State.OPEN && dc.bufferedAmount() > 0L && System.currentTimeMillis() < deadline) {
            delay(25)
        }
        delay(50)
    }

    override fun disconnect() {
        failChannel(IOException("Transfer disconnected"))
        signalingJob?.cancel()
        scope.cancel()
        try { dataChannel?.close() } catch (_: Exception) {}
        try { peerConnection?.close() } catch (_: Exception) {}
        dataChannel = null
        peerConnection = null
    }
}

open class SimplePeerConnectionObserver : PeerConnection.Observer {
    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(p0: Boolean) {}
    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(p0: IceCandidate?) {}
    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
    override fun onAddStream(p0: MediaStream?) {}
    override fun onRemoveStream(p0: MediaStream?) {}
    override fun onDataChannel(p0: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
}

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}
