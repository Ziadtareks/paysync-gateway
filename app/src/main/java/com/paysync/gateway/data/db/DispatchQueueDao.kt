package com.paysync.gateway.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DispatchQueueDao {
    @Insert
    suspend fun insert(item: DispatchQueueItem): Long

    @Query("SELECT * FROM dispatch_queue WHERE id = :id")
    suspend fun getById(id: Long): DispatchQueueItem?

    /** Only rows still eligible for retry (attempts < 6). */
    @Query("SELECT * FROM dispatch_queue WHERE attempts < 6 ORDER BY createdAt ASC LIMIT :limit")
    suspend fun dueOnce(limit: Int = 20): List<DispatchQueueItem>

    /**
     * Offline-queue view: every row present here with attempts < 6 is PENDING
     * (waiting for CONNECTED drain). [DispatchQueueItem.status] carries the
     * outcome (confirmed/timeout); [DispatchQueueItem.attempts] defaults to 0
     * at enqueue time per the offline-queue contract.
     */
    @Query("SELECT * FROM dispatch_queue WHERE attempts < 6 ORDER BY createdAt ASC LIMIT :limit")
    suspend fun pendingOnce(limit: Int = 20): List<DispatchQueueItem>

    /** Crash-recovery guard: never enqueue a timeout if a row already exists. */
    @Query("SELECT COUNT(*) FROM dispatch_queue WHERE verifyId = :verifyId AND attempts < 6")
    suspend fun countForVerify(verifyId: String): Int

    @Query("DELETE FROM dispatch_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE dispatch_queue SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun bumpAttempt(id: Long, error: String?)

    @Query("SELECT COUNT(*) FROM dispatch_queue WHERE attempts < 6")
    fun countFlow(): Flow<Int>
}
