package com.paysync.gateway.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProcessedSmsDao {
    /**
     * Atomic claim. Returns rowId, or -1L if [item.hash] was already seen
     * (a duplicate broadcast lost the race — caller must abort).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(item: ProcessedSms): Long

    @Query("SELECT EXISTS(SELECT 1 FROM processed_sms WHERE hash = :hash)")
    suspend fun exists(hash: String): Boolean

    @Query("DELETE FROM processed_sms WHERE receivedAt < :cutoff")
    suspend fun pruneOlderThan(cutoff: Long)

    @Query("DELETE FROM processed_sms")
    suspend fun clear()
}
