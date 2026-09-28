package com.paysync.gateway.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Every parsed payment SMS, matched or not — feeds the dashboard live log. */
@Entity(tableName = "captured_sms")
data class CapturedSms(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val type: String,
    val amount: Double,
    val referenceId: String?,
    val senderName: String?,
    val rawBody: String,
    val matched: Boolean = false,
    val verifyId: String? = null,
    val timeMs: Long = System.currentTimeMillis()
)
