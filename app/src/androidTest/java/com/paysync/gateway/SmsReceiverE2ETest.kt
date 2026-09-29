package com.paysync.gateway

import android.content.Intent
import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.paysync.gateway.data.HmacSha256
import com.paysync.gateway.data.ParsedTransaction
import com.paysync.gateway.data.SettingsManager
import com.paysync.gateway.data.db.AppDatabase
import com.paysync.gateway.data.db.PendingVerify
import com.paysync.gateway.data.db.ProcessedSms
import com.paysync.gateway.receiver.SmsReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Phase-6 device-like verification (the owner cannot test manually):
 * feeds REAL wallet SMS (encoded as SMS-DELIVER PDUs, EN + AR digits)
 * through the actual SmsReceiver -> parser -> matcher -> Room outbox ->
 * DispatchWorker -> MockWebServer backend, and asserts the confirmed
 * dispatch, its signature over the exact received bytes, Idempotency-Key
 * presence, one-SMS-one-deposit semantics, sender gating and ambiguity
 * handling.
 */
@RunWith(AndroidJUnit4::class)
class SmsReceiverE2ETest {

    private lateinit var server: MockWebServer
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as PaySyncApp).container

    private val secret = "e2e-test-secret"

    /** Production-shaped VF-Cash English SMS. */
    private val vfEnSms =
        "You have received 150.00 EGP from 01012345678. Transaction ID: 023650505952. " +
            "New balance: 561.92 EGP."

    /**
     * Production-shaped VF-Cash Arabic SMS with Arabic-Indic digits.
     * Kept under 127 chars: a single UCS-2 SMS carries at most 255 TP-UDL
     * bytes (longer messages are multi-part, which FakeSms does not encode).
     */
    private val vfArSms =
        "تم استلام مبلغ ١٥٠ جنيه على رقم محفظتك. رصيدك الحالي: ٥٦١.٩٢ جنيه " +
            "رقم العملية: ٠٢٣٦٥٠٥٠٥٩٥٢"

    @Before
    fun setUp() = runBlocking<Unit> {
        server = MockWebServer()
        server.start()
        repeat(6) { server.enqueue(MockResponse().setBody("""{"status":"success"}""")) }
        container.db.clearAllTables()

        SettingsManager.get(context).apply {
            botApiUrl = server.url("/").toString().trimEnd('/')
            webhookSecret = secret
            pollingIntervalMs = 15_000L
            amountFallbackEnabled = true
            maxAutoConfirmAmountEgp = 0.0
        }
        allowSenders("VF-Cash", "BanK-AlAhly")
        container.repo.clearLogs()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun allowSenders(vararg senders: String) {
        val s = SettingsManager.get(context)
        s.setSenders(senders.toList())
        assertEquals(senders.toSet(), s.getSenders())
    }

    private fun broadcastSms(sender: String, body: String, timestamp: Long = System.currentTimeMillis()) {
        val pdu = FakeSms.deliverPdu(sender, body, timestamp)
        val intent = Intent(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            putExtra("pdus", arrayOf(pdu))
            putExtra("format", "3gpp")
        }
        SmsReceiver().onReceive(context, intent)
    }

    private fun pending(
        id: String,
        amount: Double,
        provider: String = "VF-Cash",
        ref: String? = null
    ) = runBlocking<Unit> {
        container.db.pendingVerifyDao().upsertAll(
            listOf(PendingVerify(id, amount, provider, ref, System.currentTimeMillis(), 120_000L))
        )
    }

    private fun awaitUntil(timeoutMs: Long = 20_000L, condition: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (runBlocking { condition() }) return true
            Thread.sleep(250)
        }
        return runBlocking { condition() }
    }

    private suspend fun confirmedLogCount() =
        container.db.dispatchLogDao().recentFlow(50).first().count { it.status == "confirmed" }

    private suspend fun logsWithStatus(status: String) =
        container.db.dispatchLogDao().recentFlow(50).first().count { it.status == status }

    private suspend fun queueCount() = container.db.dispatchQueueDao().countFlow().first()

    private suspend fun pendingCount() = container.db.pendingVerifyDao().countFlow().first()

    // ── 6a) End-to-end confirmed dispatch ────────────────────────────────

    @Test
    fun confirmed_vodafoneEnglishSms_dispatchsWithSignatureAndIdempotencyKey() = runBlocking<Unit> {
        allowSenders("VF-Cash")
        pending("v-100", amount = 150.0, provider = "VF-Cash", ref = "000000000000")

        broadcastSms("VF-Cash", vfEnSms)

        assertTrue("dispatch was not delivered", awaitUntil { queueCount() == 0 && confirmedLogCount() == 1 })
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull("expected a POST /transactions/dispatch", recorded)
        assertEquals("/transactions/dispatch", recorded!!.path)

        val bodyBytes = recorded.body.readByteArray()
        val json = JsonParser.parseString(String(bodyBytes, Charsets.UTF_8)).asJsonObject
        assertEquals("confirmed", json.get("status").asString)
        assertEquals("v-100", json.get("verify_id").asString)
        assertEquals(150.0, json.get("amount").asDouble, 0.001)
        assertEquals("023650505952", json.get("reference_id").asString)
        assertEquals("VF-Cash", json.get("provider").asString)

        // Signature must verify over the EXACT bytes the server received.
        assertEquals(
            HmacSha256.hex(secret, bodyBytes),
            recorded.getHeader("X-Gateway-Signature")
        )
        assertEquals(secret, recorded.getHeader("X-Gateway-Secret"))
        // Idempotency-Key must be present and a valid UUID.
        UUID.fromString(recorded.getHeader("Idempotency-Key"))
    }

    @Test
    fun confirmed_arabicDigitsSms_referenceNormalizedToWestern() = runBlocking<Unit> {
        allowSenders("VF-Cash")
        pending("v-ar", amount = 999.0, provider = "VF-Cash", ref = "023650505952")

        broadcastSms("VF-Cash", vfArSms)

        assertTrue("dispatch was not delivered", awaitUntil { queueCount() == 0 && confirmedLogCount() == 1 })
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        val json = JsonParser.parseString(recorded!!.body.readUtf8()).asJsonObject
        assertEquals("confirmed", json.get("status").asString)
        assertEquals("v-ar", json.get("verify_id").asString)
        assertEquals(150.0, json.get("amount").asDouble, 0.001)
        assertEquals("023650505952", json.get("reference_id").asString)
    }

    // ── 6a) One SMS confirms at most ONE deposit, ever ───────────────────

    @Test
    fun duplicateBroadcast_samePdu_confirmsExactlyOnce() = runBlocking<Unit> {
        allowSenders("VF-Cash")
        pending("v-dup", 150.0, ref = "023650505952")

        val ts = System.currentTimeMillis()
        broadcastSms("VF-Cash", vfEnSms, ts)
        assertTrue(awaitUntil { confirmedLogCount() == 1 })

        // Re-delivered broadcast (identical PDU → identical fingerprint).
        broadcastSms("VF-Cash", vfEnSms, ts)
        Thread.sleep(3_000) // give any illegal second path time to show up

        assertEquals(1, confirmedLogCount())
        assertEquals(0, queueCount())
    }

    @Test
    fun consumedSmsFingerprint_persistsAcrossDbReopen() = runBlocking<Unit> {
        val dao = container.db.processedSmsDao()
        val hash = "abc123"
        assertTrue(dao.insertIfAbsent(ProcessedSms(hash = hash)) != -1L)
        // A second claim of the SAME fingerprint must fail in this process…
        assertEquals(-1L, dao.insertIfAbsent(ProcessedSms(hash = hash)))
        // …and through a SECOND connection to the SAME on-disk database file,
        // which is what an app restart is from SQLite's point of view.
        val fresh = androidx.room.Room.databaseBuilder(
            context.applicationContext, AppDatabase::class.java,
            "paysync.db"
        ).build()
        try {
            assertEquals(-1L, fresh.processedSmsDao().insertIfAbsent(ProcessedSms(hash = hash)))
        } finally {
            fresh.close()
        }
    }

    // ── 6a) Sender gating before parsing ─────────────────────────────────

    @Test
    fun nonAllowedSender_isDroppedBeforeAnyProcessing() = runBlocking<Unit> {
        allowSenders("VF-Cash")
        pending("v-drop", 150.0, ref = "023650505952")

        broadcastSms("HACKED", vfEnSms) // near-miss attacker sender
        Thread.sleep(3_000)

        assertEquals(0, server.requestCount)
        assertEquals(0, queueCount())
        assertEquals(0, confirmedLogCount())
    }

    // ── 6a) Ambiguous amount never confirms ──────────────────────────────

    @Test
    fun ambiguousAmount_twoCandidates_notConfirmedAndLogged() = runBlocking<Unit> {
        allowSenders("VF-Cash")
        pending("v-a1", 150.0, ref = "111111111111")
        pending("v-a2", 150.0, ref = "222222222222")

        broadcastSms("VF-Cash", vfEnSms) // amount matches both; ref matches neither
        Thread.sleep(3_000)

        assertEquals(0, server.requestCount)
        assertEquals(0, queueCount())
        assertEquals(0, confirmedLogCount())
        assertEquals(1, logsWithStatus("ambiguous"))
        // Both deposits stay pending (they follow their normal timeout).
        assertEquals(2, pendingCount())
    }

    // ── 6a) Fallback OFF: only reference matching confirms ───────────────

    @Test
    fun fallbackDisabled_amountOnlySms_neverConfirms() = runBlocking<Unit> {
        allowSenders("VF-Cash")
        SettingsManager.get(context).amountFallbackEnabled = false
        pending("v-off", 150.0, ref = null)

        broadcastSms("VF-Cash", vfEnSms)
        Thread.sleep(3_000)

        assertEquals(0, server.requestCount)
        assertEquals(0, confirmedLogCount())
    }

    // ── 6a/2.2) Max auto-confirm amount ──────────────────────────────────

    @Test
    fun overMaxAmount_loggedForReview_notDispatched() = runBlocking<Unit> {
        allowSenders("VF-Cash")
        SettingsManager.get(context).maxAutoConfirmAmountEgp = 100.0
        pending("v-cap", 150.0, ref = "023650505952")

        broadcastSms("VF-Cash", vfEnSms)
        Thread.sleep(3_000)

        assertEquals(0, server.requestCount)
        assertEquals(0, queueCount())
        assertEquals(0, confirmedLogCount())
        assertEquals(1, logsWithStatus("review"))
        // Deposit stays pending and will follow its normal timeout.
        assertEquals(1, pendingCount())
    }

    // ── 1.5) N parallel attempts → exactly ONE confirmation ──────────────

    @Test
    fun parallelSmsBatches_exactlyOneConfirmation() = runBlocking<Unit> {
        allowSenders("VF-Cash")
        pending("v-race", 150.0, ref = "023650505952")

        // 8 concurrent "SMS" with different fingerprints but the SAME
        // reference + amount, racing for the same single deposit.
        val jobs = (1..8).map { n ->
            launch(Dispatchers.IO) {
                container.repo.onSmsParsed(
                    "VF-Cash",
                    ParsedTransaction(
                        provider = "VF-Cash", type = "vodafone_cash", amount = 150.0,
                        referenceId = "023650505952",
                        rawBody = "race-$n", timestamp = System.currentTimeMillis() + n
                    )
                )
            }
        }
        jobs.forEach { it.join() }

        assertTrue(awaitUntil { queueCount() == 0 })
        assertEquals("exactly one deposit confirmed", 1, confirmedLogCount())
        assertEquals(1, server.requestCount)
    }

}
