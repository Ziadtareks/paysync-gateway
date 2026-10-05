package com.paysync.gateway.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CapturedSmsDao {
    @Insert
    suspend fun insert(sms: CapturedSms): Long

    @Query("UPDATE captured_sms SET matched = 1, verifyId = :verifyId WHERE id = :id")
    suspend fun markMatched(id: Long, verifyId: String)

    @Query("SELECT * FROM captured_sms WHERE id = :id")
    suspend fun getById(id: Long): CapturedSms?

    /** Late-match candidates: SMS that confirmed nothing yet, newest window only. */
    @Query("SELECT * FROM captured_sms WHERE matched = 0 AND timeMs >= :since ORDER BY timeMs ASC")
    suspend fun unmatchedSince(since: Long): List<CapturedSms>

    @Query("SELECT * FROM captured_sms WHERE matched = 1 ORDER BY timeMs DESC LIMIT :limit")
    fun recentMatchedFlow(limit: Int = 5): Flow<List<CapturedSms>>

    @Query("SELECT * FROM captured_sms ORDER BY timeMs DESC LIMIT :limit")
    fun recentFlow(limit: Int = 20): Flow<List<CapturedSms>>

    @Query("DELETE FROM captured_sms WHERE id NOT IN (SELECT id FROM captured_sms ORDER BY timeMs DESC LIMIT 100)")
    suspend fun prune()

    @Query("DELETE FROM captured_sms")
    suspend fun clear()
}
