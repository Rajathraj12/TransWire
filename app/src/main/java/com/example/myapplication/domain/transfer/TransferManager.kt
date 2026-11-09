package com.example.myapplication.domain.transfer

import android.content.Context
import com.example.myapplication.data.storage.FileStorageManager
import com.example.myapplication.domain.model.*
import com.example.myapplication.domain.transport.TransferTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class TransferManager(
    private val context: Context,
    private val transport: TransferTransport,
    private val isGroupOwner: Boolean,
    private val hostIp: String,
    private val isSender: Boolean
) {
    private val fileStorageManager = FileStorageManager(context)
    
    private val _progress = MutableStateFlow(
        TransferProgress("", 0f, 0L, 0L, 0L, 0L)
    )
    val progress: StateFlow<TransferProgress> = _progress.asStateFlow()

    suspend fun start(files: List<SelectedFile> = emptyList()) {
        try {
            if (isGroupOwner) {
                transport.initAsServer(8888)
            } else {
                transport.initAsClient(hostIp, 8888)
            }

            if (isSender) {
                runSender(files)
            } else {
                runReceiver()
            }
        } catch (e: Exception) {
            _progress.value = _progress.value.copy(
                error = e.message ?: "Transfer failed",
                isCompleted = true
            )
        } finally {
            transport.disconnect()
        }
    }

    private suspend fun runSender(files: List<SelectedFile>) = withContext(Dispatchers.IO) {
        val totalBytes = files.sumOf { it.size }
        val manifestFiles = files.map { 
            FileManifest(UUID.randomUUID().toString(), it.name, it.size, it.mimeType) 
        }
        
        transport.writeMessage(TransferMessage.Manifest(1, manifestFiles))
        
        var totalTransferred = 0L
        val startTime = System.currentTimeMillis()

        for ((index, fileInfo) in manifestFiles.withIndex()) {
            val selectedFile = files[index]
            val digest = MessageDigest.getInstance("SHA-256")
            
            context.contentResolver.openInputStream(selectedFile.uri)?.use { inputStream ->
                val buffer = ByteArray(256 * 1024) // 256 KiB chunks
                var bytesRead: Int
                var chunkIndex = 0
                var fileTransferred = 0L
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    val payload = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                    transport.writeMessage(TransferMessage.Chunk(fileInfo.fileId, chunkIndex++, bytesRead, payload))
                    
                    digest.update(payload)
                    fileTransferred += bytesRead
                    totalTransferred += bytesRead
                    
                    updateProgress(fileInfo.name, fileTransferred, fileInfo.size, totalTransferred, totalBytes, startTime)
                }
            }
            
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            transport.writeMessage(TransferMessage.Completion(fileInfo.fileId, sha256))
        }
        
        _progress.value = _progress.value.copy(isCompleted = true, fileProgress = 1f)
    }

    private suspend fun runReceiver() = withContext(Dispatchers.IO) {
        val message = transport.readMessage()
        if (message !is TransferMessage.Manifest) throw Exception("Expected Manifest message")
        
        val totalBytes = message.files.sumOf { it.size }
        var totalTransferred = 0L
        val startTime = System.currentTimeMillis()
        
        val activeFiles = mutableMapOf<String, FileManifest>()
        val fileDigests = mutableMapOf<String, MessageDigest>()
        val fileOutputs = mutableMapOf<String, FileOutputStream>()
        val tempFiles = mutableMapOf<String, java.io.File>()
        val fileTransferredBytes = mutableMapOf<String, Long>()
        
        message.files.forEach { 
            activeFiles[it.fileId] = it 
            val tempFile = fileStorageManager.createTempFile(it.fileId)
            tempFiles[it.fileId] = tempFile
            fileOutputs[it.fileId] = FileOutputStream(tempFile)
            fileDigests[it.fileId] = MessageDigest.getInstance("SHA-256")
            fileTransferredBytes[it.fileId] = 0L
        }

        var completedFiles = 0
        while (completedFiles < message.files.size) {
            when (val msg = transport.readMessage()) {
                is TransferMessage.Chunk -> {
                    val out = fileOutputs[msg.fileId] ?: continue
                    out.write(msg.payload)
                    fileDigests[msg.fileId]?.update(msg.payload)
                    
                    val currentTransferred = (fileTransferredBytes[msg.fileId] ?: 0L) + msg.length
                    fileTransferredBytes[msg.fileId] = currentTransferred
                    totalTransferred += msg.length
                    
                    val fileInfo = activeFiles[msg.fileId]
                    if (fileInfo != null) {
                        updateProgress(fileInfo.name, currentTransferred, fileInfo.size, totalTransferred, totalBytes, startTime)
                    }
                }
                is TransferMessage.Completion -> {
                    fileOutputs[msg.fileId]?.close()
                    val calculatedSha = fileDigests[msg.fileId]?.digest()?.joinToString("") { "%02x".format(it) }
                    
                    if (calculatedSha == msg.sha256) {
                        // Hash verified
                        val fileInfo = activeFiles[msg.fileId]
                        val tempFile = tempFiles[msg.fileId]
                        if (fileInfo != null && tempFile != null) {
                            fileStorageManager.saveToDownloads(tempFile, fileInfo.name, fileInfo.mimeType)
                        }
                    } else {
                        // Verification failed, cleanup
                        tempFiles[msg.fileId]?.delete()
                    }
                    completedFiles++
                }
                is TransferMessage.Error -> {
                    throw Exception("Peer error: ${msg.message}")
                }
                is TransferMessage.Manifest -> {
                    // Ignore unexpected manifest
                }
            }
        }
        
        _progress.value = _progress.value.copy(isCompleted = true, fileProgress = 1f)
    }

    private fun updateProgress(
        fileName: String,
        fileTransferred: Long,
        fileTotal: Long,
        totalTransferred: Long,
        totalBytes: Long,
        startTime: Long
    ) {
        val elapsed = System.currentTimeMillis() - startTime
        val speed = if (elapsed > 0) (totalTransferred * 1000) / elapsed else 0L
        val remainingBytes = totalBytes - totalTransferred
        val eta = if (speed > 0) (remainingBytes * 1000) / speed else 0L
        
        _progress.value = TransferProgress(
            currentFileName = fileName,
            fileProgress = if (fileTotal > 0) fileTransferred.toFloat() / fileTotal else 0f,
            totalBytesTransferred = totalTransferred,
            totalBytes = totalBytes,
            speedBytesPerSec = speed,
            estimatedTimeRemainingMs = eta
        )
    }
}
