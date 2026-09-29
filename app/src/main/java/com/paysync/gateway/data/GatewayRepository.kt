package com.paysync.gateway.data

import android.content.Context
import com.paysync.gateway.util.AppLog
import com.google.gson.Gson
import com.paysync.gateway.data.DispatchLog
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
 *    on match, enqueue the confirmed dispatch FIRST (idempotency key born at
 *    match time), then mark MATCHED + delete, so a crash can never lose a
 *    payment and a twin SMS can never double-match.
 *  - [pollPending]: GET /transactions/pending -> upsert Room -> sweep expired
 *    into timeout dispatches. Never throws when offline; returns silently.
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
        val match: MatchOutcome = VerifyMatcher.withMatchLock {
            val live = db.pendingVerifyDao().liveOnce()
            val hit = VerifyMatcher.findMatch(
                parsed, live,
                allowAmountFallback = settings.amountFallbackEnabled
            )
            when (hit) {
                is VerifyMatcher.MatchResult.Match -> {
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
                        enqueueDispatch(payload)
                        // Claim instantly: any twin SMS waiting on the Mutex
                        // now sees PENDING-only liveOnce() without this row.
                        db.pendingVerifyDao().markMatched(hit.verify.verifyId)
                        db.pendingVerifyDao().deleteById(hit.verify.verifyId)
                        MatchOutcome.Confirmed(hit.verify)
                    }
                }
                is VerifyMatcher.MatchResult.Ambiguous ->
                    MatchOutcome.Ambiguous(hit.reason, hit.candidates)
                VerifyMatcher.MatchResult.NoMatch -> MatchOutcome.NoMatch
            }
        }
        when (match) {
            is MatchOutcome.Confirmed -> {
                db.capturedSmsDao().markMatched(smsId, match.verify.verifyId)
                settings.touchHeartbeat()
                AppLog.i(TAG, "Matched ${match.verify.verifyId} via ${parsed.referenceId ?: parsed.amount}")
                DispatchWorker.enqueueDrain(appContext)
            }
            is MatchOutcome.OverCap -> {
                // Needs manual review: logged, deposit stays pending and will
                // follow its normal timeout (no confirmed dispatch sent).
                logDispatch(
                    match.verify.verifyId, "review",
                    "Amount above auto-confirm cap: ${"%.2f".format(parsed.amount)} EGP • " +
                        "${parsed.provider} • ref ${parsed.referenceId ?: "—"}"
                )
                AppLog.w(TAG, "Verify ${match.verify.verifyId} above auto-confirm cap — manual review")
            }
            is MatchOutcome.Ambiguous -> {
                logDispatch(
                    "—", "ambiguous",
                    "${match.candidates} deposits match ${parsed.provider} " +
                        "${"%.2f".format(parsed.amount)} EGP — not confirmed"
                )
                AppLog.w(TAG, "Ambiguous match (${match.reason}, ${match.candidates} candidates) — not confirmed")
            }
            MatchOutcome.NoMatch ->
                AppLog.d(TAG, "No pending verify matches ${parsed.type} ${parsed.amount}")
        }
    }

    /** What [onSmsParsed] decided for one SMS, resolved inside the match lock. */
    private sealed interface MatchOutcome {
        data class Confirmed(val verify: PendingVerify) : MatchOutcome
        data class OverCap(val verify: PendingVerify) : MatchOutcome
        data class Ambiguous(val reason: String, val candidates: Int) : MatchOutcome
        object NoMatch : MatchOutcome
    }

    // ---------- Poll side ----------

    /** Fetches pending requests and sweeps expired ones. Safe to call often. */
    suspend fun pollPending() {
        if (!settings.isConfigured()) return
        if (!api.isOnline(appContext)) return // offline: queue silently, never crash
        try {
            val dtos = api.fetchPending()
            if (dtos.isNotEmpty()) {
                val now = System.currentTimeMillis()
                db.pendingVerifyDao().upsertAll(
                    dtos.map {
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
                )
            }
            settings.touchHeartbeat()
        } catch (e: Exception) {
            AppLog.w(TAG, "pollPending fetch failed: ${e.message}")
            throw e // let PollingWorker decide retry vs failure
        } finally {
            sweepExpired()
        }
    }

    /** Expired requests become timeout dispatches (spec: status="timeout"). */
    suspend fun sweepExpired() {
        val now = System.currentTimeMillis()
        val expired = db.pendingVerifyDao().expiredOnce(now)
        if (expired.isNotEmpty()) {
            for (p in expired) {
                db.pendingVerifyDao().deleteById(p.verifyId)
                // Crash-recovery guard: a confirmed row may already exist.
                if (db.dispatchQueueDao().countForVerify(p.verifyId) == 0) {
                    enqueueDispatch(DispatchPayload(verifyId = p.verifyId, status = "timeout"))
                }
                AppLog.i(TAG, "Verify ${p.verifyId} timed out")
            }
            DispatchWorker.enqueueDrain(appContext)
        }
        db.pendingVerifyDao().deleteStaleMatched(now)
    }

    // ---------- Dispatch outbox ----------

    /**
     * Persists the payload with attempts = 0 (PENDING queue state = row present
     * with attempts < 6). The Idempotency-Key UUID is minted here, at match
     * time, so server-side retries after a lost ACK are safely ignored.
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
    }
}
