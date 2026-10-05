package com.paysync.gateway.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Terminal dispatch outcomes and review notices — feeds the dashboard Live
 * Log. Written via GatewayRepository.logDispatch (by DispatchWorker for
 * sent/failed dispatches, by the matcher for review/ambiguous SMS).
 */
@Entity(tableName = "dispatch_logs")
data class DispatchLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val verifyId: String,
    /**
     * confirmed | timeout (+ " (failed)" suffix when dead-lettered unsent),
     * or review | ambiguous for SMS that need a person (nothing was sent).
     */
    val status: String,
    val detail: String,
    val timeMs: Long = System.currentTimeMillis()
) {
    val isConfirmed: Boolean get() = status == "confirmed"
}
