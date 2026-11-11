package com.example.myapplication.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transfer: TransferEntity): Long

    @Update
    suspend fun update(transfer: TransferEntity): Int

    @Query("SELECT * FROM transfers WHERE sessionId = :sessionId")
    suspend fun getTransfersForSession(sessionId: String): List<TransferEntity>

    @Query("SELECT * FROM transfers WHERE fileId = :fileId")
    suspend fun getTransferById(fileId: String): TransferEntity?
    
    @Query("UPDATE transfers SET status = :status WHERE fileId = :fileId")
    suspend fun updateStatus(fileId: String, status: String): Int

    @Query("UPDATE transfers SET status = :status, errorMessage = :errorMessage WHERE fileId = :fileId")
    suspend fun updateFailure(fileId: String, status: String, errorMessage: String?): Int

    @Query("UPDATE transfers SET status = :status, fileUri = :fileUri, fileName = :fileName, tempFilePath = NULL, errorMessage = NULL WHERE fileId = :fileId")
    suspend fun updateCompletion(fileId: String, status: String, fileUri: String?, fileName: String): Int

    @Query("UPDATE transfers SET transferredBytes = :bytes WHERE fileId = :fileId")
    suspend fun updateProgress(fileId: String, bytes: Long): Int

    @Query("UPDATE transfers SET transferredBytes = :bytes, verifiedRanges = :verifiedRanges, status = :status WHERE fileId = :fileId")
    suspend fun updateChunkProgress(fileId: String, bytes: Long, verifiedRanges: String, status: String): Int

    @Query("UPDATE transfers SET status = 'INTERRUPTED', errorMessage = 'Transfer interrupted before completion' WHERE status IN ('PENDING', 'IN_PROGRESS')")
    suspend fun markOrphanedTransfersInterrupted(): Int

    @Query("SELECT tempFilePath FROM transfers WHERE tempFilePath IS NOT NULL AND status IN ('PENDING', 'IN_PROGRESS', 'PAUSED', 'INTERRUPTED', 'FAILED')")
    suspend fun getResumableTempPaths(): List<String>

    @Query("SELECT * FROM transfers ORDER BY timestamp DESC, sessionId DESC")
    suspend fun getAllTransfers(): List<TransferEntity>

    @Query("SELECT * FROM transfers ORDER BY timestamp DESC, sessionId DESC")
    fun observeAllTransfers(): Flow<List<TransferEntity>>

    @Query("DELETE FROM transfers WHERE status NOT IN ('PENDING', 'IN_PROGRESS')")
    suspend fun deleteNonActiveHistory(): Int

    @Query("DELETE FROM transfers WHERE fileId = :fileId")
    suspend fun deleteById(fileId: String): Int
}
