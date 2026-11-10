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
    @Volatile
    private var closedReason: Throwable? = null

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000L
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

    override suspend fun initAsServer(port: Int) {
        initFactory()
        closedReason = null

        val iceServers = SignalingClient.getTurnCredentials()
        val dcDeferred = CompletableDeferred<Unit>()

        peerConnection = peerConnectionFactory?.createPeerConnection(
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
                        val err = IOException("WebRTC ICE connection $newState")
                        dcDeferred.completeExceptionally(err)
                        failChannel(err)
                    }
                }
            }
        ) ?: throw IOException("Failed to create PeerConnection")

        val dcInit = DataChannel.Init().apply {
            ordered = true
        }
        dataChannel = peerConnection?.createDataChannel("fileTransfer", dcInit)
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

        val sdpDeferred = CompletableDeferred<SessionDescription>()
        peerConnection?.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                desc?.let {
                    peerConnection?.setLocalDescription(SimpleSdpObserver(), it)
                    sdpDeferred.complete(it)
                }
            }

            override fun onCreateFailure(s: String?) {
                sdpDeferred.completeExceptionally(IOException("Offer failed: $s"))
            }
        }, MediaConstraints())

        val offerDesc = withTimeout(CONNECT_TIMEOUT_MS) { sdpDeferred.await() }
        SignalingClient.sendOffer(offerDesc.description)

        signalingJob = scope.launch {
            SignalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.AnswerReceived -> {
                        val answer = SessionDescription(SessionDescription.Type.ANSWER, event.sdp)
                        peerConnection?.setRemoteDescription(SimpleSdpObserver(), answer)
                    }
                    is SignalingEvent.IceCandidateReceived -> {
                        val ice = IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
                        peerConnection?.addIceCandidate(ice)
                    }
                    is SignalingEvent.PeerDisconnected -> {
                        val err = IOException("Peer disconnected from signaling session")
                        dcDeferred.completeExceptionally(err)
                        failChannel(err)
                    }
                    else -> {}
                }
            }
        }

        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                dcDeferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw SocketTimeoutException("Timed out establishing WebRTC data channel")
        }
    }

    override suspend fun initAsClient(host: String, port: Int) {
        initFactory()
        closedReason = null

        val iceServers = SignalingClient.getTurnCredentials()
        val dcDeferred = CompletableDeferred<Unit>()

        peerConnection = peerConnectionFactory?.createPeerConnection(
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
                        val err = IOException("WebRTC ICE connection $newState")
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

        signalingJob = scope.launch {
            SignalingClient.events.collect { event ->
                when (event) {
                    is SignalingEvent.OfferReceived -> {
                        val offer = SessionDescription(SessionDescription.Type.OFFER, event.sdp)
                        peerConnection?.setRemoteDescription(SimpleSdpObserver(), offer)

                        val sdpDeferred = CompletableDeferred<SessionDescription>()
                        peerConnection?.createAnswer(object : SimpleSdpObserver() {
                            override fun onCreateSuccess(desc: SessionDescription?) {
                                desc?.let {
                                    peerConnection?.setLocalDescription(SimpleSdpObserver(), it)
                                    sdpDeferred.complete(it)
                                }
                            }

                            override fun onCreateFailure(s: String?) {
                                sdpDeferred.completeExceptionally(IOException("Answer failed: $s"))
                            }
                        }, MediaConstraints())

                        val answerDesc = sdpDeferred.await()
                        SignalingClient.sendAnswer(answerDesc.description)
                    }
                    is SignalingEvent.AnswerReceived -> {
                        val answer = SessionDescription(SessionDescription.Type.ANSWER, event.sdp)
                        peerConnection?.setRemoteDescription(SimpleSdpObserver(), answer)
                    }
                    is SignalingEvent.IceCandidateReceived -> {
                        val ice = IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
                        peerConnection?.addIceCandidate(ice)
                    }
                    is SignalingEvent.PeerDisconnected -> {
                        val err = IOException("Peer disconnected from signaling session")
                        dcDeferred.completeExceptionally(err)
                        failChannel(err)
                    }
                    else -> {}
                }
            }
        }

        try {
            withTimeout(CONNECT_TIMEOUT_MS) {
                dcDeferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            throw SocketTimeoutException("Timed out establishing WebRTC data channel")
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
        } catch (e: TimeoutCancellationException) {
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
