package com.paysync.gateway.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp client for the Telegram Bot backend.
 *
 * Security (per request, via [HmacInterceptor]):
 *  - X-Gateway-Timestamp + X-Gateway-Signature-V2: HMAC-SHA256 over
 *    timestamp, method, path, Idempotency-Key and body — on GET and POST.
 *    Proves possession of the secret without sending it and lets the
 *    server reject stale/replayed requests.
 *  - X-Gateway-Signature: legacy HMAC-SHA256 hex of the raw POST JSON body.
 *  - X-Gateway-Secret: the raw shared secret — legacy, sent only while
 *    [SettingsManager.sendLegacySecretHeader] is on (default, for backends
 *    that have not adopted V2 yet).
 *
 * Endpoints (relative to [SettingsManager.botApiUrl]):
 *  - GET  /transactions/pending  -> pending verification requests
 *  - POST /transactions/dispatch  -> confirmed / timeout results
 */
class ApiClient(private val settings: SettingsManager) {

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            // Hard cap on the whole call (DNS + connect + TLS + body). Unlike
            // a coroutine timeout around a blocking execute(), this really
            // aborts the socket.
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(HmacInterceptor(settings))
            .build()
    }

    data class HttpResult(val ok: Boolean, val code: Int = -1, val error: String? = null) {
        /**
         * Only an explicit "this dispatch can never be processed" answer is
         * dead-lettered (400 / 404 / 409 / 410 / 422). Everything else — IO
         * failures, timeouts, 5xx, 429, and auth/config errors (401 / 403)
         * that the merchant can fix — stays queued and is retried, so a
         * confirmed payment is never silently dropped.
         */
        val retryable: Boolean get() = !ok && code !in PERMANENT_FAILURE_CODES
    }

    // ---------- pending ----------

    // Body reads block on the socket: always on IO, whatever the caller's
    // dispatcher (the UI's "Poll now" runs on Main).
    suspend fun fetchPending(): List<PendingVerifyDto> = withContext(Dispatchers.IO) {
        val base = requireConfiguredBase()
        val request = Request.Builder().url("$base/transactions/pending").get().build()
        client.newCall(request).await().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("GET pending failed: HTTP ${res.code}")
            parsePendingList(body)
        }
    }

    /** See [PendingListParser]: malformed items are skipped, never crash the poll. */
    fun parsePendingList(body: String): List<PendingVerifyDto> = PendingListParser.parse(body)

    // ---------- dispatch ----------

    fun dispatchJson(payload: DispatchPayload): String = gson.toJson(payload)

    suspend fun postDispatch(payload: DispatchPayload, idempotencyKey: String): HttpResult =
        withContext(Dispatchers.IO) { postDispatchIo(payload, idempotencyKey) }

    private suspend fun postDispatchIo(payload: DispatchPayload, idempotencyKey: String): HttpResult {
        val base = try {
            requireConfiguredBase()
        } catch (e: IllegalStateException) {
            return HttpResult(false, -1, e.message)
        }
        val json = dispatchJson(payload)
        return try {
            val request = Request.Builder()
                .url("$base/transactions/dispatch")
                .post(json.toRequestBody(jsonMediaType))
                .header("Idempotency-Key", idempotencyKey)
                .build()
            client.newCall(request).await().use { res ->
                if (res.isSuccessful) HttpResult(true, res.code)
                else HttpResult(false, res.code, "HTTP ${res.code}")
            }
        } catch (e: IllegalArgumentException) {
            HttpResult(false, -1, "Bad URL: ${e.message}")
        } catch (e: InterruptedIOException) {
            HttpResult(false, -1, "Timeout after ${CALL_TIMEOUT_SECONDS}s")
        } catch (e: IOException) {
            HttpResult(false, -1, e.message ?: "Network error")
        }
    }

    // ---------- helpers ----------

    private fun requireConfiguredBase(): String {
        if (!settings.isConfigured()) throw IllegalStateException("Bot API URL not configured")
        return settings.botApiUrl
    }

    fun isOnline(context: android.content.Context): Boolean {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Signs every request. V2 (GET + POST): HMAC-SHA256 over
     * "v2\n{unix seconds}\n{METHOD}\n{encoded path?query}\n{Idempotency-Key}\n"
     * followed by the raw body bytes exactly as written to the wire. Legacy
     * headers (body-only X-Gateway-Signature on POST, raw X-Gateway-Secret)
     * are kept for existing backends; the raw secret goes out only while
     * [legacySecretHeader] returns true. The secret is read lazily per
     * request via [secretProvider] (testable without Android).
     */
    class HmacInterceptor(
        private val legacySecretHeader: () -> Boolean = { true },
        private val clock: () -> Long = { System.currentTimeMillis() },
        private val secretProvider: () -> String
    ) : Interceptor {
        constructor(settings: SettingsManager) : this(
            legacySecretHeader = { settings.sendLegacySecretHeader },
            secretProvider = { settings.webhookSecret }
        )

        override fun intercept(chain: Interceptor.Chain): Response {
            val original = chain.request()
            val secret = secretProvider()
            if (secret.isBlank()) return chain.proceed(original)

            val builder = original.newBuilder()
            val bodyBytes = original.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readByteArray()
            } ?: ByteArray(0)

            if (legacySecretHeader()) builder.header("X-Gateway-Secret", secret)
            if (original.method == "POST" && original.body != null) {
                builder.header("X-Gateway-Signature", HmacSha256.hex(secret, bodyBytes))
            }
            val ts = (clock() / 1000L).toString()
            val pathAndQuery = original.url.encodedPath +
                (original.url.encodedQuery?.let { "?$it" } ?: "")
            builder.header("X-Gateway-Timestamp", ts)
            builder.header(
                "X-Gateway-Signature-V2",
                HmacSha256.hex(
                    secret,
                    v2SigningPrefix(ts, original.method, pathAndQuery, original.header("Idempotency-Key"))
                        .toByteArray(Charsets.UTF_8) + bodyBytes
                )
            )
            return chain.proceed(builder.build())
        }
    }

    companion object {
        const val CALL_TIMEOUT_SECONDS = 25L

        /** HTTP codes meaning "permanently unprocessable" — dead-lettered, never retried. */
        val PERMANENT_FAILURE_CODES = setOf(400, 404, 409, 410, 422)

        /** Exact signed-string prefix for X-Gateway-Signature-V2 (body bytes follow). */
        fun v2SigningPrefix(ts: String, method: String, pathAndQuery: String, idempotencyKey: String?): String =
            "v2\n$ts\n${method.uppercase()}\n$pathAndQuery\n${idempotencyKey.orEmpty()}\n"
    }
}

/**
 * Suspends on an OkHttp call; coroutine cancellation cancels the socket
 * (a blocking execute() inside withTimeout would keep running).
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    })
}
