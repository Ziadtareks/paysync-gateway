package com.paysync.gateway.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Tolerant, null-safe parser for GET /transactions/pending (pure JVM).
 *
 * Accepts a bare JSON array or an object wrapping the array under
 * pending / transactions / data / items. Each item is validated field by
 * field instead of trusting Gson reflection — Gson ignores Kotlin
 * nullability, so a missing `verify_id` / `provider` used to surface later
 * as an NPE or a Room NOT NULL crash that aborted the WHOLE poll. Invalid
 * items are now skipped individually; valid ones still go through.
 */
object PendingListParser {

    private val WRAPPER_KEYS = listOf("pending", "transactions", "data", "items")

    /** @throws com.google.gson.JsonParseException when the body is not JSON at all. */
    fun parse(body: String): List<PendingVerifyDto> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return emptyList()
        val element = JsonParser.parseString(trimmed)
        val array = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> {
                val obj = element.asJsonObject
                WRAPPER_KEYS.firstNotNullOfOrNull { key ->
                    obj.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                } ?: return emptyList()
            }
            else -> return emptyList()
        }
        return array.mapNotNull { runCatching { item(it) }.getOrNull() }
    }

    private fun item(el: JsonElement): PendingVerifyDto? {
        if (!el.isJsonObject) return null
        val o = el.asJsonObject
        val verifyId = o.string("verify_id")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val provider = o.string("provider")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val amount = o.number("expected_amount")?.takeIf { it > 0.0 && !it.isNaN() && !it.isInfinite() }
            ?: return null
        val hint = o.string("reference_id_hint")?.trim()?.takeIf { it.isNotEmpty() }
        val timeout = o.number("timeout_ms")?.toLong()
        return PendingVerifyDto(verifyId, amount, provider, hint, timeout)
    }

    private fun JsonObject.string(key: String): String? {
        val v = get(key) ?: return null
        if (v.isJsonNull || !v.isJsonPrimitive) return null
        return v.asString
    }

    private fun JsonObject.number(key: String): Double? {
        val v = get(key) ?: return null
        if (v.isJsonNull || !v.isJsonPrimitive) return null
        return runCatching { v.asDouble }.getOrNull()
    }
}
