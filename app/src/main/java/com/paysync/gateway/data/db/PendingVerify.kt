package com.paysync.gateway.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A verification request from GET /transactions/pending awaiting an SMS. */
@Entity(tableName = "pending_verify")
data class PendingVerify(
    @PrimaryKey val verifyId: String,
    val expectedAmount: Double,
    val provider: String,
    val referenceIdHint: String?,
    val createdAt: Long,
    /** Effective timeout (per-request override or app default). */
    val timeoutMs: Long,
    /**
     * PENDING = matchable intent. MATCHED = claimed inside the match Mutex.
     * TIMED_OUT = timeout dispatched. Non-PENDING rows are tombstones.
     */
    val status: String = STATUS_PENDING
) {
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        now - createdAt >= timeoutMs

    fun isLive(now: Long = System.currentTimeMillis()): Boolean =
        status == STATUS_PENDING && !isExpired(now)

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_MATCHED = "MATCHED"
        const val STATUS_TIMED_OUT = "TIMED_OUT"
    }
}
