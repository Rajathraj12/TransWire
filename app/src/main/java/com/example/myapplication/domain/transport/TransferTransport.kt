package com.example.myapplication.domain.transport

import com.example.myapplication.domain.model.TransferMessage

interface TransferTransport {
    suspend fun initAsServer(port: Int)
    suspend fun initAsClient(host: String, port: Int)
    suspend fun writeMessage(message: TransferMessage)
    suspend fun readMessage(): TransferMessage
    fun disconnect()
}
