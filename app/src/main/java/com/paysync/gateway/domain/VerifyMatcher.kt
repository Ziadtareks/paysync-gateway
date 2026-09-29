package com.paysync.gateway.domain

import com.paysync.gateway.data.ParsedTransaction
import com.paysync.gateway.data.db.PendingVerify
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

/**
 * Matches a parsed SMS against pending verification requests.
 *
 * Priority 1 — reference match: exact equality after digit normalization
 *   (Arabic-Indic/Persian → Western, whitespace/punctuation stripped).
 *   Confirms only when EXACTLY ONE live request carries that reference;
 *   2+ duplicates are reported [MatchResult.Ambiguous] and never confirmed.
 * Priority 2 — amount fallback (only when enabled): provider matches AND
 *   |expected − parsed| ≤ 0.01 EGP. Confirms only when EXACTLY ONE candidate
 *   matches; zero candidates is a plain no-match, 2+ is [MatchResult.Ambiguous].
 *   When the fallback is disabled, only reference matching can confirm.
 * Expired requests (created_at + timeout < now) never match; the polling
 * path sweeps them into timeout dispatches instead.
 *
 * [findMatch] stays pure Kotlin for unit tests. Concurrency is enforced by
 * [withMatchLock]: the repository wraps the whole read→claim→enqueue block
 * so two simultaneous SMS can never match the same pending intent, and the
 * claimed row is deleted inside the lock so a twin SMS sees only what is
 * still unclaimed.
 */
object VerifyMatcher {

    const val AMOUNT_TOLERANCE_EGP = 0.01

    /** Single gateway-wide match lock. Never held across network I/O. */
    private val matchMutex = Mutex()

    suspend fun <T> withMatchLock(block: suspend () -> T): T =
        matchMutex.withLock { block() }

    /**
     * Outcome of matching one parsed SMS. [Match] carries the single
     * unambiguous deposit to confirm; every other outcome must NOT dispatch
     * "confirmed".
     */
    sealed interface MatchResult {
        data class Match(val verify: PendingVerify, val byReference: Boolean) : MatchResult

        /** Multiple equally plausible deposits — ambiguous, never confirm. */
        data class Ambiguous(val reason: String, val candidates: Int) : MatchResult

        object NoMatch : MatchResult
    }

    fun findMatch(
        parsed: ParsedTransaction,
        pending: List<PendingVerify>,
        now: Long = System.currentTimeMillis(),
        allowAmountFallback: Boolean = true
    ): MatchResult {
        val live = pending.filterNot { it.isExpired(now) }
        if (live.isEmpty()) return MatchResult.NoMatch

        val ref = parsed.referenceId?.let(::normalizedReference).orEmpty()
        if (ref.isNotEmpty()) {
            val byRef = live.filter { it.referenceIdHint?.let(::normalizedReference) == ref }
            return when (byRef.size) {
                1 -> MatchResult.Match(byRef[0], byReference = true)
                0 -> if (allowAmountFallback) amountFallbackMatch(parsed, live) else MatchResult.NoMatch
                else -> MatchResult.Ambiguous("duplicate_reference", byRef.size)
            }
        }
        return if (allowAmountFallback) amountFallbackMatch(parsed, live) else MatchResult.NoMatch
    }

    private fun amountFallbackMatch(parsed: ParsedTransaction, live: List<PendingVerify>): MatchResult {
        val candidates = live.filter {
            sameProvider(it.provider, parsed.provider) &&
                abs(it.expectedAmount - parsed.amount) <= AMOUNT_TOLERANCE_EGP
        }
        return when (candidates.size) {
            1 -> MatchResult.Match(candidates[0], byReference = false)
            0 -> MatchResult.NoMatch
            else -> MatchResult.Ambiguous("duplicate_amount", candidates.size)
        }
    }

    /**
     * Safe reference normalization: Arabic-Indic (٠-٩) and Extended
     * Arabic-Indic/Persian (۰-۹) digits → Western, then only digits are kept
     * (strips whitespace, dashes and any punctuation). Matching itself stays
     * exact — no substring or partial comparison ever happens.
     */
    fun normalizedReference(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when (ch) {
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                else -> if (ch.isDigit()) sb.append(ch)
            }
        }
        return sb.toString()
    }

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
