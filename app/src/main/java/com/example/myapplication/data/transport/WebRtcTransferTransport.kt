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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class WebRtcTransferTransport(
    private val context: Context
) : TransferTransport {

    private val tag = "WebRTC"
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private val stripeChannels = CopyOnWriteArrayList<DataChannel>()
    private var signalingJob: Job? = null

    private val rawMessageChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val bufferChangeSignal = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeMutex = Mutex()
    private val iceLock = Any()
    private val pendingRemoteIceCandidates = mutableListOf<IceCandidate>()

    @Volatile
    private var remoteDescriptionSet = false

    @Volatile
    private var closedReason: Throwable? = null

    @Volatile
    private var locallyDisconnected = false

    @Volatile
    private var collectorToken: Long = 0L

    companion object {
        private const val CONNECT_TIMEOUT_MS = 60_000L
        private const val READ_TIMEOUT_MS = 60_000L
        private const val STALL_TIMEOUT_MS = 60_000L
        private const val MAX_BUFFERED_BYTES = 4L * 1024L * 1024L
        private const val CONTROL_CHANNEL_LABEL = "fileTransfer"
        private const val DATA_CHANNEL_PREFIX = "fileTransfer-data-"
        private const val DATA_CHANNEL_STRIPES = 3
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
        rawMessageChannel.close(cause)
        bufferChangeSignal.close(cause)
    }

    private fun handleDataChannelMessage(buffer: DataChannel.Buffer) {
        if (!buffer.binary) return
        val bytes = ByteArray(buffer.data.remaining())
        buffer.data.get(bytes)
        rawMessageChannel.trySend(bytes)
    }

    private fun registerChannelObserver(
        channel: DataChannel,
        isPrimaryControl: Boolean,
        dcDeferred: CompletableDeferred<Unit>?
    ) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {
                bufferChangeSignal.trySend(Unit)
            }

            override fun onStateChange() {
                when (channel.state()) {
                    DataChannel.State.OPEN -> {
                        if (isPrimaryControl) {
                            dcDeferred?.complete(Unit)
                        }
                    }
                    DataChannel.State.CLOSED -> {
                        if (!locallyDisconnected && (isPrimaryControl || dcDeferred?.isCompleted == true)) {
                            val err = IOException("WebRTC DataChannel closed")
                            dcDeferred?.completeExceptionally(err)
                            failChannel(err)
                        }
                    }
                    else -> {}
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                handleDataChannelMessage(buffer)
            }
        })
    }

    private fun buildRtcConfiguration(iceServers: List<PeerConnection.IceServer>): PeerConnection.RTCConfiguration {
        return PeerConnection.RTCConfiguration(iceServers).apply {
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = 2
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
    }

    private fun launchSignalingCollector(
        dcDeferred: CompletableDeferred<Unit>,
        buffered: List<SignalingEvent>,
        handler: suspend (SignalingEvent) -> Unit
    ): Job = scope.launch {
        try {
            for (event in buffered) {
                handler(event)
            }
            SignalingClient.events.collect { event ->
                handler(event)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dcDeferred.completeExceptionally(e)
            failChannel(e)
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
        if (!SignalingClient.ensureConnected()) {
            throw IOException("Signaling session is unavailable. Please pair again.")
        }
        initFactory()
        locallyDisconnected = false
        closedReason = null
        remoteDescriptionSet = false
        stripeChannels.clear()
        synchronized(iceLock) { pendingRemoteIceCandidates.clear() }

        val iceServers = SignalingClient.getTurnCredentials()
        val hasTurn = hasTurnServer(iceServers)
        val dcDeferred = CompletableDeferred<Unit>()

        val pc = peerConnectionFactory?.createPeerConnection(
            buildRtcConfiguration(iceServers),
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

        val controlInit = DataChannel.Init().apply { ordered = true }
        val controlDc = pc.createDataChannel(CONTROL_CHANNEL_LABEL, controlInit)
        dataChannel = controlDc
        registerChannelObserver(controlDc, isPrimaryControl = true, dcDeferred = dcDeferred)

        val dataInit = DataChannel.Init().apply { ordered = false }
        for (i in 0 until DATA_CHANNEL_STRIPES) {
            pc.createDataChannel("$DATA_CHANNEL_PREFIX$i", dataInit)?.let { stripe ->
                stripeChannels.add(stripe)
                registerChannelObserver(stripe, isPrimaryControl = false, dcDeferred = dcDeferred)
            }
        }

        val acceptingResponses = AtomicBoolean(false)
        val (token, _) = SignalingClient.activateWebRtcCollector()
        collectorToken = token

        signalingJob = launchSignalingCollector(dcDeferred, emptyList()) { event ->
            when (event) {
                is SignalingEvent.AnswerReceived -> {
                    if (acceptingResponses.get() && !remoteDescriptionSet) {
                        val answer = SessionDescription(SessionDescription.Type.ANSWER, event.sdp)
                        awaitSetRemoteDescription(pc, answer)
                    }
                }
                is SignalingEvent.IceCandidateReceived -> {
                    if (acceptingResponses.get()) {
                        val ice = IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
                        handleRemoteIceCandidate(ice)
                    }
                }
                is SignalingEvent.PeerDisconnected -> {
                    if (!dcDeferred.isCompleted) {
                        val err = IOException("Receiver disconnected from signaling session")
                        dcDeferred.completeExceptionally(err)
                        failChannel(err)
                    }
                }
                else -> {}
            }
        }

        val offerDesc = awaitCreateOffer(pc)
        awaitSetLocalDescription(pc, offerDesc)
        acceptingResponses.set(true)
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
        if (!SignalingClient.ensureConnected()) {
            throw IOException("Signaling session is unavailable. Please pair again.")
        }
        initFactory()
        locallyDisconnected = false
        closedReason = null
        remoteDescriptionSet = false
        stripeChannels.clear()
        synchronized(iceLock) { pendingRemoteIceCandidates.clear() }

        val iceServers = SignalingClient.getTurnCredentials()
        val hasTurn = hasTurnServer(iceServers)
        val dcDeferred = CompletableDeferred<Unit>()

        val pc = peerConnectionFactory?.createPeerConnection(
            buildRtcConfiguration(iceServers),
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
                    dc?.let { channel ->
                        val isControl = channel.label() == CONTROL_CHANNEL_LABEL || dataChannel == null && !channel.label().startsWith(DATA_CHANNEL_PREFIX)
                        if (isControl) {
                            dataChannel = channel
                        } else {
                            stripeChannels.add(channel)
                        }
                        registerChannelObserver(channel, isPrimaryControl = isControl, dcDeferred = dcDeferred)
                    }
                }
            }
        ) ?: throw IOException("Failed to create PeerConnection")
        peerConnection = pc

        val (token, buffered) = SignalingClient.activateWebRtcCollector()
        collectorToken = token

        signalingJob = launchSignalingCollector(dcDeferred, buffered) { event ->
            when (event) {
                is SignalingEvent.OfferReceived -> {
                    if (!remoteDescriptionSet) {
                        val offer = SessionDescription(SessionDescription.Type.OFFER, event.sdp)
                        awaitSetRemoteDescription(pc, offer)
                        val answerDesc = awaitCreateAnswer(pc)
                        awaitSetLocalDescription(pc, answerDesc)
                        SignalingClient.sendAnswer(answerDesc.description)
                    }
                }
                is SignalingEvent.IceCandidateReceived -> {
                    val ice = IceCandidate(event.sdpMid, event.sdpMLineIndex, event.candidate)
                    handleRemoteIceCandidate(ice)
                }
                is SignalingEvent.PeerDisconnected -> {
                    if (!dcDeferred.isCompleted) {
                        val err = IOException("Sender disconnected from signaling session")
                        dcDeferred.completeExceptionally(err)
                        failChannel(err)
                    }
                }
                else -> {}
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

    private fun selectTargetChannel(message: TransferMessage): DataChannel? {
        val control = dataChannel?.takeIf { it.state() == DataChannel.State.OPEN }
        if (message !is TransferMessage.Chunk) {
            return control
        }
        val openStripe = stripeChannels
            .asSequence()
            .filter { it.state() == DataChannel.State.OPEN }
            .minByOrNull { it.bufferedAmount() }
        return openStripe ?: control
    }

    override suspend fun writeMessage(message: TransferMessage) = writeMutex.withLock {
        var targetDc = selectTargetChannel(message)
            ?: throw IOException("DataChannel not open")

        var currentBuffered = targetDc.bufferedAmount()
        if (currentBuffered > MAX_BUFFERED_BYTES) {
            var minBuffered = currentBuffered
            var lastProgressAt = System.currentTimeMillis()
            while (currentBuffered > MAX_BUFFERED_BYTES) {
                if (locallyDisconnected || closedReason != null) {
                    throw (closedReason ?: IOException("DataChannel closed while buffering"))
                }
                if (targetDc.state() != DataChannel.State.OPEN) {
                    throw IOException("DataChannel closed while buffering")
                }
                withTimeoutOrNull(100L) {
                    bufferChangeSignal.receiveCatching()
                }
                val candidate = selectTargetChannel(message) ?: targetDc
                val nextBuffered = candidate.bufferedAmount()
                if (candidate !== targetDc && nextBuffered <= MAX_BUFFERED_BYTES) {
                    targetDc = candidate
                    currentBuffered = nextBuffered
                    break
                }
                currentBuffered = targetDc.bufferedAmount()
                if (currentBuffered < minBuffered) {
                    minBuffered = currentBuffered
                    lastProgressAt = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - lastProgressAt > STALL_TIMEOUT_MS) {
                    throw SocketTimeoutException("Timed out draining WebRTC send buffer")
                }
            }
        }

        val bytes = MessageSerializer.serialize(message)
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.flip()

        val dcBuffer = DataChannel.Buffer(buffer, true)
        if (!targetDc.send(dcBuffer)) {
            throw IOException("DataChannel send failed")
        }
    }

    override suspend fun readMessage(): TransferMessage {
        if (locallyDisconnected) {
            throw (closedReason ?: IOException("Transfer disconnected"))
        }
        val buffered = rawMessageChannel.tryReceive().getOrNull()
        val bytes = if (buffered != null) {
            buffered
        } else {
            closedReason?.let { throw it }
            try {
                withTimeout(READ_TIMEOUT_MS) {
                    rawMessageChannel.receive()
                }
            } catch (_: TimeoutCancellationException) {
                throw SocketTimeoutException("Timed out waiting for peer message")
            } catch (e: ClosedReceiveChannelException) {
                throw (closedReason ?: IOException("Connection closed by peer", e))
            }
        }
        return try {
            MessageSerializer.deserialize(bytes)
        } catch (e: Exception) {
            Log.w(tag, "Rejected invalid transfer frame: ${e.message}")
            val err = IOException("Protocol validation failed: ${e.message}", e)
            failChannel(err)
            throw err
        }
    }

    private fun totalOpenBufferedAmount(): Long {
        var total = 0L
        dataChannel?.let { if (it.state() == DataChannel.State.OPEN) total += it.bufferedAmount() }
        for (stripe in stripeChannels) {
            if (stripe.state() == DataChannel.State.OPEN) total += stripe.bufferedAmount()
        }
        return total
    }

    override suspend fun flush() {
        val controlOpen = dataChannel?.state() == DataChannel.State.OPEN
        val anyOpen = controlOpen || stripeChannels.any { it.state() == DataChannel.State.OPEN }
        if (!anyOpen) return

        val deadline = System.currentTimeMillis() + 20_000L
        while (
            !locallyDisconnected &&
            closedReason == null &&
            totalOpenBufferedAmount() > 0L &&
            System.currentTimeMillis() < deadline
        ) {
            withTimeoutOrNull(50L) {
                bufferChangeSignal.receiveCatching()
            }
        }
        if (!locallyDisconnected && closedReason == null) {
            delay(25)
        }
    }

    override suspend fun awaitRemoteClose(timeoutMs: Long) {
        if (locallyDisconnected || closedReason != null) return
        withTimeoutOrNull(timeoutMs) {
            try {
                while (!locallyDisconnected && closedReason == null) {
                    rawMessageChannel.receive()
                }
            } catch (_: Exception) {
                // Expected once peer disconnects after receiving CompletionAck
            }
        }
    }

    override fun disconnect() {
        locallyDisconnected = true
        SignalingClient.deactivateWebRtcCollector(collectorToken)
        failChannel(IOException("Transfer disconnected"))
        signalingJob?.cancel()
        scope.cancel()
        for (stripe in stripeChannels) {
            try { stripe.close() } catch (_: Exception) {}
        }
        stripeChannels.clear()
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
