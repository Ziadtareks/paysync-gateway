package com.paysync.gateway

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.paysync.gateway.data.GatewayRepository
import com.paysync.gateway.data.ParsedTransaction
import com.paysync.gateway.data.SettingsManager
import com.paysync.gateway.data.db.PendingVerify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for the review fixes in the repository layer, against a
 * fake backend that keeps LISTING a deposit (as a real backend does until
 * our dispatch lands) and fails every dispatch POST (so it stays queued):
 *  - a matched deposit is not resurrected as PENDING by the next poll;
 *  - an SMS that arrived BEFORE its deposit is late-matched on the poll
 *    that brings the deposit in (amount-only within poll lag, exact
 *    reference up to 15 minutes back);
 *  - a reference match with the wrong amount is never confirmed.
 */
@RunWith(AndroidJUnit4::class)
class RepositoryReliabilityTest {

    private lateinit var server: MockWebServer
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as PaySyncApp).container

    @Volatile
    private var pendingJson = "[]"

    @Before
    fun setUp() = runBlocking<Unit> {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "GET") MockResponse().setBody(pendingJson)
                else MockResponse().setResponseCode(503) // keep dispatches queued
        }
        server.start()
        container.db.clearAllTables()
        SettingsManager.get(context).apply {
            botApiUrl = server.url("/").toString().trimEnd('/')
            webhookSecret = "repo-test-secret"
            amountFallbackEnabled = true
            maxAutoConfirmAmountEgp = 0.0
        }
    }

    @After
    fun tearDown() {
        // Confirmations above trigger real background drains against the
        // 503 backend; stop them so they cannot leak into the next test.
        WorkManager.getInstance(context).cancelAllWorkByTag("dispatch").result.get()
        server.shutdown()
    }

    private fun sms(amount: Double, ref: String?, timestamp: Long = System.currentTimeMillis()) =
        ParsedTransaction("VF-Cash", "vodafone_cash", amount, "EGP", null, null, ref, "raw", timestamp)

    private fun deposit(id: String, amount: Double, ref: String = "") =
        """[{"verify_id":"$id","expected_amount":$amount,"provider":"VF-Cash","reference_id_hint":"$ref"}]"""

    @Test
    fun matchedDeposit_isNotResurrectedByTheNextPoll() = runBlocking<Unit> {
        pendingJson = deposit("v-tomb", 150.0)
        assertEquals(GatewayRepository.PollResult.OK, container.repo.pollPending())
        assertEquals(1, container.db.pendingVerifyDao().countFlow().first())

        container.repo.onSmsParsed("VF-Cash", sms(150.0, "023732288590"))
        assertEquals(0, container.db.pendingVerifyDao().countFlow().first())

        // Backend still lists it (our dispatch got 503): must NOT come back.
        container.repo.pollPending()
        assertEquals(0, container.db.pendingVerifyDao().countFlow().first())
        val row = container.db.pendingVerifyDao().allOnce().single()
        assertEquals(PendingVerify.STATUS_MATCHED, row.status)
        assertEquals(1, container.db.dispatchQueueDao().countForVerify("v-tomb"))
    }

    @Test
    fun smsBeforeDeposit_isLateMatchedOnThePollThatBringsTheDeposit() = runBlocking<Unit> {
        // Customer paid first: no deposit known yet.
        container.repo.onSmsParsed("VF-Cash", sms(200.0, "023732288591"))
        assertEquals(0, container.db.dispatchQueueDao().countOnce())

        pendingJson = deposit("v-late", 200.0)
        container.repo.pollPending()

        assertEquals(0, container.db.pendingVerifyDao().countFlow().first())
        val queued = container.db.dispatchQueueDao().dueOnce(10).single()
        assertEquals("v-late", queued.verifyId)
        assertEquals("confirmed", queued.status)
        assertTrue(container.db.capturedSmsDao().recentFlow(5).first().single().matched)
    }

    @Test
    fun lateMatch_ignoresSmsOlderThanTheWindow() = runBlocking<Unit> {
        val old = System.currentTimeMillis() - GatewayRepository.LATE_MATCH_WINDOW_MS - 60_000L
        container.repo.onSmsParsed("VF-Cash", sms(300.0, null, timestamp = old))

        pendingJson = deposit("v-old", 300.0)
        container.repo.pollPending()

        assertEquals(1, container.db.pendingVerifyDao().countFlow().first())
        assertEquals(0, container.db.dispatchQueueDao().countOnce())
    }

    @Test
    fun lateMatch_olderAmountOnlySms_isNotCreditedToANewDeposit() = runBlocking<Unit> {
        // 5 minutes old: beyond poll lag (15 s interval + 60 s grace). Could be
        // an unmatched payment for another order — never credit it by amount.
        val fiveMinAgo = System.currentTimeMillis() - 5 * 60_000L
        container.repo.onSmsParsed("VF-Cash", sms(400.0, null, timestamp = fiveMinAgo))

        pendingJson = deposit("v-amt", 400.0)
        container.repo.pollPending()

        assertEquals(1, container.db.pendingVerifyDao().countFlow().first())
        assertEquals(0, container.db.dispatchQueueDao().countOnce())
    }

    @Test
    fun lateMatch_olderSmsWithExactReference_isMatched() = runBlocking<Unit> {
        val fiveMinAgo = System.currentTimeMillis() - 5 * 60_000L
        container.repo.onSmsParsed("VF-Cash", sms(400.0, "023732288593", timestamp = fiveMinAgo))

        pendingJson = deposit("v-ref5", 400.0, ref = "023732288593")
        container.repo.pollPending()

        assertEquals(0, container.db.pendingVerifyDao().countFlow().first())
        assertEquals("v-ref5", container.db.dispatchQueueDao().dueOnce(10).single().verifyId)
    }

    @Test
    fun referenceMatchWithWrongAmount_isLoggedForReview_neverConfirmed() = runBlocking<Unit> {
        pendingJson = deposit("v-ref", 1000.0, ref = "023732288592")
        container.repo.pollPending()

        container.repo.onSmsParsed("VF-Cash", sms(1.0, "023732288592"))

        assertEquals(0, container.db.dispatchQueueDao().countOnce())
        assertEquals(1, container.db.pendingVerifyDao().countFlow().first())
        val review = container.db.dispatchLogDao().recentFlow(10).first().single()
        assertEquals("review", review.status)
        assertEquals("v-ref", review.verifyId)
    }
}
