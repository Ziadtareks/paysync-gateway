package com.paysync.gateway.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Terminal dispatch outcomes — feeds the dashboard Live Log.
 * Rows are written by DispatchWorker only (single writer, no races).
 */
@Entity(tableName = "dispatch_logs")
data class DispatchLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val verifyId: String,
    /** confirmed | timeout (+ " (failed)" suffix when dead-lettered unsent). */
    val status: String,
    val detail: String,
    val timeMs: Long = System.currentTimeMillis()
) {
    val isConfirmed: Boolean get() = status == "confirmed"
}
