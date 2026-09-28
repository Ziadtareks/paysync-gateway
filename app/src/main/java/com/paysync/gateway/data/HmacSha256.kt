package com.paysync.gateway.data

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 helper used to sign dispatch payloads.
 * Pure JVM — unit-testable without Android.
 */
object HmacSha256 {

    fun hex(secret: String, payload: ByteArray): String {
        require(secret.isNotEmpty()) { "HMAC secret is empty" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload).toHex()
    }

    fun hex(secret: String, payload: String): String =
        hex(secret, payload.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        var i = 0
        for (b in this) {
            val v = b.toInt() and 0xFF
            chars[i++] = "0123456789abcdef"[v ushr 4]
            chars[i++] = "0123456789abcdef"[v and 0x0F]
        }
        return String(chars)
    }
}
