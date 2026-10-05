package com.paysync.gateway.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingVerifyDao {
    /** IGNORE keeps the original createdAt so timeouts don't slide on re-poll. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertAll(items: List<PendingVerify>): List<Long>

    @Query("SELECT * FROM pending_verify ORDER BY createdAt ASC")
    suspend fun allOnce(): List<PendingVerify>

    /** Only unclaimed intents — the match path must use this, never allOnce(). */
    @Query("SELECT * FROM pending_verify WHERE status = 'PENDING' ORDER BY createdAt ASC")
    suspend fun liveOnce(): List<PendingVerify>

    /**
     * Claimed instantly inside the match Mutex so no second SMS can match it.
     * The row is KEPT as a tombstone (not deleted): while the backend keeps
     * listing the deposit until our dispatch lands, the IGNORE upsert above
     * cannot re-insert it as a fresh PENDING intent.
     */
    @Query("UPDATE pending_verify SET status = 'MATCHED' WHERE verifyId = :verifyId")
    suspend fun markMatched(verifyId: String)

    /** Timeout dispatched — kept as a tombstone for the same reason as [markMatched]. */
    @Query("UPDATE pending_verify SET status = 'TIMED_OUT' WHERE verifyId = :verifyId AND status = 'PENDING'")
    suspend fun markTimedOut(verifyId: String)

    @Query("DELETE FROM pending_verify WHERE verifyId = :verifyId")
    suspend fun deleteById(verifyId: String)

    @Query("SELECT * FROM pending_verify WHERE status = 'PENDING' AND createdAt + timeoutMs < :now ORDER BY createdAt ASC")
    suspend fun expiredOnce(now: Long): List<PendingVerify>

    /**
     * Tombstone cleanup: finalized rows (MATCHED / TIMED_OUT) past their
     * timeout + grace, and only once their dispatch has left the outbox —
     * a tombstone whose dispatch is still being retried must stay, or the
     * next poll would resurrect the deposit as PENDING.
     */
    @Query(
        "DELETE FROM pending_verify WHERE status != 'PENDING' AND createdAt + timeoutMs < :cutoff " +
            "AND verifyId NOT IN (SELECT verifyId FROM dispatch_queue)"
    )
    suspend fun deleteStaleFinalized(cutoff: Long)

    /** Dashboard "Pending Verifications": active, unclaimed intents only. */
    @Query("SELECT COUNT(*) FROM pending_verify WHERE status = 'PENDING'")
    fun countFlow(): Flow<Int>
}
