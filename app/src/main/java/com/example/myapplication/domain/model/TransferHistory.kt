package com.example.myapplication.domain.model

data class TransferHistory(
    val id: String,
    val session: TransferSession,
    val timestamp: Long,
    val remoteDeviceName: String
)
