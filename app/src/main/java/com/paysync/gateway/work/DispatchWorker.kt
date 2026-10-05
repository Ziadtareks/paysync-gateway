package com.paysync.gateway.work

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.paysync.gateway.util.AppLog
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.paysync.gateway.AppContainer
import com.paysync.gateway.PaySyncApp
import com.paysync.gateway.data.DispatchPayload
import com.paysync.gateway.data.db.DispatchQueueItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Expedited dispatcher for POST /transactions/dispatch.
 *
 * Each outbox row carries its own Idempotency-Key (UUID, generated at enqueue)
 * so bot-side dedupe makes retries safe.
 *
 * Delivery guarantee: a row leaves the outbox only when the server accepts
 * it (2xx) or permanently rejects it (400 / 404 / 409 / 410 / 422 —
 * dead-lettered and logged). Every other failure (IO, timeout, 5xx, 429,
 * 401/403 misconfiguration) keeps the row: in-worker backoff 2s → 32s for up
 * to [MAX_ATTEMPTS_PER_RUN] tries, then the run ends with Result.retry() and
 * WorkManager / the periodic drain / the service loop try again later. A
 * confirmed payment is never dropped because the backend was briefly down.
 *
 * Drains are serialized by [drainMutex] (one-time, periodic and test runs
 * share one process), so the same row is never POSTed twice concurrently.
 */
class DispatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private enum class ItemOutcome { DONE, RETRY_LATER, OFFLINE }

    override suspend fun doWork(): Result {
        val container = (applicationContext as? PaySyncApp)?.container
            ?: return Result.failure()
        return drainMutex.withLock { drain(container) }
    }

    private suspend fun drain(container: AppContainer): Result {
        val dao = container.db.dispatchQueueDao()
        val singleId = inputData.getLong(KEY_DISPATCH_ID, -1L)
        if (singleId != -1L) {
            val item = dao.getById(singleId) ?: return Result.success()
            if (!container.api.isOnline(applicationContext)) return Result.retry()
            return when (send(container, item)) {
                ItemOutcome.DONE -> Result.success()
                ItemOutcome.RETRY_LATER, ItemOutcome.OFFLINE -> Result.retry()
            }
        }

        // Keyset pass over the whole outbox, including rows inserted while
        // this run is in progress (ids only grow).
        var afterId = 0L
        while (true) {
            val batch = dao.dueAfter(afterId, BATCH_SIZE)
            if (batch.isEmpty()) return Result.success()
            if (!container.api.isOnline(applicationContext)) return Result.retry()
            for (item in batch) {
                afterId = item.id
                when (send(container, item)) {
                    ItemOutcome.DONE -> Unit
                    // The backend is unhealthy right now: stop hammering it,
                    // keep every row, let WorkManager back off.
                    ItemOutcome.RETRY_LATER, ItemOutcome.OFFLINE -> return Result.retry()
                }
            }
        }
    }

    private suspend fun send(container: AppContainer, item: DispatchQueueItem): ItemOutcome {
        val dao = container.db.dispatchQueueDao()
        val payload = container.repo.payloadFrom(item)
        if (payload == null) {
            AppLog.w(TAG, "corrupt outbox row ${item.id}, dead-lettering")
            dao.deleteById(item.id)
            container.repo.logDispatch(item.verifyId, "${item.status} (failed)", "Corrupt outbox row")
            return ItemOutcome.DONE
        }
        var attempt = 0
        while (true) {
            val result = container.api.postDispatch(payload, item.idempotencyKey)
            // Any HTTP response proves the server was reached — drive Last Sync.
            if (result.code != -1) runCatching { container.settings.touchHeartbeat() }
            when {
                result.ok -> {
                    dao.deleteById(item.id)
                    container.repo.logDispatch(payload.verifyId, payload.status, describe(payload))
                    return ItemOutcome.DONE
                }
                !result.retryable -> {
                    // Permanent rejection (e.g. 404 unknown verify_id): drop, never retry.
                    AppLog.w(TAG, "dead-letter row ${item.id}: ${result.error}")
                    dao.deleteById(item.id)
                    container.repo.logDispatch(
                        payload.verifyId, "${payload.status} (failed)", result.error ?: "HTTP error"
                    )
                    return ItemOutcome.DONE
                }
                else -> {
                    attempt++
                    dao.bumpAttempt(item.id, result.error)
                    if (attempt >= MAX_ATTEMPTS_PER_RUN) {
                        AppLog.w(TAG, "row ${item.id} still failing (${result.error}); kept for a later retry")
                        return ItemOutcome.RETRY_LATER
                    }
                    delay(backoffMs[minOf(attempt - 1, backoffMs.lastIndex)])
                    if (!container.api.isOnline(applicationContext)) return ItemOutcome.OFFLINE
                }
            }
        }
    }

    /** One-line human summary for the Live Log. */
    private fun describe(payload: DispatchPayload): String {
        return if (payload.status == "confirmed") {
            val amount = payload.amount?.let {
                if (it % 1.0 == 0.0) it.toLong().toString() else "%.2f".format(it)
            } ?: "—"
            "$amount EGP • ${payload.provider ?: "—"} • ref ${payload.referenceId ?: "—"}"
        } else {
            "no matching SMS within timeout"
        }
    }

    companion object {
        private const val TAG = "DispatchWorker"
        const val KEY_DISPATCH_ID = "dispatch_id"
        const val DRAIN_WORK_NAME = "dispatch-drain"
        const val PERIODIC_WORK_NAME = "dispatch-drain-periodic"
        private const val BATCH_SIZE = 20

        /** In-worker tries per row before handing the retry back to WorkManager. */
        const val MAX_ATTEMPTS_PER_RUN = 6
        val BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L)

        /** Delays between in-worker tries; tests shrink it to keep runs fast. */
        @VisibleForTesting
        @Volatile
        var backoffMs: LongArray = BACKOFF_MS

        /** One drain at a time per process (one-time, periodic and test runs alike). */
        private val drainMutex = Mutex()

        private fun baseRequest(input: Data) = OneTimeWorkRequestBuilder<DispatchWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            // Between runs while the backend stays down: 30s, 60s, 90s, …
            // (linear keeps recovery latency low during long outages).
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30L, TimeUnit.SECONDS)
            .addTag("dispatch")
            .build()

        /** Dispatch one freshly-enqueued row. */
        fun enqueue(context: Context, dispatchId: Long) {
            val req = baseRequest(Data.Builder().putLong(KEY_DISPATCH_ID, dispatchId).build())
            WorkManager.getInstance(context.applicationContext).enqueue(req)
        }

        /**
         * Drain whatever is queued. KEEP: a drain that is already running (or
         * waiting out its backoff) is never cancelled mid-POST; a running
         * drain picks up rows inserted after it started, the service loop
         * re-triggers it while rows remain, and the periodic drain is the
         * safety net.
         */
        fun enqueueDrain(context: Context) {
            val req = baseRequest(Data.EMPTY)
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(DRAIN_WORK_NAME, ExistingWorkPolicy.KEEP, req)
        }

        /**
         * Safety net: every 15 min (WorkManager minimum) with CONNECTED
         * constraint, drain anything the expedited path missed (reboot,
         * process death, lost ACK). KEEP = never duplicate the schedule.
         */
        fun schedulePeriodicDrain(context: Context) {
            val req = PeriodicWorkRequestBuilder<DispatchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .addTag(PERIODIC_WORK_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req
            )
        }
    }
}
