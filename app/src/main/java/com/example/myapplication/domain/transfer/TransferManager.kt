package com.example.myapplication.domain.transfer

import android.content.Context
import android.text.format.Formatter
import com.example.myapplication.data.db.AppDatabase
import com.example.myapplication.data.db.TransferEntity
import com.example.myapplication.data.storage.FileStorageManager
import com.example.myapplication.domain.model.*
import com.example.myapplication.domain.transport.TransferTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.security.MessageDigest

class TransferManager(
    private val context: Context,
    private val transport: TransferTransport,
    private val isGroupOwner: Boolean,
    private val hostIp: String,
    private val isSender: Boolean
) {
    private val fileStorageManager = FileStorageManager(context)
    private val dao = AppDatabase.getDatabase(context).transferDao()
    private val activeSessionId = java.util.UUID.randomUUID().toString()
    private val sessionFiles = LinkedHashMap<String, TransferredFileItem>()
    private val controlScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isCancelled = false
    @Volatile
    private var isPaused = false
    @Volatile
    private var activeFileId: String = "session"
    @Volatile
    private var activeChunkIndex: Int = 0
    @Volatile
    private var peerErrorMessage: String? = null

    private val _progress = MutableStateFlow(
        TransferProgress("", 0f, 0L, 0L, 0L, 0L)
    )
    val progress: StateFlow<TransferProgress> = _progress.asStateFlow()

    private class NonRetryableTransferException(message: String, cause: Throwable? = null) : Exception(message, cause)
    private class TransferCancelledException(message: String = "Transfer cancelled") : Exception(message)

    suspend fun start(files: List<SelectedFile> = emptyList()) {
        try {
            withContext(Dispatchers.IO) {
                dao.markOrphanedTransfersInterrupted()
                val protectedPaths = dao.getResumableTempPaths().toSet()
                fileStorageManager.cleanupStaleTempFiles(protectedPaths)
            }

            if (isSender) {
                validateSenderFilePermissions(files)
            }

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
        } catch (e: TransferCancelledException) {
            _progress.value = _progress.value.copy(
                error = e.message,
                isCompleted = true,
                isPaused = false,
                isCancelled = true,
                canRetry = false,
                transferredFiles = currentSessionFilesSnapshot()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: NonRetryableTransferException) {
            _progress.value = _progress.value.copy(
                error = e.message ?: "Transfer failed",
                isCompleted = true,
                isPaused = false,
                isCancelled = false,
                canRetry = false,
                transferredFiles = currentSessionFilesSnapshot()
            )
        } catch (e: Exception) {
            val cancelled = isCancelled
            val message = when {
                cancelled -> "Transfer cancelled"
                e is SocketTimeoutException -> e.message ?: "Transfer timed out waiting for peer"
                else -> e.message ?: "Transfer interrupted"
            }
            _progress.value = _progress.value.copy(
                error = message,
                isCompleted = true,
                isPaused = false,
                isCancelled = cancelled,
                canRetry = !cancelled,
                transferredFiles = currentSessionFilesSnapshot()
            )
        } finally {
            try {
                transport.disconnect()
            } catch (_: Exception) {
            }
            controlScope.cancel()
        }
    }

    private fun validateSenderFilePermissions(files: List<SelectedFile>) {
        if (files.isEmpty()) {
            throw NonRetryableTransferException("No files selected to send.")
        }
        files.forEach { file ->
            try {
                context.contentResolver.openAssetFileDescriptor(file.uri, "r")?.use {
                    // Accessible
                } ?: throw FileNotFoundException("Unable to open ${file.name}")
            } catch (e: SecurityException) {
                throw NonRetryableTransferException(
                    "Read permission was revoked for \"${file.name}\". Please re-select the file.",
                    e
                )
            } catch (e: FileNotFoundException) {
                throw NonRetryableTransferException(
                    "\"${file.name}\" is no longer accessible. Please re-select the file.",
                    e
                )
            }
        }
    }

    private fun buildDeterministicFileId(file: SelectedFile): String {
        val raw = "${file.name}|${file.size}|${file.mimeType}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun currentSessionFilesSnapshot(): List<TransferredFileItem> =
        synchronized(sessionFiles) { sessionFiles.values.toList() }

    private suspend fun runSender(files: List<SelectedFile>) = withContext(Dispatchers.IO) {
        val totalBytes = files.sumOf { it.size }
        val usedIds = HashSet<String>()
        val manifestFiles = files.mapIndexed { index, file ->
            val baseId = buildDeterministicFileId(file)
            val uniqueId = if (usedIds.add(baseId)) baseId else "${baseId}_$index"
            FileManifest(uniqueId, FileStorageManager.sanitizeFileName(file.name), file.size, file.mimeType)
        }

        synchronized(sessionFiles) {
            sessionFiles.clear()
            manifestFiles.forEachIndexed { index, manifest ->
                sessionFiles[manifest.fileId] = TransferredFileItem(
                    fileId = manifest.fileId,
                    fileName = manifest.name,
                    mimeType = manifest.mimeType,
                    size = manifest.size,
                    status = "PENDING",
                    fileUri = files[index].uri.toString()
                )
            }
        }

        transport.writeMessage(TransferMessage.Manifest(1, manifestFiles))

        val resumeMap = awaitResumeStateFromPeer()

        val controlJob: Job = launch {
            while (isActive && !isCancelled) {
                try {
                    when (val ctrl = transport.readMessage()) {
                        is TransferMessage.Pause -> {
                            isPaused = true
                            _progress.value = _progress.value.copy(isPaused = true)
                        }
                        is TransferMessage.Resume -> {
                            isPaused = false
                            _progress.value = _progress.value.copy(isPaused = false)
                        }
                        is TransferMessage.Cancel -> {
                            isCancelled = true
                            peerErrorMessage = "Transfer cancelled by receiver"
                            transport.disconnect()
                            break
                        }
                        is TransferMessage.Error -> {
                            peerErrorMessage = ctrl.message
                            transport.disconnect()
                            break
                        }
                        else -> {}
                    }
                } catch (_: SocketTimeoutException) {
                    // Normal when receiver has no control updates to send
                } catch (_: Exception) {
                    break
                }
            }
        }

        try {
            var totalTransferred = manifestFiles.sumOf { manifest ->
                val totalChunks = ChunkRangeTracker.computeTotalChunks(manifest.size, DEFAULT_CHUNK_SIZE)
                val resumeInfo = resumeMap[manifest.fileId]
                val chunkSize = resumeInfo?.chunkSize ?: DEFAULT_CHUNK_SIZE
                val tracker = ChunkRangeTracker.parse(resumeInfo?.verifiedRanges, totalChunks)
                tracker.verifiedBytes(manifest.size, chunkSize)
            }

            val startTime = System.currentTimeMillis()
            val sessionStartBytes = totalTransferred

            for ((index, fileInfo) in manifestFiles.withIndex()) {
                if (isCancelled) throw TransferCancelledException()
                peerErrorMessage?.let { throw Exception(it) }

                val selectedFile = files[index]
                activeFileId = fileInfo.fileId
                val resumeInfo = resumeMap[fileInfo.fileId]
                val chunkSize = resumeInfo?.chunkSize ?: DEFAULT_CHUNK_SIZE
                val totalChunks = ChunkRangeTracker.computeTotalChunks(fileInfo.size, chunkSize)
                val tracker = ChunkRangeTracker.parse(resumeInfo?.verifiedRanges, totalChunks)

                val digest = MessageDigest.getInstance("SHA-256")
                var fileTransferred = tracker.verifiedBytes(fileInfo.size, chunkSize)

                if (totalChunks > 0) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(selectedFile.uri)
                            ?: throw NonRetryableTransferException("Cannot open \"${selectedFile.name}\" for reading.")
                        inputStream.use { stream ->
                            val buffer = ByteArray(chunkSize)
                            for (chunkIdx in 0 until totalChunks) {
                                while (isPaused && !isCancelled && peerErrorMessage == null) {
                                    delay(100)
                                }
                                if (isCancelled) throw TransferCancelledException()
                                peerErrorMessage?.let { throw Exception(it) }

                                activeChunkIndex = chunkIdx
                                val expectedBytes = ChunkRangeTracker.expectedChunkSize(chunkIdx, fileInfo.size, chunkSize)
                                val bytesRead = readExactBytes(stream, buffer, expectedBytes)
                                if (bytesRead <= 0) {
                                    throw NonRetryableTransferException("Unexpected end of file while reading \"${selectedFile.name}\".")
                                }

                                val payload = if (bytesRead == buffer.size) buffer.copyOf() else buffer.copyOf(bytesRead)
                                digest.update(payload)

                                if (!tracker.isVerified(chunkIdx)) {
                                    transport.writeMessage(TransferMessage.Chunk(fileInfo.fileId, chunkIdx, bytesRead, payload))
                                    fileTransferred += bytesRead
                                    totalTransferred += bytesRead
                                    updateProgress(
                                        fileName = fileInfo.name,
                                        fileTransferred = fileTransferred,
                                        fileTotal = fileInfo.size,
                                        totalTransferred = totalTransferred,
                                        totalBytes = totalBytes,
                                        startTime = startTime,
                                        sessionStartBytes = sessionStartBytes
                                    )
                                }
                            }
                        }
                    } catch (e: SecurityException) {
                        val msg = "Read permission was revoked for \"${selectedFile.name}\". Please re-select the file."
                        runCatching { transport.writeMessage(TransferMessage.Error(msg)) }
                        throw NonRetryableTransferException(msg, e)
                    } catch (e: FileNotFoundException) {
                        val msg = "\"${selectedFile.name}\" is no longer accessible."
                        runCatching { transport.writeMessage(TransferMessage.Error(msg)) }
                        throw NonRetryableTransferException(msg, e)
                    }
                }

                if (isCancelled) throw TransferCancelledException()
                peerErrorMessage?.let { throw Exception(it) }

                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                transport.writeMessage(TransferMessage.Completion(fileInfo.fileId, sha256))
                synchronized(sessionFiles) {
                    sessionFiles[fileInfo.fileId]?.let { existing ->
                        sessionFiles[fileInfo.fileId] = existing.copy(status = "COMPLETED")
                    }
                }
            }

            transport.flush()
            _progress.value = _progress.value.copy(
                isCompleted = true,
                fileProgress = 1f,
                isPaused = false,
                canRetry = false,
                transferredFiles = currentSessionFilesSnapshot()
            )
        } finally {
            controlJob.cancel()
        }
    }

    private suspend fun awaitResumeStateFromPeer(): Map<String, FileResumeInfo> {
        while (!isCancelled) {
            when (val msg = transport.readMessage()) {
                is TransferMessage.ResumeState -> {
                    return msg.files.associateBy { it.fileId }
                }
                is TransferMessage.Error -> {
                    throw Exception(msg.message)
                }
                is TransferMessage.Cancel -> {
                    isCancelled = true
                    throw TransferCancelledException("Transfer cancelled by receiver")
                }
                is TransferMessage.Pause -> {
                    isPaused = true
                    _progress.value = _progress.value.copy(isPaused = true)
                }
                is TransferMessage.Resume -> {
                    isPaused = false
                    _progress.value = _progress.value.copy(isPaused = false)
                }
                else -> {}
            }
        }
        throw TransferCancelledException()
    }

    private fun readExactBytes(stream: java.io.InputStream, buffer: ByteArray, targetBytes: Int): Int {
        var offset = 0
        while (offset < targetBytes) {
            val read = stream.read(buffer, offset, targetBytes - offset)
            if (read == -1) break
            offset += read
        }
        return offset
    }

    private suspend fun runReceiver() = withContext(Dispatchers.IO) {
        val message = transport.readMessage()
        if (message !is TransferMessage.Manifest) {
            if (message is TransferMessage.Error) throw Exception(message.message)
            if (message is TransferMessage.Cancel) throw TransferCancelledException("Transfer cancelled by sender")
            throw Exception("Expected Manifest message")
        }

        val sanitizedManifest = message.files.map {
            it.copy(name = FileStorageManager.sanitizeFileName(it.name))
        }

        val totalBytes = sanitizedManifest.sumOf { it.size }
        val activeFiles = mutableMapOf<String, FileManifest>()
        val trackers = mutableMapOf<String, ChunkRangeTracker>()
        val tempFiles = mutableMapOf<String, File>()
        val randomAccessFiles = mutableMapOf<String, RandomAccessFile>()
        val newlyCreatedTempIds = mutableSetOf<String>()

        synchronized(sessionFiles) {
            sessionFiles.clear()
        }

        try {
            var remainingNeededBytes = 0L
            var largestFileSize = 0L
            var totalTransferred = 0L

            for (fileInfo in sanitizedManifest) {
                activeFiles[fileInfo.fileId] = fileInfo
                largestFileSize = maxOf(largestFileSize, fileInfo.size)
                val totalChunks = ChunkRangeTracker.computeTotalChunks(fileInfo.size, DEFAULT_CHUNK_SIZE)
                val existing = dao.getTransferById(fileInfo.fileId)

                val (tempFile, tracker) = if (
                    existing != null &&
                    existing.status != "COMPLETED" &&
                    existing.totalBytes == fileInfo.size &&
                    existing.chunkSize == DEFAULT_CHUNK_SIZE
                ) {
                    val (candidateFile, reused) = fileStorageManager.getOrCreateTempFile(
                        fileId = fileInfo.fileId,
                        existingPath = existing.tempFilePath,
                        maxExpectedBytes = fileInfo.size
                    )
                    val restoredTracker = if (reused) {
                        ChunkRangeTracker.parse(existing.verifiedRanges, totalChunks).apply {
                            clampToFileLength(candidateFile.length(), fileInfo.size, DEFAULT_CHUNK_SIZE)
                        }
                    } else {
                        newlyCreatedTempIds.add(fileInfo.fileId)
                        ChunkRangeTracker(totalChunks)
                    }
                    candidateFile to restoredTracker
                } else {
                    newlyCreatedTempIds.add(fileInfo.fileId)
                    fileStorageManager.createTempFile(fileInfo.fileId) to ChunkRangeTracker(totalChunks)
                }

                val verifiedBytes = tracker.verifiedBytes(fileInfo.size, DEFAULT_CHUNK_SIZE)
                remainingNeededBytes += (fileInfo.size - verifiedBytes).coerceAtLeast(0L)
                totalTransferred += verifiedBytes

                tempFiles[fileInfo.fileId] = tempFile
                trackers[fileInfo.fileId] = tracker
            }

            // Ensure storage for remaining temp writes + final copy of largest file into Downloads
            val requiredSpace = remainingNeededBytes + largestFileSize
            if (!fileStorageManager.hasSufficientStorage(requiredSpace)) {
                val available = Formatter.formatFileSize(context, fileStorageManager.getAvailableStorageBytes())
                val needed = Formatter.formatFileSize(context, requiredSpace)
                newlyCreatedTempIds.forEach { id -> fileStorageManager.deleteTempFile(tempFiles[id]) }
                val errMsg = "Insufficient storage space: need $needed, $available available."
                runCatching { transport.writeMessage(TransferMessage.Error("Receiver has insufficient storage space")) }
                throw Exception(errMsg)
            }

            val resumeInfos = ArrayList<FileResumeInfo>(sanitizedManifest.size)
            for (fileInfo in sanitizedManifest) {
                val tempFile = tempFiles[fileInfo.fileId]!!
                val tracker = trackers[fileInfo.fileId]!!
                val verifiedBytes = tracker.verifiedBytes(fileInfo.size, DEFAULT_CHUNK_SIZE)
                val rangesStr = tracker.toRangeString()
                val initialStatus = if (tracker.verifiedCount() > 0) "IN_PROGRESS" else "PENDING"

                randomAccessFiles[fileInfo.fileId] = RandomAccessFile(tempFile, "rw")
                resumeInfos.add(FileResumeInfo(fileInfo.fileId, rangesStr, DEFAULT_CHUNK_SIZE))

                synchronized(sessionFiles) {
                    sessionFiles[fileInfo.fileId] = TransferredFileItem(
                        fileId = fileInfo.fileId,
                        fileName = fileInfo.name,
                        mimeType = fileInfo.mimeType,
                        size = fileInfo.size,
                        status = initialStatus,
                        fileUri = null
                    )
                }

                dao.insert(
                    TransferEntity(
                        fileId = fileInfo.fileId,
                        sessionId = activeSessionId,
                        fileName = fileInfo.name,
                        mimeType = fileInfo.mimeType,
                        totalBytes = fileInfo.size,
                        transferredBytes = verifiedBytes,
                        status = initialStatus,
                        chunkSize = DEFAULT_CHUNK_SIZE,
                        totalChunks = tracker.totalChunks,
                        verifiedRanges = rangesStr,
                        tempFilePath = tempFile.absolutePath,
                        isIncoming = true
                    )
                )
            }

            transport.writeMessage(TransferMessage.ResumeState(resumeInfos))

            val startTime = System.currentTimeMillis()
            val sessionStartBytes = totalTransferred
            val chunksSincePersist = mutableMapOf<String, Int>()
            var lastPersistAt = System.currentTimeMillis()

            var completedFiles = 0
            while (completedFiles < sanitizedManifest.size) {
                if (isCancelled) throw TransferCancelledException()

                val msg = try {
                    transport.readMessage()
                } catch (e: SocketTimeoutException) {
                    if (isPaused) continue else throw e
                }

                when (msg) {
                    is TransferMessage.Chunk -> {
                        val fileInfo = activeFiles[msg.fileId] ?: continue
                        val tracker = trackers[msg.fileId] ?: continue
                        val raf = randomAccessFiles[msg.fileId] ?: continue
                        if (msg.chunkIndex !in 0 until tracker.totalChunks) {
                            throw Exception("Received out-of-bounds chunk index ${msg.chunkIndex}")
                        }
                        val expectedSize = ChunkRangeTracker.expectedChunkSize(msg.chunkIndex, fileInfo.size, DEFAULT_CHUNK_SIZE)
                        if (msg.length != expectedSize) {
                            throw Exception("Invalid chunk size ${msg.length}, expected $expectedSize")
                        }

                        activeFileId = msg.fileId
                        activeChunkIndex = msg.chunkIndex

                        if (!tracker.isVerified(msg.chunkIndex)) {
                            raf.seek(msg.chunkIndex.toLong() * DEFAULT_CHUNK_SIZE)
                            raf.write(msg.payload, 0, msg.length)
                            tracker.markVerified(msg.chunkIndex)
                            totalTransferred += msg.length
                        }

                        val fileBytes = tracker.verifiedBytes(fileInfo.size, DEFAULT_CHUNK_SIZE)
                        val countSincePersist = (chunksSincePersist[msg.fileId] ?: 0) + 1
                        chunksSincePersist[msg.fileId] = countSincePersist
                        val now = System.currentTimeMillis()
                        if (countSincePersist >= 16 || now - lastPersistAt >= 500L) {
                            dao.updateChunkProgress(
                                fileId = msg.fileId,
                                bytes = fileBytes,
                                verifiedRanges = tracker.toRangeString(),
                                status = if (isPaused) "PAUSED" else "IN_PROGRESS"
                            )
                            chunksSincePersist[msg.fileId] = 0
                            lastPersistAt = now
                        }

                        updateProgress(
                            fileName = fileInfo.name,
                            fileTransferred = fileBytes,
                            fileTotal = fileInfo.size,
                            totalTransferred = totalTransferred,
                            totalBytes = totalBytes,
                            startTime = startTime,
                            sessionStartBytes = sessionStartBytes
                        )
                    }
                    is TransferMessage.Completion -> {
                        val fileInfo = activeFiles[msg.fileId]
                        val tracker = trackers[msg.fileId]
                        val tempFile = tempFiles[msg.fileId]
                        val raf = randomAccessFiles.remove(msg.fileId)
                        runCatching { raf?.fd?.sync() }
                        runCatching { raf?.close() }

                        if (fileInfo != null && tracker != null && tempFile != null) {
                            if (!tracker.isComplete()) {
                                persistInterruptedState(activeFiles, trackers)
                                throw Exception("Incomplete file received for \"${fileInfo.name}\" (${tracker.verifiedCount()}/${tracker.totalChunks} chunks)")
                            }

                            val calculatedSha = fileStorageManager.computeFileSha256(tempFile)
                            if (calculatedSha.equals(msg.sha256, ignoreCase = true)) {
                                if (!fileStorageManager.hasSufficientStorage(fileInfo.size)) {
                                    dao.updateFailure(msg.fileId, "FAILED", "Insufficient storage saving file")
                                    throw Exception("Insufficient storage space to save \"${fileInfo.name}\".")
                                }
                                val savedUri = fileStorageManager.saveToDownloads(tempFile, fileInfo.name, fileInfo.mimeType)
                                val uriString = savedUri?.toString()
                                val finalName = if (savedUri != null) {
                                    fileStorageManager.resolveSavedDisplayName(savedUri, fileInfo.name)
                                } else {
                                    fileInfo.name
                                }
                                val finalStatus = if (uriString != null) "COMPLETED" else "FAILED"
                                if (finalStatus == "COMPLETED") {
                                    dao.updateCompletion(msg.fileId, "COMPLETED", uriString, finalName)
                                } else {
                                    dao.updateFailure(msg.fileId, "FAILED", "Failed to save file to Downloads")
                                }
                                synchronized(sessionFiles) {
                                    sessionFiles[msg.fileId] = TransferredFileItem(
                                        fileId = fileInfo.fileId,
                                        fileName = finalName,
                                        mimeType = fileInfo.mimeType,
                                        size = fileInfo.size,
                                        status = finalStatus,
                                        fileUri = uriString
                                    )
                                }
                            } else {
                                // Corrupted file: purge temp file and reset ranges so retry fetches clean data
                                fileStorageManager.deleteTempFile(tempFile)
                                dao.updateChunkProgress(msg.fileId, 0L, "", "FAILED")
                                dao.updateFailure(msg.fileId, "FAILED", "SHA-256 verification failed")
                                synchronized(sessionFiles) {
                                    sessionFiles[msg.fileId]?.let { existing ->
                                        sessionFiles[msg.fileId] = existing.copy(status = "FAILED")
                                    }
                                }
                            }
                        }
                        completedFiles++
                    }
                    is TransferMessage.Error -> {
                        throw Exception("Peer error: ${msg.message}")
                    }
                    is TransferMessage.Pause -> {
                        isPaused = true
                        persistInterruptedState(activeFiles, trackers, forceStatus = "PAUSED")
                        _progress.value = _progress.value.copy(isPaused = true)
                    }
                    is TransferMessage.Resume -> {
                        isPaused = false
                        _progress.value = _progress.value.copy(isPaused = false)
                    }
                    is TransferMessage.Cancel -> {
                        isCancelled = true
                        throw TransferCancelledException("Transfer cancelled by sender")
                    }
                    is TransferMessage.Manifest, is TransferMessage.ResumeState -> {
                        // Ignore duplicate handshake frames mid-stream
                    }
                }
            }

            _progress.value = _progress.value.copy(
                isCompleted = true,
                fileProgress = 1f,
                isPaused = false,
                canRetry = false,
                transferredFiles = currentSessionFilesSnapshot()
            )
        } catch (e: TransferCancelledException) {
            randomAccessFiles.values.forEach { runCatching { it.close() } }
            randomAccessFiles.clear()
            // Safe temp file cleanup on cancellation for incomplete files
            activeFiles.keys.forEach { id ->
                val isDone = synchronized(sessionFiles) { sessionFiles[id]?.status == "COMPLETED" }
                if (!isDone) {
                    fileStorageManager.deleteTempFile(tempFiles[id])
                    dao.updateCompletion(id, "CANCELLED", null, activeFiles[id]?.name ?: "file")
                }
            }
            throw e
        } catch (e: Exception) {
            randomAccessFiles.values.forEach {
                runCatching { it.fd.sync() }
                runCatching { it.close() }
            }
            randomAccessFiles.clear()
            persistInterruptedState(activeFiles, trackers)
            throw e
        } finally {
            randomAccessFiles.values.forEach { runCatching { it.close() } }
            randomAccessFiles.clear()
        }
    }

    private suspend fun persistInterruptedState(
        activeFiles: Map<String, FileManifest>,
        trackers: Map<String, ChunkRangeTracker>,
        forceStatus: String? = null
    ) {
        for ((fileId, manifest) in activeFiles) {
            val isCompleted = synchronized(sessionFiles) { sessionFiles[fileId]?.status == "COMPLETED" }
            if (isCompleted) continue
            val tracker = trackers[fileId] ?: continue
            val bytes = tracker.verifiedBytes(manifest.size, DEFAULT_CHUNK_SIZE)
            val status = forceStatus ?: if (isPaused) "PAUSED" else "INTERRUPTED"
            dao.updateChunkProgress(
                fileId = fileId,
                bytes = bytes,
                verifiedRanges = tracker.toRangeString(),
                status = status
            )
            synchronized(sessionFiles) {
                sessionFiles[fileId]?.let { existing ->
                    sessionFiles[fileId] = existing.copy(status = status)
                }
            }
        }
    }

    private fun updateProgress(
        fileName: String,
        fileTransferred: Long,
        fileTotal: Long,
        totalTransferred: Long,
        totalBytes: Long,
        startTime: Long,
        sessionStartBytes: Long
    ) {
        val elapsed = System.currentTimeMillis() - startTime
        val bytesThisRun = (totalTransferred - sessionStartBytes).coerceAtLeast(0L)
        val speed = if (elapsed > 0) (bytesThisRun * 1000L) / elapsed else 0L
        val remainingBytes = (totalBytes - totalTransferred).coerceAtLeast(0L)
        val eta = if (speed > 0) (remainingBytes * 1000L) / speed else 0L

        _progress.value = TransferProgress(
            currentFileName = fileName,
            fileProgress = if (fileTotal > 0) (fileTransferred.toFloat() / fileTotal).coerceIn(0f, 1f) else 0f,
            totalBytesTransferred = totalTransferred,
            totalBytes = totalBytes,
            speedBytesPerSec = speed,
            estimatedTimeRemainingMs = eta,
            isPaused = isPaused,
            transferredFiles = currentSessionFilesSnapshot()
        )
    }

    fun cancelTransfer() {
        isCancelled = true
        val fileIdToCancel = activeFileId
        controlScope.launch {
            runCatching { transport.writeMessage(TransferMessage.Cancel(fileIdToCancel)) }
            runCatching { transport.disconnect() }
        }
    }

    fun pauseTransfer() {
        isPaused = true
        _progress.value = _progress.value.copy(isPaused = true)
        val fileIdToPause = activeFileId
        controlScope.launch {
            runCatching { transport.writeMessage(TransferMessage.Pause(fileIdToPause)) }
        }
    }

    fun resumeTransfer() {
        isPaused = false
        _progress.value = _progress.value.copy(isPaused = false)
        val fileIdToResume = activeFileId
        val chunkIdx = activeChunkIndex
        controlScope.launch {
            runCatching { transport.writeMessage(TransferMessage.Resume(fileIdToResume, chunkIdx)) }
        }
    }
}
