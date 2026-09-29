package com.paysync.gateway

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.paysync.gateway.data.DispatchPayload
import com.paysync.gateway.work.DispatchWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-6b: outbox behavior against a fake backend — 5xx then 200 (in-worker
 * retry with backoff and eventual delivery), 4xx (dead-lettered, never
 * retried), and outbox rows persisting with their once-minted Idempotency
 * Keys (what makes WorkManager delivery after process death safe).
 */
@RunWith(AndroidJUnit4::class)
class OutboxWorkerTest {

    private lateinit var server: MockWebServer
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as PaySyncApp).container

    @Before
    fun setUp() = runBlocking {
        server = MockWebServer()
        server.start()
        container.db.clearAllTables()
        val s = container.settings
        s.botApiUrl = server.url("/").toString().trimEnd('/')
        s.webhookSecret = "outbox-secret"
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Runs the real DispatchWorker.doWork() synchronously. */
    private suspend fun runWorker(): ListenableWorker.Result =
        // The builder passes the given context straight to the worker;
        // production WorkManager uses the Application, which the worker casts to PaySyncApp.
        TestListenableWorkerBuilder<DispatchWorker>(context.applicationContext).build().doWork()

    @Test
    fun fiveHundredThen200_retriesAndEventuallyDelivers() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("""{"status":"success"}"""))
        container.repo.enqueueDispatch(
            DispatchPayload(verifyId = "v-ob1", status = "confirmed", amount = 50.0)
        )
        val before = container.db.dispatchQueueDao().dueOnce(20).first().idempotencyKey
        assertNotEquals("", before)

        val result = runWorker()
        assertEquals(ListenableWorker.Result.success(), result)

        // First request hit 500, second delivered.
        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        assertEquals("/transactions/dispatch", first.path)
        assertEquals(before, first.getHeader("Idempotency-Key")) // key minted once, reused across retries

        assertEquals(0, container.db.dispatchQueueDao().countFlow().first())
        assertEquals(
            1,
            container.db.dispatchLogDao().recentFlow(50).first().count { it.status == "confirmed" }
        )
    }

    @Test
    fun fourHundred_isDeadLettered_neverRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        container.repo.enqueueDispatch(DispatchPayload(verifyId = "v-ob2", status = "timeout"))

        val result = runWorker()
        assertEquals(ListenableWorker.Result.success(), result)

        assertEquals(1, server.requestCount) // no retry after 4xx
        assertEquals(0, container.db.dispatchQueueDao().countFlow().first())
        val failed = container.db.dispatchLogDao().recentFlow(50).first()
            .first { it.status.contains("failed") }
        assertTrue(failed.detail.isNotEmpty())
    }

    @Test
    fun outboxRows_persistWithOnceMintedIdempotencyKeys() = runBlocking {
        // What makes delivery after process death safe: rows live in Room
        // with a stable key, so the next process's WorkManager drain delivers
        // them exactly once (delivery itself covered by the 5xx→200 test).
        val id1 = container.repo.enqueueDispatch(
            DispatchPayload(verifyId = "v-ob3", status = "confirmed", amount = 25.0)
        )
        val id2 = container.repo.enqueueDispatch(DispatchPayload(verifyId = "v-ob4", status = "timeout"))

        val rows = container.db.dispatchQueueDao().dueOnce(20)
        assertEquals(2, rows.size)
        assertEquals(setOf("v-ob3", "v-ob4"), rows.map { it.verifyId }.toSet())
        assertTrue(rows.all { it.idempotencyKey.isNotBlank() && it.attempts == 0 })
        assertEquals(setOf(id1, id2), rows.map { it.id }.toSet())
    }

}
