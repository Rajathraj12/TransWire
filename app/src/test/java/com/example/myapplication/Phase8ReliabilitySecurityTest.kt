package com.example.myapplication

import com.example.myapplication.data.storage.FileStorageManager
import com.example.myapplication.domain.model.DEFAULT_CHUNK_SIZE
import com.example.myapplication.domain.model.FileManifest
import com.example.myapplication.domain.model.FileResumeInfo
import com.example.myapplication.domain.model.TransferMessage
import com.example.myapplication.domain.transfer.ChunkRangeTracker
import com.example.myapplication.util.MessageSerializer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class Phase8ReliabilitySecurityTest {

    @Test
    fun chunkRangeTracker_encodesAndTracksMissingIndices() {
        val tracker = ChunkRangeTracker(totalChunks = 10)
        tracker.markVerified(0)
        tracker.markVerified(1)
        tracker.markVerified(2)
        tracker.markVerified(5)
        tracker.markVerified(7)
        tracker.markVerified(8)

        assertEquals("0-2,5,7-8", tracker.toRangeString())
        assertEquals(listOf(3, 4, 6, 9), tracker.missingIndices())
        assertFalse(tracker.isComplete())

        val reparsed = ChunkRangeTracker.parse("0-2,5,7-8,999,-5,8-3", totalChunks = 10)
        assertEquals("0-2,5,7-8", reparsed.toRangeString())
        assertEquals(listOf(3, 4, 6, 9), reparsed.missingIndices())
    }

    @Test
    fun chunkRangeTracker_clampsToPhysicalDiskLength() {
        val chunkSize = 64 * 1024
        val totalBytes = (3L * chunkSize) + 100L // 4 chunks
        val tracker = ChunkRangeTracker.parse("0-3", totalChunks = 4)

        // Simulate disk truncated inside chunk 2
        val truncatedLength = (2L * chunkSize) + 10L
        tracker.clampToFileLength(truncatedLength, totalBytes, chunkSize)

        assertEquals("0-1", tracker.toRangeString())
        assertEquals(listOf(2, 3), tracker.missingIndices())
    }

    @Test
    fun sanitizeFileName_blocksTraversalAndInvalidChars() {
        assertEquals("passwd", FileStorageManager.sanitizeFileName("../../etc/passwd"))
        assertEquals("evil.apk", FileStorageManager.sanitizeFileName("..\\..\\temp\\evil.apk"))
        assertEquals("received_file", FileStorageManager.sanitizeFileName(".."))
        assertEquals("received_file", FileStorageManager.sanitizeFileName("."))
        assertEquals("received_file", FileStorageManager.sanitizeFileName("   "))
        assertEquals("a_b_c.txt", FileStorageManager.sanitizeFileName("a\u0000b:c.txt"))
    }

    @Test
    fun messageSerializer_roundTripsResumeStateAndCompletion() {
        val resume = TransferMessage.ResumeState(
            listOf(FileResumeInfo("file_123", "0-4,6", 65536))
        )
        val decodedResume = MessageSerializer.deserialize(MessageSerializer.serialize(resume))
        assertEquals(resume, decodedResume)

        val sha = "a".repeat(64)
        val completion = TransferMessage.Completion("file_123", sha)
        val decodedCompletion = MessageSerializer.deserialize(MessageSerializer.serialize(completion))
        assertEquals(completion, decodedCompletion)

        val manifest = TransferMessage.Manifest(
            1,
            listOf(FileManifest("file_123", "report.pdf", 1024L, "application/pdf"))
        )
        val decodedManifest = MessageSerializer.deserialize(MessageSerializer.serialize(manifest))
        assertEquals(manifest, decodedManifest)

        val ackOk = TransferMessage.CompletionAck("file_123", success = true)
        val decodedAckOk = MessageSerializer.deserialize(MessageSerializer.serialize(ackOk))
        assertEquals(ackOk, decodedAckOk)

        val ackErr = TransferMessage.CompletionAck("file_123", success = false, errorMessage = "SHA-256 verification failed")
        val decodedAckErr = MessageSerializer.deserialize(MessageSerializer.serialize(ackErr))
        assertEquals(ackErr, decodedAckErr)

        val maxChunkFrame = MessageSerializer.serialize(
            TransferMessage.Chunk("a".repeat(32), 0, DEFAULT_CHUNK_SIZE, ByteArray(DEFAULT_CHUNK_SIZE))
        )
        assertTrue(maxChunkFrame.size < 64 * 1024)
    }

    @Test(expected = IOException::class)
    fun messageSerializer_rejectsOversizedChunkLength() {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.writeInt(2) // Chunk
        out.writeUTF("valid_id")
        out.writeInt(0)
        out.writeInt(MessageSerializer.MAX_CHUNK_BYTES + 1)
        out.flush()

        MessageSerializer.deserialize(baos.toByteArray())
    }

    @Test(expected = IOException::class)
    fun messageSerializer_rejectsInvalidFileIdTraversal() {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.writeInt(5) // Pause
        out.writeUTF("../bad_id")
        out.flush()

        MessageSerializer.deserialize(baos.toByteArray())
    }

    @Test(expected = IOException::class)
    fun messageSerializer_rejectsInvalidSha256() {
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        out.writeInt(3) // Completion
        out.writeUTF("valid_id")
        out.writeUTF("not-a-sha256")
        out.flush()

        MessageSerializer.deserialize(baos.toByteArray())
    }
}

