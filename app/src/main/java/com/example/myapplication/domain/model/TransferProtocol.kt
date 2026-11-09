package com.example.myapplication.domain.model

data class FileManifest(
    val fileId: String,
    val name: String,
    val size: Long,
    val mimeType: String
)

sealed class TransferMessage {
    data class Manifest(val version: Int, val files: List<FileManifest>) : TransferMessage()
    data class Chunk(val fileId: String, val chunkIndex: Int, val length: Int, val payload: ByteArray) : TransferMessage()
    data class Completion(val fileId: String, val sha256: String) : TransferMessage()
    data class Error(val message: String) : TransferMessage()
}

data class TransferProgress(
    val currentFileName: String,
    val fileProgress: Float,
    val totalBytesTransferred: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val estimatedTimeRemainingMs: Long,
    val isCompleted: Boolean = false,
    val error: String? = null
)
