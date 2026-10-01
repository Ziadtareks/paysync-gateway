package com.paysync.gateway.work

import android.content.Context
import com.paysync.gateway.util.AppLog
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.paysync.gateway.PaySyncApp
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Backup poller: every 15 minutes (WorkManager minimum) fetches
 * GET /transactions/pending, upserts Room, and sweeps expired verifies into
 * timeout dispatches. The foreground service handles the tight 15s loop;
 * this worker keeps verification alive even if the service is killed.
 */
class PollingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? PaySyncApp)?.container
            ?: return Result.failure()
        val repo = container.repo
        val result = try {
            repo.pollPending()
            Result.success()
        } catch (e: IOException) {
            AppLog.w(TAG, "poll failed (io), retrying: ${e.message}")
            Result.retry()
        } catch (e: Exception) {
            AppLog.e(TAG, "poll failed", e)
            Result.failure()
        }
        // Service watchdog: with the toggle ON, alert when the foreground
        // service is actually dead (stale heartbeat) — the persisted toggle
        // alone is not proof that verification is running. Recovery (fresh
        // heartbeat + successful poll) clears the alert as before.
        val settings = container.settings
        if (settings.serviceEnabled) {
            val now = System.currentTimeMillis()
            val beat = settings.serviceHeartbeatMs
            val alive = com.paysync.gateway.util.ServiceHealth.isAlive(
                beat, now, settings.pollingIntervalMs
            )
            if (alive) {
                if (result == Result.success() && settings.isConfigured()) {
                    com.paysync.gateway.service.HealthNotifier.onPollSuccess(applicationContext)
                }
            } else {
                com.paysync.gateway.service.HealthNotifier.onServiceDead(applicationContext, beat, now)
            }
        }
        return result
    }

    companion object {
        private const val TAG = "PollingWorker"
        const val UNIQUE_NAME = "pending-poll"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PollingWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(UNIQUE_NAME)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }
    }
}
