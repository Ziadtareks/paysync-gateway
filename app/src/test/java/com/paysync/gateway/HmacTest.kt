package com.paysync.gateway

import com.paysync.gateway.data.HmacSha256
import org.junit.Assert.assertEquals
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
}
