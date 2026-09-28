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

    /** Claimed instantly inside the match Mutex so no second SMS can match it. */
    @Query("UPDATE pending_verify SET status = 'MATCHED' WHERE verifyId = :verifyId")
    suspend fun markMatched(verifyId: String)

    @Query("DELETE FROM pending_verify WHERE verifyId = :verifyId")
    suspend fun deleteById(verifyId: String)

    @Query("SELECT * FROM pending_verify WHERE status = 'PENDING' AND createdAt + timeoutMs < :now ORDER BY createdAt ASC")
    suspend fun expiredOnce(now: Long): List<PendingVerify>

    /** Crash leftovers: MATCHED rows past their timeout are removed (queue drain already owns them). */
    @Query("DELETE FROM pending_verify WHERE status = 'MATCHED' AND createdAt + timeoutMs < :cutoff")
    suspend fun deleteStaleMatched(cutoff: Long)

    /** Dashboard "Pending Verifications": active, unclaimed intents only. */
    @Query("SELECT COUNT(*) FROM pending_verify WHERE status = 'PENDING'")
    fun countFlow(): Flow<Int>
}
