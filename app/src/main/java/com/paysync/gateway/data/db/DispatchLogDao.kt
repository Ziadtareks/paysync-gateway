package com.paysync.gateway.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.paysync.gateway.data.DispatchLog
import kotlinx.coroutines.flow.Flow

@Dao
interface DispatchLogDao {
    @Insert
    suspend fun insert(log: DispatchLog)

    @Query("SELECT * FROM dispatch_logs ORDER BY timeMs DESC LIMIT :limit")
    fun recentFlow(limit: Int = 5): Flow<List<DispatchLog>>

    @Query("DELETE FROM dispatch_logs WHERE id NOT IN (SELECT id FROM dispatch_logs ORDER BY timeMs DESC LIMIT 50)")
    suspend fun prune()

    @Query("DELETE FROM dispatch_logs")
    suspend fun clear()
}
