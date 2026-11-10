package com.example.myapplication.util

import com.example.myapplication.domain.model.FileManifest
import com.example.myapplication.domain.model.FileResumeInfo
import com.example.myapplication.domain.model.TransferMessage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

object MessageSerializer {

    const val PROTOCOL_VERSION = 1
    const val MAX_MANIFEST_FILES = 10_000
    const val MAX_CHUNK_BYTES = 256 * 1024
    const val MAX_FILE_SIZE_BYTES = 100L * 1024L * 1024L * 1024L // 100 GiB
    const val MAX_RANGE_STRING_LENGTH = 65_536
    const val MAX_ERROR_MESSAGE_LENGTH = 1024
    const val MAX_FILE_NAME_LENGTH = 512
    const val MAX_MIME_LENGTH = 256

    private val FILE_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    fun serialize(message: TransferMessage): ByteArray {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        writeMessage(out, message)
        out.flush()
        return baos.toByteArray()
    }

    fun writeMessage(out: DataOutputStream, message: TransferMessage) {
        when (message) {
            is TransferMessage.Manifest -> {
                require(message.version == PROTOCOL_VERSION) { "Unsupported manifest version" }
                require(message.files.size <= MAX_MANIFEST_FILES) { "Too many manifest entries" }
                out.writeInt(1)
                out.writeInt(message.version)
                out.writeInt(message.files.size)
                message.files.forEach {
                    validateFileId(it.fileId)
                    require(it.name.isNotBlank() && it.name.length <= MAX_FILE_NAME_LENGTH) { "Invalid manifest file name" }
                    require(it.size in 0L..MAX_FILE_SIZE_BYTES) { "Invalid manifest file size" }
                    require(it.mimeType.length <= MAX_MIME_LENGTH) { "Invalid manifest MIME type" }
                    out.writeUTF(it.fileId)
                    out.writeUTF(it.name)
                    out.writeLong(it.size)
                    out.writeUTF(it.mimeType)
                }
            }
            is TransferMessage.Chunk -> {
                validateFileId(message.fileId)
                require(message.chunkIndex >= 0) { "Negative chunk index" }
                require(message.length in 1..MAX_CHUNK_BYTES && message.payload.size >= message.length) {
                    "Invalid chunk length"
                }
                out.writeInt(2)
                out.writeUTF(message.fileId)
                out.writeInt(message.chunkIndex)
                out.writeInt(message.length)
                out.write(message.payload, 0, message.length)
            }
            is TransferMessage.Completion -> {
                validateFileId(message.fileId)
                require(SHA256_REGEX.matches(message.sha256)) { "Invalid SHA-256 hash format" }
                out.writeInt(3)
                out.writeUTF(message.fileId)
                out.writeUTF(message.sha256)
            }
            is TransferMessage.Error -> {
                require(message.message.length <= MAX_ERROR_MESSAGE_LENGTH) { "Error message too long" }
                out.writeInt(4)
                out.writeUTF(message.message)
            }
            is TransferMessage.Pause -> {
                validateFileId(message.fileId)
                out.writeInt(5)
                out.writeUTF(message.fileId)
            }
            is TransferMessage.Resume -> {
                validateFileId(message.fileId)
                require(message.chunkIndex >= 0) { "Negative resume chunk index" }
                out.writeInt(6)
                out.writeUTF(message.fileId)
                out.writeInt(message.chunkIndex)
            }
            is TransferMessage.Cancel -> {
                validateFileId(message.fileId)
                out.writeInt(7)
                out.writeUTF(message.fileId)
            }
            is TransferMessage.ResumeState -> {
                require(message.files.size <= MAX_MANIFEST_FILES) { "Too many resume entries" }
                out.writeInt(8)
                out.writeInt(message.files.size)
                message.files.forEach {
                    validateFileId(it.fileId)
                    require(it.verifiedRanges.length <= MAX_RANGE_STRING_LENGTH) { "Resume range string too long" }
                    require(it.chunkSize in 1024..MAX_CHUNK_BYTES) { "Invalid chunk size" }
                    out.writeUTF(it.fileId)
                    out.writeUTF(it.verifiedRanges)
                    out.writeInt(it.chunkSize)
                }
            }
        }
    }

    fun deserialize(bytes: ByteArray): TransferMessage {
        if (bytes.isEmpty()) {
            throw IOException("Empty transfer frame")
        }
        val bais = ByteArrayInputStream(bytes)
        val input = DataInputStream(bais)
        val message = readMessage(input)
        if (bais.available() > 0) {
            throw IOException("Unexpected trailing bytes in transfer frame")
        }
        return message
    }

    fun readMessage(input: DataInputStream): TransferMessage {
        val type = input.readInt()
        return when (type) {
            1 -> {
                val version = input.readInt()
                if (version != PROTOCOL_VERSION) {
                    throw IOException("Unsupported manifest protocol version: $version")
                }
                val count = input.readInt()
                if (count < 0 || count > MAX_MANIFEST_FILES) {
                    throw IOException("Invalid manifest file count: $count")
                }
                val files = ArrayList<FileManifest>(count)
                for (i in 0 until count) {
                    val fileId = readValidatedFileId(input)
                    val name = input.readUTF()
                    if (name.isBlank() || name.length > MAX_FILE_NAME_LENGTH) {
                        throw IOException("Invalid file name in manifest")
                    }
                    val size = input.readLong()
                    if (size < 0L || size > MAX_FILE_SIZE_BYTES) {
                        throw IOException("Invalid file size in manifest: $size")
                    }
                    val mimeType = input.readUTF()
                    if (mimeType.length > MAX_MIME_LENGTH) {
                        throw IOException("Invalid MIME type in manifest")
                    }
                    files.add(FileManifest(fileId = fileId, name = name, size = size, mimeType = mimeType))
                }
                TransferMessage.Manifest(version, files)
            }
            2 -> {
                val fileId = readValidatedFileId(input)
                val chunkIndex = input.readInt()
                if (chunkIndex < 0) {
                    throw IOException("Invalid negative chunk index: $chunkIndex")
                }
                val length = input.readInt()
                if (length <= 0 || length > MAX_CHUNK_BYTES) {
                    throw IOException("Invalid chunk length: $length")
                }
                val payload = ByteArray(length)
                input.readFully(payload)
                TransferMessage.Chunk(fileId, chunkIndex, length, payload)
            }
            3 -> {
                val fileId = readValidatedFileId(input)
                val sha256 = input.readUTF()
                if (!SHA256_REGEX.matches(sha256)) {
                    throw IOException("Invalid SHA-256 digest in completion message")
                }
                TransferMessage.Completion(fileId, sha256.lowercase())
            }
            4 -> {
                val msg = input.readUTF()
                if (msg.length > MAX_ERROR_MESSAGE_LENGTH) {
                    throw IOException("Oversized error message")
                }
                TransferMessage.Error(msg)
            }
            5 -> {
                TransferMessage.Pause(readValidatedFileId(input))
            }
            6 -> {
                val fileId = readValidatedFileId(input)
                val chunkIndex = input.readInt()
                if (chunkIndex < 0) {
                    throw IOException("Invalid negative resume chunk index: $chunkIndex")
                }
                TransferMessage.Resume(fileId, chunkIndex)
            }
            7 -> {
                TransferMessage.Cancel(readValidatedFileId(input))
            }
            8 -> {
                val count = input.readInt()
                if (count < 0 || count > MAX_MANIFEST_FILES) {
                    throw IOException("Invalid resume state count: $count")
                }
                val items = ArrayList<FileResumeInfo>(count)
                for (i in 0 until count) {
                    val fileId = readValidatedFileId(input)
                    val verifiedRanges = input.readUTF()
                    if (verifiedRanges.length > MAX_RANGE_STRING_LENGTH) {
                        throw IOException("Oversized verified ranges string")
                    }
                    val chunkSize = input.readInt()
                    if (chunkSize < 1024 || chunkSize > MAX_CHUNK_BYTES) {
                        throw IOException("Invalid resume chunk size: $chunkSize")
                    }
                    items.add(FileResumeInfo(fileId, verifiedRanges, chunkSize))
                }
                TransferMessage.ResumeState(items)
            }
            else -> throw IOException("Unknown message type: $type")
        }
    }

    private fun readValidatedFileId(input: DataInputStream): String {
        val fileId = input.readUTF()
        if (!FILE_ID_REGEX.matches(fileId)) {
            throw IOException("Invalid fileId format")
        }
        return fileId
    }

    private fun validateFileId(fileId: String) {
        require(FILE_ID_REGEX.matches(fileId)) { "Invalid fileId format" }
    }
}
