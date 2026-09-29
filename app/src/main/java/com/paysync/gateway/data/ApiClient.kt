package com.paysync.gateway.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp client for the Telegram Bot backend.
 *
 * Security (per request, via [HmacInterceptor]):
 *  - X-Gateway-Secret: the shared secret (authenticates the gateway).
 *  - X-Gateway-Signature: HMAC-SHA256 hex of the raw POST JSON body.
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
            .retryOnConnectionFailure(true)
            .addInterceptor(HmacInterceptor(settings))
            .build()
    }

    data class HttpResult(val ok: Boolean, val code: Int = -1, val error: String? = null) {
        /** 429 / 5xx / IO failures are worth retrying; other 4xx are dead-lettered. */
        val retryable: Boolean get() = !ok && (code == -1 || code == 429 || code >= 500)
    }

    // ---------- pending ----------

    suspend fun fetchPending(): List<PendingVerifyDto> = withContext(Dispatchers.IO) {
        val base = requireConfiguredBase()
        val request = Request.Builder().url("$base/transactions/pending").get().build()
        val response = withTimeoutOrNull(25_000L) { client.newCall(request).execute() }
            ?: throw IOException("Timeout fetching pending transactions")
        response.use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException("GET pending failed: HTTP ${res.code}")
            return@withContext parsePendingList(body)
        }
    }

    /**
     * Tolerant: accepts a bare JSON array or an object wrapping the array
     * under pending / transactions / data / items.
     */
    fun parsePendingList(body: String): List<PendingVerifyDto> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        val element = JsonParser.parseString(trimmed)
        val array = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> {
                val obj = element.asJsonObject
                listOf("pending", "transactions", "data", "items")
                    .firstNotNullOfOrNull { obj.getAsJsonArray(it) } ?: return emptyList()
            }
            else -> return emptyList()
        }
        return array.mapNotNull { runCatching { gson.fromJson(it, PendingVerifyDto::class.java) }.getOrNull() }
            .filter { it.verifyId.isNotBlank() }
    }

    // ---------- dispatch ----------

    fun dispatchJson(payload: DispatchPayload): String = gson.toJson(payload)

    suspend fun postDispatch(payload: DispatchPayload, idempotencyKey: String): HttpResult =
        withContext(Dispatchers.IO) {
            val base = try {
                requireConfiguredBase()
            } catch (e: IllegalStateException) {
                return@withContext HttpResult(false, -1, e.message)
            }
            val json = dispatchJson(payload)
            val request = Request.Builder()
                .url("$base/transactions/dispatch")
                .post(json.toRequestBody(jsonMediaType))
                .header("Idempotency-Key", idempotencyKey)
                .build()
            try {
                val response = withTimeoutOrNull(25_000L) { client.newCall(request).execute() }
                    ?: return@withContext HttpResult(false, -1, "Timeout after 25s")
                response.use { res ->
                    return@withContext if (res.isSuccessful) {
                        HttpResult(true, res.code)
                    } else {
                        HttpResult(false, res.code, "HTTP ${res.code}")
                    }
                }
            } catch (e: IllegalArgumentException) {
                HttpResult(false, -1, "Bad URL: ${e.message}")
            } catch (e: IOException) {
                HttpResult(false, -1, e.message ?: "Network error")
            } catch (e: Exception) {
                HttpResult(false, -1, e.message ?: e.javaClass.simpleName)
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
     * Signs every POST with HMAC-SHA256 of the raw body bytes exactly as they
     * are written to the wire, and always identifies the gateway via
     * X-Gateway-Secret. The secret is read lazily per request via
     * [secretProvider] (testable without Android).
     */
    class HmacInterceptor(private val secretProvider: () -> String) : Interceptor {
        constructor(settings: SettingsManager) : this({ settings.webhookSecret })

        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val original = chain.request()
            val secret = secretProvider()
            val builder = original.newBuilder()
            if (secret.isNotBlank()) {
                builder.header("X-Gateway-Secret", secret)
                val body = original.body
                if (original.method == "POST" && body != null) {
                    val buffer = Buffer()
                    body.writeTo(buffer)
                    builder.header("X-Gateway-Signature", HmacSha256.hex(secret, buffer.readByteArray()))
                }
            }
            return chain.proceed(builder.build())
        }
    }
}
