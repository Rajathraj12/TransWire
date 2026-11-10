package com.example.myapplication.domain.model

const val DEFAULT_CHUNK_SIZE = 64 * 1024

data class FileManifest(
    val fileId: String,
    val name: String,
    val size: Long,
    val mimeType: String
)

data class FileResumeInfo(
    val fileId: String,
    val verifiedRanges: String,
    val chunkSize: Int = DEFAULT_CHUNK_SIZE
)

sealed class TransferMessage {
    data class Manifest(val version: Int, val files: List<FileManifest>) : TransferMessage()
    data class Chunk(val fileId: String, val chunkIndex: Int, val length: Int, val payload: ByteArray) : TransferMessage()
    data class Completion(val fileId: String, val sha256: String) : TransferMessage()
    data class Error(val message: String) : TransferMessage()
    data class Pause(val fileId: String) : TransferMessage()
    data class Resume(val fileId: String, val chunkIndex: Int) : TransferMessage()
    data class Cancel(val fileId: String) : TransferMessage()
    data class ResumeState(val files: List<FileResumeInfo>) : TransferMessage()
}

data class TransferredFileItem(
    val fileId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val status: String,
    val fileUri: String? = null
)

data class TransferProgress(
    val currentFileName: String,
    val fileProgress: Float,
    val totalBytesTransferred: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val estimatedTimeRemainingMs: Long,
    val isCompleted: Boolean = false,
    val isPaused: Boolean = false,
    val isCancelled: Boolean = false,
    val canRetry: Boolean = false,
    val error: String? = null,
    val transferredFiles: List<TransferredFileItem> = emptyList()
)
