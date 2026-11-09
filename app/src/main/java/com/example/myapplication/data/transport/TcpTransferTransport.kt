package com.example.myapplication.data.transport

import com.example.myapplication.domain.model.FileManifest
import com.example.myapplication.domain.model.TransferMessage
import com.example.myapplication.domain.transport.TransferTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class TcpTransferTransport : TransferTransport {
    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var dataIn: DataInputStream? = null
    private var dataOut: DataOutputStream? = null

    override suspend fun initAsServer(port: Int) = withContext(Dispatchers.IO) {
        serverSocket = ServerSocket(port)
        socket = serverSocket!!.accept()
        setupStreams()
    }

    override suspend fun initAsClient(host: String, port: Int) = withContext(Dispatchers.IO) {
        socket = Socket()
        var connected = false
        var attempts = 0
        while (!connected && attempts < 10) {
            try {
                socket!!.connect(InetSocketAddress(host, port), 5000)
                connected = true
            } catch (e: Exception) {
                attempts++
                delay(1000) // Wait before retry
            }
        }
        if (!connected) throw IOException("Failed to connect to TCP server")
        setupStreams()
    }

    private fun setupStreams() {
        socket?.let {
            it.receiveBufferSize = 256 * 1024
            it.sendBufferSize = 256 * 1024
            dataIn = DataInputStream(it.getInputStream())
            dataOut = DataOutputStream(it.getOutputStream())
        }
    }

    override suspend fun writeMessage(message: TransferMessage) = withContext(Dispatchers.IO) {
        val out = dataOut ?: throw IOException("Not connected")
        when (message) {
            is TransferMessage.Manifest -> {
                out.writeInt(1) // type
                out.writeInt(message.version)
                out.writeInt(message.files.size)
                message.files.forEach {
                    out.writeUTF(it.fileId)
                    out.writeUTF(it.name)
                    out.writeLong(it.size)
                    out.writeUTF(it.mimeType)
                }
            }
            is TransferMessage.Chunk -> {
                out.writeInt(2) // type
                out.writeUTF(message.fileId)
                out.writeInt(message.chunkIndex)
                out.writeInt(message.length)
                out.write(message.payload, 0, message.length)
            }
            is TransferMessage.Completion -> {
                out.writeInt(3) // type
                out.writeUTF(message.fileId)
                out.writeUTF(message.sha256)
            }
            is TransferMessage.Error -> {
                out.writeInt(4) // type
                out.writeUTF(message.message)
            }
        }
        out.flush()
    }

    override suspend fun readMessage(): TransferMessage = withContext(Dispatchers.IO) {
        val input = dataIn ?: throw IOException("Not connected")
        try {
            val type = input.readInt()
            when (type) {
                1 -> {
                    val version = input.readInt()
                    val count = input.readInt()
                    val files = mutableListOf<FileManifest>()
                    for (i in 0 until count) {
                        files.add(
                            FileManifest(
                                fileId = input.readUTF(),
                                name = input.readUTF(),
                                size = input.readLong(),
                                mimeType = input.readUTF()
                            )
                        )
                    }
                    TransferMessage.Manifest(version, files)
                }
                2 -> {
                    val fileId = input.readUTF()
                    val chunkIndex = input.readInt()
                    val length = input.readInt()
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    TransferMessage.Chunk(fileId, chunkIndex, length, payload)
                }
                3 -> {
                    val fileId = input.readUTF()
                    val sha256 = input.readUTF()
                    TransferMessage.Completion(fileId, sha256)
                }
                4 -> {
                    TransferMessage.Error(input.readUTF())
                }
                else -> throw IOException("Unknown message type: $type")
            }
        } catch (e: EOFException) {
            throw IOException("Connection closed by peer")
        }
    }

    override fun disconnect() {
        try { dataIn?.close() } catch (_: Exception) {}
        try { dataOut?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
    }
}
