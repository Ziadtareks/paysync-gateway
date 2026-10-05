package com.paysync.gateway.data

import android.content.Context
import com.paysync.gateway.util.AppLog
import androidx.room.withTransaction
import com.google.gson.Gson
import com.paysync.gateway.data.db.AppDatabase
import com.paysync.gateway.data.db.CapturedSms
import com.paysync.gateway.data.db.DispatchQueueItem
import com.paysync.gateway.data.db.PendingVerify
import com.paysync.gateway.domain.VerifyMatcher
import com.paysync.gateway.work.DispatchWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Single entry point for the verification flow (manual DI, no Hilt/Koin).
 *
 *  - [onSmsParsed]: store SMS -> Mutex-guarded match against live verifies ->
 *    on match, ONE Room transaction enqueues the confirmed dispatch
 *    (idempotency key born at match time), marks the verify MATCHED and the
 *    SMS consumed — a crash can never lose a payment and a twin SMS can never
 *    double-match. The MATCHED row stays as a tombstone so a re-poll cannot
 *    resurrect the deposit before the backend has received our dispatch.
 *  - [pollPending]: GET /transactions/pending -> insert new verifies ->
 *    late-match recent unmatched SMS against the NEW verifies (customer paid
 *    before the deposit reached the phone) -> sweep expired into timeout
 *    dispatches. Never throws when offline; returns [PollResult.OFFLINE].
 *
 * Owns a bounded [SupervisorJob] scope — zero GlobalScope in production.
 */
class GatewayRepository(
    private val appContext: Context,
    private val db: AppDatabase,
    private val settings: SettingsManager,
    private val api: ApiClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()

    val pendingCount: Flow<Int> = db.pendingVerifyDao().countFlow()
    val queueDepth: Flow<Int> = db.dispatchQueueDao().countFlow()
    val recentSms: Flow<List<CapturedSms>> = db.capturedSmsDao().recentFlow(20)
    /** Last 5 terminal dispatch outcomes (confirmed / timeout) for the Live Log. */
    val recentDispatches = db.dispatchLogDao().recentFlow(5)

    /** What one poll actually did — callers must not report "polled" unless [OK]. */
    enum class PollResult { OK, NOT_CONFIGURED, OFFLINE }

    // ---------- SMS side ----------

    /** Fire-and-forget entry for SmsReceiver (which already owns its scope). */
    fun handleParsedAsync(provider: String, parsed: ParsedTransaction) {
        scope.launch { onSmsParsed(provider, parsed) }
    }

    suspend fun onSmsParsed(provider: String, parsed: ParsedTransaction) {
        val smsId = db.capturedSmsDao().insert(
            CapturedSms(
                provider = provider,
                type = parsed.type,
                amount = parsed.amount,
                referenceId = parsed.referenceId,
                senderName = parsed.senderName,
                rawBody = parsed.rawBody,
                timeMs = parsed.timestamp
            )
        )
        db.capturedSmsDao().prune()

        // Only one transaction is matched and dispatched at a time.
        val outcome = VerifyMatcher.withMatchLock {
            matchAndClaimLocked(smsId, provider, parsed, onlyVerifyIds = null)
        }
        report(outcome, parsed)
    }

    /**
     * MUST run inside [VerifyMatcher.withMatchLock]. Matches [parsed] against
     * every live verify; with [onlyVerifyIds] set, a confirmation is accepted
     * only for one of those ids (late matching against newly polled
     * deposits — ambiguity is still judged against ALL live deposits), and
     * with [requireReference] only an exact reference match confirms.
     */
    private suspend fun matchAndClaimLocked(
        smsId: Long,
        provider: String,
        parsed: ParsedTransaction,
        onlyVerifyIds: Set<String>?,
        requireReference: Boolean = false
    ): MatchOutcome {
        val live = db.pendingVerifyDao().liveOnce()
        val hit = VerifyMatcher.findMatch(
            parsed, live,
            allowAmountFallback = settings.amountFallbackEnabled
        )
        return when (hit) {
            is VerifyMatcher.MatchResult.Match -> {
                if (onlyVerifyIds != null && hit.verify.verifyId !in onlyVerifyIds) {
                    return MatchOutcome.NoMatch
                }
                if (requireReference && !hit.byReference) return MatchOutcome.NoMatch
                // Safety cap: over-limit matches are never auto-confirmed —
                // they stay pending and follow their timeout for review.
                val cap = settings.maxAutoConfirmAmountEgp
                if (cap > 0.0 && parsed.amount > cap) {
                    MatchOutcome.OverCap(hit.verify)
                } else {
                    val payload = DispatchPayload(
                        verifyId = hit.verify.verifyId,
                        status = "confirmed",
                        amount = parsed.amount,
                        provider = provider,
                        referenceId = parsed.referenceId
                    )
                    db.withTransaction {
                        enqueueDispatch(payload)
                        // Claim instantly: any twin SMS waiting on the Mutex
                        // now sees PENDING-only liveOnce() without this row.
                        db.pendingVerifyDao().markMatched(hit.verify.verifyId)
                        db.capturedSmsDao().markMatched(smsId, hit.verify.verifyId)
                    }
                    MatchOutcome.Confirmed(hit.verify)
                }
            }
            is VerifyMatcher.MatchResult.AmountMismatch ->
                MatchOutcome.AmountMismatch(hit.verify, hit.expected, hit.received)
            is VerifyMatcher.MatchResult.Ambiguous ->
                MatchOutcome.Ambiguous(hit.reason, hit.candidates)
            VerifyMatcher.MatchResult.NoMatch -> MatchOutcome.NoMatch
        }
    }

    private suspend fun report(outcome: MatchOutcome, parsed: ParsedTransaction) {
        when (outcome) {
            is MatchOutcome.Confirmed -> {
                settings.touchHeartbeat()
                AppLog.i(TAG, "Matched ${outcome.verify.verifyId} via ${parsed.referenceId ?: parsed.amount}")
                DispatchWorker.enqueueDrain(appContext)
            }
            is MatchOutcome.OverCap -> {
                // Needs manual review: logged, deposit stays pending and will
                // follow its normal timeout (no confirmed dispatch sent).
                logDispatch(
                    outcome.verify.verifyId, "review",
                    "Amount above auto-confirm cap: ${"%.2f".format(parsed.amount)} EGP • " +
                        "${parsed.provider} • ref ${parsed.referenceId ?: "—"}"
                )
                AppLog.w(TAG, "Verify ${outcome.verify.verifyId} above auto-confirm cap — manual review")
            }
            is MatchOutcome.AmountMismatch -> {
                // The reference points at this deposit but the money does not
                // add up — never confirmed; it follows its timeout for review.
                logDispatch(
                    outcome.verify.verifyId, "review",
                    "Reference matches but amount ${"%.2f".format(outcome.received)} EGP ≠ expected " +
                        "${"%.2f".format(outcome.expected)} EGP • ref ${parsed.referenceId ?: "—"} — not confirmed"
                )
                AppLog.w(TAG, "Verify ${outcome.verify.verifyId}: reference matches, amount differs — not confirmed")
            }
            is MatchOutcome.Ambiguous -> {
                logDispatch(
                    "—", "ambiguous",
                    "${outcome.candidates} deposits match ${parsed.provider} " +
                        "${"%.2f".format(parsed.amount)} EGP — not confirmed"
                )
                AppLog.w(TAG, "Ambiguous match (${outcome.reason}, ${outcome.candidates} candidates) — not confirmed")
            }
            MatchOutcome.NoMatch ->
                AppLog.d(TAG, "No pending verify matches ${parsed.type} ${parsed.amount}")
        }
    }

    /** What one match attempt decided, resolved inside the match lock. */
    private sealed interface MatchOutcome {
        data class Confirmed(val verify: PendingVerify) : MatchOutcome
        data class OverCap(val verify: PendingVerify) : MatchOutcome
        data class AmountMismatch(val verify: PendingVerify, val expected: Double, val received: Double) : MatchOutcome
        data class Ambiguous(val reason: String, val candidates: Int) : MatchOutcome
        object NoMatch : MatchOutcome
    }

    // ---------- Poll side ----------

    /** Fetches pending requests and sweeps expired ones. Safe to call often. */
    suspend fun pollPending(): PollResult {
        if (!settings.isConfigured()) return PollResult.NOT_CONFIGURED
        if (!api.isOnline(appContext)) {
            // Offline: queue silently, never crash — but expiry still runs.
            sweepExpired()
            return PollResult.OFFLINE
        }
        try {
            val dtos = api.fetchPending()
            val now = System.currentTimeMillis()
            val newIds = HashSet<String>()
            if (dtos.isNotEmpty()) {
                val rows = dtos.map {
                    PendingVerify(
                        verifyId = it.verifyId,
                        expectedAmount = it.expectedAmount,
                        provider = it.provider,
                        referenceIdHint = it.referenceIdHint,
                        createdAt = now,
                        timeoutMs = it.timeoutMs?.coerceIn(15_000L, 900_000L)
                            ?: settings.verifyTimeoutMs
                    )
                }
                // IGNORE: -1L = already known (live or tombstone), keeps createdAt.
                val rowIds = db.pendingVerifyDao().upsertAll(rows)
                rows.forEachIndexed { i, row -> if (rowIds.getOrNull(i) != -1L) newIds += row.verifyId }
            }
            settings.touchHeartbeat()
            settings.lastPollMs = now
            if (newIds.isNotEmpty()) lateMatch(newIds, now)
            return PollResult.OK
        } catch (e: Exception) {
            AppLog.w(TAG, "pollPending fetch failed: ${e.message}")
            throw e // let PollingWorker decide retry vs failure
        } finally {
            sweepExpired()
        }
    }

    /**
     * The SMS arrived BEFORE the deposit reached the phone (poll lag, or the
     * customer paid first): it found no match on arrival. Retry recent
     * unmatched SMS against the deposits this poll just inserted.
     *
     * Money safety: an amount-only match is accepted only for SMS younger
     * than one poll interval + [LATE_AMOUNT_GRACE_MS] (pure poll lag) — an
     * older unmatched SMS (e.g. an underpayment for another order) must not
     * be credited to a new customer's deposit of the same amount. An exact
     * reference (+ amount) match may reach back [LATE_MATCH_WINDOW_MS].
     * Only a clean confirmation acts; an amount mismatch on a new deposit is
     * logged once for review; other outcomes are not re-logged every poll.
     */
    private suspend fun lateMatch(newIds: Set<String>, now: Long) {
        val candidates = db.capturedSmsDao().unmatchedSince(now - LATE_MATCH_WINDOW_MS)
        if (candidates.isEmpty()) return
        val amountOnlySince = now - settings.pollingIntervalMs - LATE_AMOUNT_GRACE_MS
        val remaining = newIds.toMutableSet()
        for (sms in candidates) {
            if (remaining.isEmpty()) break
            val outcome = VerifyMatcher.withMatchLock {
                val fresh = db.capturedSmsDao().getById(sms.id)
                if (fresh == null || fresh.matched) return@withMatchLock MatchOutcome.NoMatch
                val parsed = ParsedTransaction(
                    provider = fresh.provider,
                    type = fresh.type,
                    amount = fresh.amount,
                    senderName = fresh.senderName,
                    referenceId = fresh.referenceId,
                    rawBody = fresh.rawBody,
                    timestamp = fresh.timeMs
                )
                matchAndClaimLocked(
                    fresh.id, fresh.provider, parsed,
                    onlyVerifyIds = remaining,
                    requireReference = fresh.timeMs < amountOnlySince
                )
            }
            when {
                outcome is MatchOutcome.Confirmed -> {
                    remaining -= outcome.verify.verifyId
                    AppLog.i(TAG, "Late-matched ${outcome.verify.verifyId} to an earlier SMS")
                    settings.touchHeartbeat()
                    DispatchWorker.enqueueDrain(appContext)
                }
                outcome is MatchOutcome.AmountMismatch && outcome.verify.verifyId in remaining ->
                    report(outcome, ParsedTransaction(
                        provider = sms.provider, type = sms.type, amount = sms.amount,
                        referenceId = sms.referenceId, rawBody = sms.rawBody, timestamp = sms.timeMs
                    ))
            }
        }
    }

    /** Expired requests become timeout dispatches (spec: status="timeout"). */
    suspend fun sweepExpired() {
        val now = System.currentTimeMillis()
        // Under the match lock: an SMS can never confirm a deposit while the
        // sweep is turning that same deposit into a timeout.
        val timedOut = VerifyMatcher.withMatchLock {
            val expired = db.pendingVerifyDao().expiredOnce(now)
            for (p in expired) {
                db.withTransaction {
                    // Crash-recovery guard: a confirmed row may already exist.
                    if (db.dispatchQueueDao().countForVerify(p.verifyId) == 0) {
                        enqueueDispatch(DispatchPayload(verifyId = p.verifyId, status = "timeout"))
                    }
                    // Tombstone, not delete: a re-poll must not resurrect it.
                    db.pendingVerifyDao().markTimedOut(p.verifyId)
                }
                AppLog.i(TAG, "Verify ${p.verifyId} timed out")
            }
            expired.isNotEmpty()
        }
        if (timedOut) DispatchWorker.enqueueDrain(appContext)
        db.pendingVerifyDao().deleteStaleFinalized(now - TOMBSTONE_GRACE_MS)
    }

    // ---------- Dispatch outbox ----------

    /**
     * Persists the payload with attempts = 0 (queued = row present; it stays
     * until delivered or permanently rejected). The Idempotency-Key UUID is
     * minted here, at match time, so server-side retries after a lost ACK
     * are safely ignored.
     */
    suspend fun enqueueDispatch(payload: DispatchPayload): Long {
        return db.dispatchQueueDao().insert(
            DispatchQueueItem(
                verifyId = payload.verifyId,
                status = payload.status,
                payloadJson = gson.toJson(payload),
                idempotencyKey = UUID.randomUUID().toString()
            )
        )
    }

    fun payloadFrom(item: DispatchQueueItem): DispatchPayload? = try {
        gson.fromJson(item.payloadJson, DispatchPayload::class.java)
    } catch (_: Exception) {
        null
    }

    /** Called by DispatchWorker on terminal outcome. Prunes to the last 50. */
    suspend fun logDispatch(verifyId: String, status: String, detail: String) {
        db.dispatchLogDao().insert(
            DispatchLog(
                verifyId = verifyId,
                status = status,
                detail = detail
            )
        )
        db.dispatchLogDao().prune()
    }

    suspend fun clearLogs() {
        db.capturedSmsDao().clear()
        db.dispatchLogDao().clear()
    }

    companion object {
        private const val TAG = "GatewayRepo"

        /** How far back an unmatched SMS may be late-matched by exact reference (max verify timeout). */
        const val LATE_MATCH_WINDOW_MS = 15L * 60_000L

        /** Amount-only late matching covers poll lag only: one poll interval + this grace. */
        const val LATE_AMOUNT_GRACE_MS = 60_000L

        /** Finalized rows outlive their timeout by this much before cleanup. */
        const val TOMBSTONE_GRACE_MS = 24L * 60L * 60_000L
    }
}
