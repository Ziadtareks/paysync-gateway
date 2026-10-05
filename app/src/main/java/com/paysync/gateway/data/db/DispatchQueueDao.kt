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

    /**
     * Every queued row is due: rows are never parked by attempt count — a
     * retryable failure keeps the row until it is delivered (or the server
     * permanently rejects it). [DispatchQueueItem.attempts] is informational.
     */
    @Query("SELECT * FROM dispatch_queue ORDER BY id ASC LIMIT :limit")
    suspend fun dueOnce(limit: Int = 20): List<DispatchQueueItem>

    /** Keyset pagination for one drain pass: rows with id > [afterId], oldest first. */
    @Query("SELECT * FROM dispatch_queue WHERE id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun dueAfter(afterId: Long, limit: Int = 20): List<DispatchQueueItem>

    /** Crash-recovery guard: never enqueue a timeout if a row already exists. */
    @Query("SELECT COUNT(*) FROM dispatch_queue WHERE verifyId = :verifyId")
    suspend fun countForVerify(verifyId: String): Int

    @Query("DELETE FROM dispatch_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE dispatch_queue SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun bumpAttempt(id: Long, error: String?)

    @Query("SELECT COUNT(*) FROM dispatch_queue")
    suspend fun countOnce(): Int

    @Query("SELECT COUNT(*) FROM dispatch_queue")
    fun countFlow(): Flow<Int>
}
