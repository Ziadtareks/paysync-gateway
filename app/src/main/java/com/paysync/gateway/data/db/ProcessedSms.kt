package com.paysync.gateway.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Dedup ledger for inbound SMS. [hash] is SHA-256(sender + body + timestamp).
 * Rows older than the dedup window are pruned by SmsReceiver so the table
 * stays bounded. Primary-key insert with IGNORE makes the claim atomic.
 */
@Entity(tableName = "processed_sms")
data class ProcessedSms(
    @PrimaryKey val hash: String,
    val receivedAt: Long = System.currentTimeMillis()
)
