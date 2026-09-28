package com.paysync.gateway.data

import com.google.gson.annotations.SerializedName

/**
 * Result of parsing one payment SMS. All amounts are EGP.
 */
data class ParsedTransaction(
    /** Canonical allow-list entry that matched the originating address. */
    val provider: String,
    /** vodafone_cash | instapay_nbe | generic_bank */
    val type: String,
    val amount: Double,
    val currency: String = "EGP",
    val senderPhone: String? = null,
    val senderName: String? = null,
    val referenceId: String? = null,
    /** Full original SMS text — always kept so the server can re-parse. */
    val rawBody: String,
    val timestamp: Long = System.currentTimeMillis()
)

/** One pending verification request from GET /transactions/pending. */
data class PendingVerifyDto(
    @SerializedName("verify_id") val verifyId: String,
    @SerializedName("expected_amount") val expectedAmount: Double,
    @SerializedName("provider") val provider: String,
    @SerializedName("reference_id_hint") val referenceIdHint: String? = null,
    /** Per-request timeout override (ms). Null = app default (120s). */
    @SerializedName("timeout_ms") val timeoutMs: Long? = null
)

/** Body of POST /transactions/dispatch. */
data class DispatchPayload(
    @SerializedName("verify_id") val verifyId: String,
    /** confirmed | timeout */
    @SerializedName("status") val status: String,
    @SerializedName("amount") val amount: Double? = null,
    @SerializedName("provider") val provider: String? = null,
    @SerializedName("reference_id") val referenceId: String? = null
)
