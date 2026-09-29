package com.paysync.gateway.work

import android.content.Context
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
import com.paysync.gateway.PaySyncApp
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Expedited dispatcher for POST /transactions/dispatch.
 *
 * Each outbox row carries its own Idempotency-Key (UUID, generated at enqueue)
 * so bot-side dedupe makes retries safe.
 *
 * Retry: in-worker exponential backoff 2s, 4s, 8s, 16s, 32s, 64s (max 6
 * attempts, tracked in Room). HTTP 4xx (except 429) is dead-lettered
 * immediately — the row is dropped, never retried. Total IO failure with no
 * connectivity returns Result.retry() so WorkManager reschedules instead of
 * burning through the backoff budget offline.
 */
class DispatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? PaySyncApp)?.container
            ?: return Result.failure()
        val db = container.db
        val api = container.api

        val singleId = inputData.getLong(KEY_DISPATCH_ID, -1L)
        val items = if (singleId != -1L) {
            db.dispatchQueueDao().getById(singleId)?.let(::listOf) ?: emptyList()
        } else {
            db.dispatchQueueDao().dueOnce(20)
        }
        if (items.isEmpty()) return Result.success()
        if (!api.isOnline(applicationContext)) return Result.retry()

        for (item in items) {
            val payload = container.repo.payloadFrom(item)
            if (payload == null) {
                db.dispatchQueueDao().deleteById(item.id) // corrupt row: dead-letter
                continue
            }
            var attempt = 0
            while (true) {
                val result = api.postDispatch(payload, item.idempotencyKey)
                // Any HTTP response proves the server was reached — drive Last Sync.
                if (result.code != -1) runCatching { container.settings.touchHeartbeat() }
                when {
                    result.ok -> {
                        db.dispatchQueueDao().deleteById(item.id)
                        container.repo.logDispatch(
                            payload.verifyId, payload.status, describe(payload)
                        )
                        break
                    }
                    !result.retryable -> {
                        // 4xx dead-letter: drop, never retry.
                        AppLog.w(TAG, "dead-letter ${item.verifyId}: ${result.error}")
                        db.dispatchQueueDao().deleteById(item.id)
                        container.repo.logDispatch(
                            payload.verifyId, "${payload.status} (failed)", result.error ?: "HTTP error"
                        )
                        break
                    }
                    else -> {
                        attempt++
                        db.dispatchQueueDao().bumpAttempt(item.id, result.error)
                        if (attempt >= MAX_ATTEMPTS) {
                            AppLog.w(TAG, "exhausted ${item.verifyId}, dead-lettering")
                            db.dispatchQueueDao().deleteById(item.id)
                            container.repo.logDispatch(
                                payload.verifyId, "${payload.status} (failed)", result.error ?: "Retries exhausted"
                            )
                            break
                        }
                        delay(BACKOFF_MS[minOf(attempt - 1, BACKOFF_MS.lastIndex)])
                        if (!api.isOnline(applicationContext)) return Result.retry()
                    }
                }
            }
        }
        return Result.success()
    }

    /** One-line human summary for the Live Log. */
    private fun describe(payload: com.paysync.gateway.data.DispatchPayload): String {
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
        const val MAX_ATTEMPTS = 6
        val BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 64_000L)

        private fun baseRequest(input: Data) = OneTimeWorkRequestBuilder<DispatchWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            // Safety net behind the in-worker fast backoff (WorkManager minimum is 10s).
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
            .addTag("dispatch")
            .build()

        /** Dispatch one freshly-enqueued row. */
        fun enqueue(context: Context, dispatchId: Long) {
            val req = baseRequest(Data.Builder().putLong(KEY_DISPATCH_ID, dispatchId).build())
            WorkManager.getInstance(context.applicationContext).enqueue(req)
        }

        /** Drain whatever is due (timeouts, connectivity restore). Replaces pile-ups. */
        fun enqueueDrain(context: Context) {
            val req = baseRequest(Data.EMPTY)
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("dispatch-drain", ExistingWorkPolicy.REPLACE, req)
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
                .addTag("dispatch-drain-periodic")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                "dispatch-drain-periodic", ExistingPeriodicWorkPolicy.KEEP, req
            )
        }
    }
}
