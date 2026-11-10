package com.example.myapplication.domain.transfer

import java.util.BitSet

/**
 * Tracks verified chunk indices for a single file transfer and encodes them as compact
 * inclusive ranges (for example, "0-14,16,18-20") for Room persistence and resume handshakes.
 */
class ChunkRangeTracker(val totalChunks: Int) {

    private val verified = BitSet(totalChunks.coerceAtLeast(0))

    fun markVerified(chunkIndex: Int) {
        if (chunkIndex in 0 until totalChunks) {
            verified.set(chunkIndex)
        }
    }

    fun isVerified(chunkIndex: Int): Boolean {
        return chunkIndex in 0 until totalChunks && verified.get(chunkIndex)
    }

    fun verifiedCount(): Int = verified.cardinality()

    fun isComplete(): Boolean = totalChunks == 0 || verified.cardinality() >= totalChunks

    fun missingIndices(): List<Int> {
        if (totalChunks <= 0) return emptyList()
        val missing = ArrayList<Int>()
        var idx = verified.nextClearBit(0)
        while (idx in 0 until totalChunks) {
            missing.add(idx)
            idx = verified.nextClearBit(idx + 1)
        }
        return missing
    }

    fun nextMissingIndex(fromIndex: Int = 0): Int? {
        if (totalChunks <= 0 || fromIndex >= totalChunks) return null
        val idx = verified.nextClearBit(fromIndex.coerceAtLeast(0))
        return if (idx in 0 until totalChunks) idx else null
    }

    fun verifiedBytes(totalBytes: Long, chunkSize: Int): Long {
        if (totalBytes <= 0L || totalChunks <= 0 || chunkSize <= 0) return 0L
        var bytes = 0L
        var idx = verified.nextSetBit(0)
        while (idx in 0 until totalChunks) {
            bytes += expectedChunkSize(idx, totalBytes, chunkSize)
            idx = verified.nextSetBit(idx + 1)
        }
        return bytes.coerceAtMost(totalBytes)
    }

    /**
     * Discards any chunk whose end byte offset exceeds the physical temporary file size on disk,
     * preventing truncated writes from a crash from being treated as verified.
     */
    fun clampToFileLength(fileLength: Long, totalBytes: Long, chunkSize: Int) {
        if (totalChunks <= 0 || chunkSize <= 0) return
        var idx = verified.nextSetBit(0)
        while (idx in 0 until totalChunks) {
            val chunkEnd = (idx.toLong() * chunkSize) + expectedChunkSize(idx, totalBytes, chunkSize)
            if (chunkEnd > fileLength) {
                verified.clear(idx)
            }
            idx = verified.nextSetBit(idx + 1)
        }
    }

    fun toRangeString(): String {
        if (totalChunks <= 0) return ""
        val sb = StringBuilder()
        var start = verified.nextSetBit(0)
        while (start in 0 until totalChunks) {
            val nextZero = verified.nextClearBit(start)
            val end = (if (nextZero == -1) totalChunks else nextZero.coerceAtMost(totalChunks)) - 1
            if (sb.isNotEmpty()) sb.append(',')
            if (start == end) {
                sb.append(start)
            } else {
                sb.append(start).append('-').append(end)
            }
            start = verified.nextSetBit(end + 1)
        }
        return sb.toString()
    }

    companion object {
        fun computeTotalChunks(totalBytes: Long, chunkSize: Int): Int {
            if (totalBytes <= 0L || chunkSize <= 0) return 0
            return ((totalBytes + chunkSize - 1L) / chunkSize).toInt()
        }

        fun expectedChunkSize(chunkIndex: Int, totalBytes: Long, chunkSize: Int): Int {
            if (totalBytes <= 0L || chunkSize <= 0) return 0
            val offset = chunkIndex.toLong() * chunkSize
            if (offset >= totalBytes) return 0
            return minOf(chunkSize.toLong(), totalBytes - offset).toInt()
        }

        fun parse(raw: String?, totalChunks: Int): ChunkRangeTracker {
            val tracker = ChunkRangeTracker(totalChunks)
            if (raw.isNullOrBlank() || totalChunks <= 0) return tracker

            raw.split(',').forEach { token ->
                val trimmed = token.trim()
                if (trimmed.isEmpty()) return@forEach
                val dashIdx = trimmed.indexOf('-')
                if (dashIdx == -1) {
                    val idx = trimmed.toIntOrNull() ?: return@forEach
                    if (idx in 0 until totalChunks) {
                        tracker.verified.set(idx)
                    }
                } else {
                    val start = trimmed.substring(0, dashIdx).toIntOrNull() ?: return@forEach
                    val end = trimmed.substring(dashIdx + 1).toIntOrNull() ?: return@forEach
                    if (start <= end && end >= 0 && start < totalChunks) {
                        val clampedStart = start.coerceAtLeast(0)
                        val clampedEnd = end.coerceAtMost(totalChunks - 1)
                        tracker.verified.set(clampedStart, clampedEnd + 1)
                    }
                }
            }
            return tracker
        }
    }
}

