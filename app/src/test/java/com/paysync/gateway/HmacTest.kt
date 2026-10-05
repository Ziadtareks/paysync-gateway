package com.paysync.gateway

import com.paysync.gateway.data.ApiClient
import com.paysync.gateway.data.HmacSha256
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HmacTest {

    @Test
    fun knownVector() {
        // RFC 4231-style check: HMAC-SHA256(key="key", "The quick brown fox jumps over the lazy dog")
        assertEquals(
            "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
            HmacSha256.hex("key", "The quick brown fox jumps over the lazy dog")
        )
    }

    @Test
    fun hexIsLowercase64Chars() {
        val hex = HmacSha256.hex("secret", """{"verify_id":"v1","status":"confirmed"}""")
        assertEquals(64, hex.length)
        assertEquals(hex, hex.lowercase())
    }

    // ── 3.3 Signature is computed over the exact raw bytes sent ──────────

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun dispatch_signatureMatchesRawBytesReceivedByServer() {
        server.enqueue(MockResponse().setBody("""{"status":"success"}"""))

        val secret = "unit-test-secret"
        val json = """{"verify_id":"550e8400","status":"confirmed","amount":150.5,"provider":"VF-Cash","reference_id":"023650505952"}"""
        val client = OkHttpClient.Builder()
            .addInterceptor(ApiClient.HmacInterceptor { secret })
            .build()

        val request = Request.Builder()
            .url(server.url("/transactions/dispatch"))
            .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Idempotency-Key", "11111111-2222-3333-4444-555555555555")
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
        }

        val recorded = server.takeRequest()
        // RecordedRequest's body buffer is one-shot — read the bytes ONCE.
        val receivedBytes = recorded.body.readByteArray()
        // The signature must verify against the bytes the server ACTUALLY received.
        assertEquals(
            HmacSha256.hex(secret, receivedBytes),
            recorded.getHeader("X-Gateway-Signature")
        )
        assertEquals(secret, recorded.getHeader("X-Gateway-Secret"))
        assertEquals("11111111-2222-3333-4444-555555555555", recorded.getHeader("Idempotency-Key"))
        assertEquals(json, String(receivedBytes, Charsets.UTF_8))
    }

    @Test
    fun get_noSignatureAndNoSecretWhenBlank() {
        server.enqueue(MockResponse().setBody("[]"))
        val client = OkHttpClient.Builder()
            .addInterceptor(ApiClient.HmacInterceptor { "" })
            .build()
        client.newCall(Request.Builder().url(server.url("/transactions/pending")).get().build())
            .execute().use { assertEquals(200, it.code) }
        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("X-Gateway-Secret"))
        assertNull(recorded.getHeader("X-Gateway-Signature"))
    }

    @Test
    fun get_withSecret_hasSecretButNeverSignature() {
        server.enqueue(MockResponse().setBody("[]"))
        val client = OkHttpClient.Builder()
            .addInterceptor(ApiClient.HmacInterceptor { "s3cret" })
            .build()
        client.newCall(Request.Builder().url(server.url("/transactions/pending")).get().build())
            .execute().use { assertEquals(200, it.code) }
        val recorded = server.takeRequest()
        assertEquals("s3cret", recorded.getHeader("X-Gateway-Secret"))
        assertNull(recorded.getHeader("X-Gateway-Signature"))
        assertNotNull(recorded.body)
    }

    // ── V2 signature: timestamp + method + path + idempotency key + body ──

    @Test
    fun v2_postSignatureCoversTimestampMethodPathKeyAndBody() {
        server.enqueue(MockResponse().setBody("""{"status":"success"}"""))
        val secret = "unit-test-secret"
        val json = """{"verify_id":"v1","status":"timeout"}"""
        val client = OkHttpClient.Builder()
            .addInterceptor(
                ApiClient.HmacInterceptor(
                    legacySecretHeader = { false },
                    clock = { 1_700_000_000_123L }
                ) { secret }
            )
            .build()
        client.newCall(
            Request.Builder()
                .url(server.url("/transactions/dispatch"))
                .post(json.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .header("Idempotency-Key", "key-1")
                .build()
        ).execute().use { assertEquals(200, it.code) }

        val recorded = server.takeRequest()
        val body = recorded.body.readByteArray()
        assertEquals("1700000000", recorded.getHeader("X-Gateway-Timestamp"))
        val expected = HmacSha256.hex(
            secret,
            ApiClient.v2SigningPrefix("1700000000", "POST", "/transactions/dispatch", "key-1")
                .toByteArray(Charsets.UTF_8) + body
        )
        assertEquals(expected, recorded.getHeader("X-Gateway-Signature-V2"))
        // Legacy body signature still present; raw secret NOT sent when legacy header is off.
        assertEquals(HmacSha256.hex(secret, body), recorded.getHeader("X-Gateway-Signature"))
        assertNull(recorded.getHeader("X-Gateway-Secret"))
    }

    @Test
    fun v2_getIsSignedToo() {
        server.enqueue(MockResponse().setBody("[]"))
        val client = OkHttpClient.Builder()
            .addInterceptor(ApiClient.HmacInterceptor(legacySecretHeader = { false }, clock = { 5_000L }) { "s3cret" })
            .build()
        client.newCall(Request.Builder().url(server.url("/transactions/pending")).get().build())
            .execute().use { assertEquals(200, it.code) }
        val recorded = server.takeRequest()
        assertEquals("5", recorded.getHeader("X-Gateway-Timestamp"))
        assertEquals(
            HmacSha256.hex("s3cret", ApiClient.v2SigningPrefix("5", "GET", "/transactions/pending", null)),
            recorded.getHeader("X-Gateway-Signature-V2")
        )
        assertNull(recorded.getHeader("X-Gateway-Secret"))
    }

    // ── Dead-letter policy: only "permanently unprocessable" codes drop a dispatch ──

    @Test
    fun retryPolicy_keepsAuthAndServerErrors_dropsOnlyPermanentRejections() {
        for (code in listOf(-1, 401, 403, 408, 429, 500, 503)) {
            assertEquals("code $code", true, ApiClient.HttpResult(false, code).retryable)
        }
        for (code in listOf(400, 404, 409, 410, 422)) {
            assertEquals("code $code", false, ApiClient.HttpResult(false, code).retryable)
        }
        assertEquals(false, ApiClient.HttpResult(true, 200).retryable)
    }

    // ── Pending list: malformed items are skipped, never crash the poll ──

    @Test
    fun pendingList_skipsMalformedItemsKeepsValidOnes() {
        val body = """[
            {"verify_id":"ok1","expected_amount":50.0,"provider":"VF-Cash","reference_id_hint":""},
            {"verify_id":null,"expected_amount":50.0,"provider":"VF-Cash"},
            {"expected_amount":50.0,"provider":"VF-Cash"},
            {"verify_id":"noProvider","expected_amount":50.0},
            {"verify_id":"noAmount","provider":"VF-Cash"},
            {"verify_id":"badAmount","expected_amount":"abc","provider":"VF-Cash"},
            "not-an-object",
            {"verify_id":"ok2","expected_amount":"75.5","provider":"CIB","reference_id_hint":"123456","timeout_ms":60000}
        ]"""
        val list = com.paysync.gateway.data.PendingListParser.parse(body)
        assertEquals(listOf("ok1", "ok2"), list.map { it.verifyId })
        assertNull(list[0].referenceIdHint)
        assertEquals(75.5, list[1].expectedAmount, 0.0)
        assertEquals(60_000L, list[1].timeoutMs)
    }

    @Test
    fun pendingList_wrappedObjectAndEmpty() {
        val p = com.paysync.gateway.data.PendingListParser
        assertEquals(1, p.parse("""{"data":[{"verify_id":"a","expected_amount":1,"provider":"x"}]}""").size)
        assertEquals(0, p.parse("""{"data":"nope"}""").size)
        assertEquals(0, p.parse("   ").size)
    }
}
