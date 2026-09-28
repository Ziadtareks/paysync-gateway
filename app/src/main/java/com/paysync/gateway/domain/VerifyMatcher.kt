package com.paysync.gateway.domain

import com.paysync.gateway.data.ParsedTransaction
import com.paysync.gateway.data.db.PendingVerify
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Matches a parsed SMS against pending verification requests.
 *
 * Priority 1 — exact match on reference_id (digit-normalized comparison).
 * Priority 2 — provider matches AND |expected - parsed| <= 0.01 EGP.
 * Expired requests (created_at + timeout < now) never match; the polling
 * path sweeps them into timeout dispatches instead.
 *
 * [findMatch] stays pure Kotlin for unit tests. Concurrency is enforced by
 * [withMatchLock]: the repository wraps the whole read→claim→enqueue block
 * so two simultaneous SMS can never match the same pending intent.
 */
object VerifyMatcher {

    const val AMOUNT_TOLERANCE_EGP = 0.01

    /** Single gateway-wide match lock. Never held across network I/O. */
    private val matchMutex = Mutex()

    suspend fun <T> withMatchLock(block: suspend () -> T): T =
        matchMutex.withLock { block() }

    fun findMatch(parsed: ParsedTransaction, pending: List<PendingVerify>, now: Long = System.currentTimeMillis()): PendingVerify? {
        val live = pending.filterNot { it.isExpired(now) }
        if (live.isEmpty()) return null

        val ref = parsed.referenceId?.let(::digitsOnly).orEmpty()
        if (ref.isNotEmpty()) {
            live.firstOrNull { it.referenceIdHint?.let(::digitsOnly) == ref }?.let { return it }
        }
        return live.firstOrNull { sameProvider(it.provider, parsed.provider) && abs(it.expectedAmount - parsed.amount) <= AMOUNT_TOLERANCE_EGP }
    }

    fun digitsOnly(s: String): String = s.filter(Char::isDigit)

    fun sameProvider(a: String, b: String): Boolean = canonical(a) == canonical(b)

    /**
     * Canonical provider groups so backend names ("vodafone_cash") match
     * sender IDs ("VF-Cash") and vice versa.
     */
    fun canonical(p: String): String {
        val t = p.trim().lowercase()
        return when {
            t.contains("vodafone") || t == "vf" || t == "vf-cash" || t == "vf_cash" -> "vodafone_cash"
            t.contains("alahly") || t.contains("ahly") || t == "nbe" || t.contains("instapay") -> "instapay_nbe"
            t == "bm" || t.contains("misr") -> "banque_misr"
            t.contains("cib") -> "cib"
            t.contains("orange") -> "orange_cash"
            t.contains("etisalat") -> "etisalat_cash"
            else -> t.filter { it.isLetterOrDigit() }
        }
    }
}
