package com.paysync.gateway

import com.paysync.gateway.data.security.AesGcmValueCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.KeyGenerator

/** Pure-JVM checks of the settings encryption format (Keystore is device-only). */
class AesGcmValueCodecTest {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val codec = AesGcmValueCodec { key }

    @Test
    fun everySharedPreferencesType_roundTripsExactly() {
        val values: Map<String, Any> = mapOf(
            "s" to "https://merchant.example/api — سر ٠١٢",
            "empty" to "",
            "l" to Long.MIN_VALUE,
            "i" to 42,
            "bt" to true,
            "bf" to false,
            "f" to 3.14f,
            "set" to setOf("VF-Cash", "BanK-AlAhly", "with,comma \"quote\""),
            "emptySet" to emptySet<String>()
        )
        for ((k, v) in values) {
            assertEquals("key $k", v, codec.decrypt(k, codec.encrypt(k, v)))
        }
    }

    @Test
    fun storedValue_isCiphertext_notPlaintext() {
        val secret = "super-secret-webhook-key"
        val stored = codec.encrypt("webhook_secret", secret)
        assertTrue(stored.startsWith(AesGcmValueCodec.PREFIX))
        assertFalse(stored.contains(secret))
        assertFalse(String(Base64.getDecoder().decode(stored.removePrefix("v1:"))).contains(secret))
    }

    @Test
    fun sameValueTwice_givesDifferentCiphertext_randomIv() {
        assertNotEquals(codec.encrypt("k", "same"), codec.encrypt("k", "same"))
    }

    @Test
    fun ciphertextMovedToAnotherKey_failsAuthentication() {
        val urlCipher = codec.encrypt("bot_api_url", "https://attacker.example")
        assertNull(codec.decrypt("webhook_secret", urlCipher))
    }

    @Test
    fun tamperedOrGarbageValues_decryptToNull_neverThrow() {
        val stored = codec.encrypt("k", "value")
        val raw = Base64.getDecoder().decode(stored.removePrefix("v1:"))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        assertNull(codec.decrypt("k", "v1:" + Base64.getEncoder().encodeToString(raw)))
        for (junk in listOf("", "plain text", "v1:", "v1:!!!", "v1:AA==", "v2:abc")) {
            assertNull("junk '$junk'", codec.decrypt("k", junk))
        }
    }

    @Test
    fun otherKey_cannotDecrypt() {
        val other = AesGcmValueCodec { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        assertNull(other.decrypt("k", codec.encrypt("k", "value")))
    }
}
