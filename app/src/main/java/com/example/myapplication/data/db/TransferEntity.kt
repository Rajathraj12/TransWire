package com.example.myapplication.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.myapplication.domain.model.DEFAULT_CHUNK_SIZE

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val fileId: String,
    val sessionId: String,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Long,
    var transferredBytes: Long,
    var status: String, // "PENDING", "IN_PROGRESS", "PAUSED", "INTERRUPTED", "COMPLETED", "FAILED", "CANCELLED"
    val fileUri: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    val totalChunks: Int = 0,
    val verifiedRanges: String = "",
    val tempFilePath: String? = null,
    val isIncoming: Boolean = true,
    val errorMessage: String? = null
)
