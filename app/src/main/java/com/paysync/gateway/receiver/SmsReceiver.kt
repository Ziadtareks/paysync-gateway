package com.paysync.gateway.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.paysync.gateway.util.AppLog
import com.paysync.gateway.PaySyncApp
import com.paysync.gateway.data.SettingsManager
import com.paysync.gateway.data.TransactionParser
import com.paysync.gateway.data.db.ProcessedSms
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Listens for SMS_RECEIVED, gates on EXACT (case-insensitive) sender match,
 * deduplicates via SHA-256(sender|body|timestamp) in [ProcessedSms], then
 * parses via [TransactionParser] and hands off to the repository matcher.
 *
 * Dedup is atomic: INSERT OR IGNORE returning -1L means a duplicate broadcast
 * (or a concurrent twin) already claimed this message — abort immediately.
 *
 * Uses goAsync() + a bounded companion [Scope] (max 4 parallel) so broadcasts
 * never block the main thread; pendingResult.finish() is always called in
 * `finally`. Zero GlobalScope.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        // goAsync() is only non-null for real system broadcasts; the null case
        // exists for direct invocation in instrumented tests.
        val pendingResult: PendingResult? = goAsync()
        Scope.launch {
            try {
                handleSms(context.applicationContext, intent)
            } catch (e: Exception) {
                AppLog.e(TAG, "onReceive failed", e)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    private suspend fun handleSms(appContext: Context, intent: Intent) {
        val container = (appContext as? PaySyncApp)?.container ?: run {
            AppLog.w(TAG, "PaySyncApp container missing; SMS dropped")
            return
        }
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val allowed = SettingsManager.get(appContext).getSenders()
        if (allowed.isEmpty()) return

        // Group PDUs by originating address (multi-part SMS reassembly).
        for ((origin, parts) in messages.groupBy { it.originatingAddress ?: "" }) {
            if (!TransactionParser.matchesAllowedSender(origin, allowed)) {
                AppLog.d(TAG, "Ignoring SMS from: $origin")
                continue
            }
            val provider = TransactionParser.resolveMatchedProvider(origin, allowed)
            val fullBody = parts.mapNotNull { it.messageBody }.joinToString("")
            if (fullBody.isBlank()) continue

            val timestamp = parts.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

            // ---- Deduplication: abort before any parsing or DB side effects.
            val hash = sha256Hex("$origin|$fullBody|$timestamp")
            val claimed = container.db.processedSmsDao()
                .insertIfAbsent(ProcessedSms(hash = hash))
            if (claimed == -1L) {
                AppLog.d(TAG, "Duplicate SMS dropped (hash=${hash.take(8)}…)")
                continue
            }
            runCatching {
                container.db.processedSmsDao()
                    .pruneOlderThan(System.currentTimeMillis() - DEDUP_WINDOW_MS)
            }

            val parsed = try {
                TransactionParser.parse(provider, fullBody, timestamp)
            } catch (e: Exception) {
                AppLog.e(TAG, "Parse failed for $provider", e)
                null
            }
            if (parsed == null) {
                AppLog.w(TAG, "Unparseable or outgoing SMS from $provider (len=${fullBody.length})")
                continue
            }
            AppLog.i(TAG, "Parsed ${parsed.type} ${parsed.amount} ref=${parsed.referenceId}")
            try {
                container.repo.onSmsParsed(provider, parsed)
            } catch (e: Exception) {
                // Processing failed AFTER the dedup claim: release it, or this
                // payment SMS would be marked "seen" forever without ever
                // having been stored or matched.
                runCatching { container.db.processedSmsDao().deleteByHash(hash) }
                AppLog.e(TAG, "Processing failed for $provider; dedup claim released", e)
            }
        }
    }

    companion object {
        private const val TAG = "SmsReceiver"

        /** Bounded: never more than 4 SMS batches parsed concurrently. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val Scope =
            CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(4))

        /** Hashes retained 7 days — covers any carrier/broadcast redelivery. */
        const val DEDUP_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
