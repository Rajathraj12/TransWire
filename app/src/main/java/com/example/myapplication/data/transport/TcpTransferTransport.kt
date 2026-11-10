package com.example.myapplication.data.transport

import com.example.myapplication.domain.model.TransferMessage
import com.example.myapplication.domain.transport.TransferTransport
import com.example.myapplication.util.MessageSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class TcpTransferTransport : TransferTransport {
    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var dataIn: DataInputStream? = null
    private var dataOut: DataOutputStream? = null
    private val writeMutex = Mutex()
    @Volatile
    private var disconnected = false

    companion object {
        private const val ACCEPT_TIMEOUT_MS = 45_000
        private const val CONNECT_ATTEMPT_TIMEOUT_MS = 3_000
        private const val SOCKET_READ_TIMEOUT_MS = 30_000
        private const val MAX_CONNECT_ATTEMPTS = 20
    }

    override suspend fun initAsServer(port: Int) = withContext(Dispatchers.IO) {
        disconnected = false
        val srv = ServerSocket(port).apply {
            reuseAddress = true
            soTimeout = ACCEPT_TIMEOUT_MS
        }
        serverSocket = srv
        try {
            val accepted = srv.accept()
            socket = accepted
            setupStreams(accepted)
        } catch (e: SocketTimeoutException) {
            throw SocketTimeoutException("Timed out waiting for peer to connect over TCP")
        }
    }

    override suspend fun initAsClient(host: String, port: Int) = withContext(Dispatchers.IO) {
        disconnected = false
        var lastError: Exception? = null
        var connectedSocket: Socket? = null
        var attempts = 0
        while (connectedSocket == null && attempts < MAX_CONNECT_ATTEMPTS && !disconnected) {
            coroutineContext.ensureActive()
            val candidate = Socket()
            try {
                candidate.connect(InetSocketAddress(host, port), CONNECT_ATTEMPT_TIMEOUT_MS)
                connectedSocket = candidate
            } catch (e: CancellationException) {
                try { candidate.close() } catch (_: Exception) {}
                throw e
            } catch (e: Exception) {
                try { candidate.close() } catch (_: Exception) {}
                lastError = e
                attempts++
                if (attempts < MAX_CONNECT_ATTEMPTS && !disconnected) {
                    delay(1_000)
                }
            }
        }
        val activeSocket = connectedSocket
            ?: throw IOException(
                "Failed to connect to TCP server after $attempts attempts: ${lastError?.message ?: "unreachable"}"
            )
        socket = activeSocket
        setupStreams(activeSocket)
    }

    private fun setupStreams(activeSocket: Socket) {
        activeSocket.soTimeout = SOCKET_READ_TIMEOUT_MS
        activeSocket.tcpNoDelay = true
        activeSocket.receiveBufferSize = 256 * 1024
        activeSocket.sendBufferSize = 256 * 1024
        dataIn = DataInputStream(activeSocket.getInputStream())
        dataOut = DataOutputStream(activeSocket.getOutputStream())
    }

    override suspend fun writeMessage(message: TransferMessage) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            val out = dataOut ?: throw IOException("Not connected")
            MessageSerializer.writeMessage(out, message)
            out.flush()
        }
    }

    override suspend fun readMessage(): TransferMessage = withContext(Dispatchers.IO) {
        val input = dataIn ?: throw IOException("Not connected")
        try {
            MessageSerializer.readMessage(input)
        } catch (e: EOFException) {
            throw IOException("Connection closed by peer", e)
        }
    }

    override fun disconnect() {
        disconnected = true
        try { dataIn?.close() } catch (_: Exception) {}
        try { dataOut?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        dataIn = null
        dataOut = null
        socket = null
        serverSocket = null
    }
}
