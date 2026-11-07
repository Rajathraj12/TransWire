package com.example.myapplication.domain.model

import java.util.UUID

data class TransferSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val mode: TransferMode,
    val state: TransferState,
    val files: List<SelectedFile>,
    val totalBytes: Long,
    val transferredBytes: Long,
    val estimatedTimeRemainingMs: Long?,
    val transferSpeedBytesPerSecond: Long?
)
