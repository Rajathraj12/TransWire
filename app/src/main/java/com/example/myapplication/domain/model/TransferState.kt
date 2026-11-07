package com.example.myapplication.domain.model

enum class TransferState {
    PENDING,
    CONNECTING,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELLED
}
