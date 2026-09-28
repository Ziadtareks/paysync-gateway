package com.paysync.gateway

import com.paysync.gateway.data.ParsedTransaction
import com.paysync.gateway.data.db.PendingVerify
import com.paysync.gateway.domain.VerifyMatcher
import org.junit.Assert.*
import org.junit.Test

class VerifyMatcherTest {

    private fun pending(
        id: String,
        amount: Double,
        provider: String,
        ref: String? = null,
        createdAt: Long = 1_000_000L,
        timeoutMs: Long = 120_000L
    ) = PendingVerify(id, amount, provider, ref, createdAt, timeoutMs)

    private fun parsed(
        amount: Double,
        provider: String,
        ref: String? = null
    ) = ParsedTransaction(provider, "vodafone_cash", amount, "EGP", null, null, ref, "raw")

    @Test
    fun priority1_exactReferenceWins() {
        val list = listOf(
            pending("v1", 999.0, "VF-Cash", "023650505952"),
            pending("v2", 150.0, "VF-Cash", "000000000000")
        )
        val hit = VerifyMatcher.findMatch(
            parsed(150.0, "VF-Cash", "023650505952"), list, now = 1_050_000L
        )
        assertEquals("v1", hit?.verifyId)
    }

    @Test
    fun priority2_providerPlusAmountTolerance() {
        val list = listOf(pending("v1", 200.0, "BanK-AlAhly"))
        val hit = VerifyMatcher.findMatch(
            parsed(200.005, "BanK-AlAhly"), list, now = 1_050_000L
        )
        assertEquals("v1", hit?.verifyId)
    }

    @Test
    fun toleranceBoundary() {
        val list = listOf(pending("v1", 200.0, "VF-Cash"))
        assertNotNull(VerifyMatcher.findMatch(parsed(200.01, "VF-Cash"), list, now = 1_050_000L))
        assertNull(VerifyMatcher.findMatch(parsed(200.02, "VF-Cash"), list, now = 1_050_000L))
    }

    @Test
    fun providerMismatch_noMatch() {
        val list = listOf(pending("v1", 150.0, "CIB"))
        assertNull(VerifyMatcher.findMatch(parsed(150.0, "VF-Cash"), list, now = 1_050_000L))
    }

    @Test
    fun expiredRequests_neverMatch() {
        val list = listOf(pending("v1", 150.0, "VF-Cash", "023650505952"))
        assertNull(
            VerifyMatcher.findMatch(
                parsed(150.0, "VF-Cash", "023650505952"), list, now = 1_000_000L + 120_000L
            )
        )
    }

    @Test
    fun backendAlias_matchesSenderId() {
        // Backend says "vodafone_cash", SMS came from allow-list entry "VF-Cash".
        val list = listOf(pending("v1", 150.0, "vodafone_cash"))
        assertNotNull(VerifyMatcher.findMatch(parsed(150.0, "VF-Cash"), list, now = 1_050_000L))
        assertTrue(VerifyMatcher.sameProvider("BanK-AlAhly", "instapay_nbe"))
    }
}
