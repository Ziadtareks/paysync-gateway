package com.paysync.gateway.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Outbox row for POST /transactions/dispatch.
 * [idempotencyKey] is generated once at enqueue time and sent as the
 * Idempotency-Key header so the bot can dedupe retries.
 */
@Entity(tableName = "dispatch_queue")
data class DispatchQueueItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val verifyId: String,
    /** confirmed | timeout */
    val status: String,
    val payloadJson: String,
    val idempotencyKey: String,
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastError: String? = null
)
