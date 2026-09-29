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
}
