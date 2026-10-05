package com.paysync.gateway

import com.paysync.gateway.data.ParsedTransaction
import com.paysync.gateway.data.db.PendingVerify
import com.paysync.gateway.domain.VerifyMatcher
import com.paysync.gateway.domain.VerifyMatcher.MatchResult
import kotlinx.coroutines.launch
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

    /** The one assertion shape used by every test below. */
    private fun assertMatchOf(result: MatchResult, id: String) {
        assertTrue("expected Match, got $result", result is MatchResult.Match)
        assertEquals(id, (result as MatchResult.Match).verify.verifyId)
    }

    // ── Existing scenarios (kept, adapted to the MatchResult API) ────────

    @Test
    fun priority1_exactReferenceWins() {
        val list = listOf(
            pending("v1", 150.0, "VF-Cash", "023650505952"),
            pending("v2", 150.0, "VF-Cash", "000000000000")
        )
        val hit = VerifyMatcher.findMatch(
            parsed(150.0, "VF-Cash", "023650505952"), list, now = 1_050_000L
        )
        assertMatchOf(hit, "v1")
    }

    @Test
    fun priority1_referenceWithWrongAmount_isNeverConfirmed() {
        // Customer supplies the reference of a 1 EGP transfer for a 999 EGP deposit.
        val list = listOf(pending("v1", 999.0, "VF-Cash", "023650505952"))
        val hit = VerifyMatcher.findMatch(
            parsed(1.0, "VF-Cash", "023650505952"), list, now = 1_050_000L
        )
        assertTrue("expected AmountMismatch, got $hit", hit is MatchResult.AmountMismatch)
        hit as MatchResult.AmountMismatch
        assertEquals("v1", hit.verify.verifyId)
        assertEquals(999.0, hit.expected, 0.0)
        assertEquals(1.0, hit.received, 0.0)
    }

    @Test
    fun priority2_providerPlusAmountTolerance() {
        val list = listOf(pending("v1", 200.0, "BanK-AlAhly"))
        val hit = VerifyMatcher.findMatch(
            parsed(200.005, "BanK-AlAhly"), list, now = 1_050_000L
        )
        assertMatchOf(hit, "v1")
    }

    @Test
    fun toleranceBoundary() {
        val list = listOf(pending("v1", 200.0, "VF-Cash"))
        assertTrue(
            VerifyMatcher.findMatch(parsed(200.01, "VF-Cash"), list, now = 1_050_000L)
                is MatchResult.Match
        )
        assertEquals(
            MatchResult.NoMatch,
            VerifyMatcher.findMatch(parsed(200.02, "VF-Cash"), list, now = 1_050_000L)
        )
    }

    @Test
    fun providerMismatch_noMatch() {
        val list = listOf(pending("v1", 150.0, "CIB"))
        assertEquals(
            MatchResult.NoMatch,
            VerifyMatcher.findMatch(parsed(150.0, "VF-Cash"), list, now = 1_050_000L)
        )
    }

    @Test
    fun expiredRequests_neverMatch() {
        val list = listOf(pending("v1", 150.0, "VF-Cash", "023650505952"))
        assertEquals(
            MatchResult.NoMatch,
            VerifyMatcher.findMatch(
                parsed(150.0, "VF-Cash", "023650505952"), list, now = 1_000_000L + 120_000L
            )
        )
    }

    @Test
    fun backendAlias_matchesSenderId() {
        // Backend says "vodafone_cash", SMS came from allow-list entry "VF-Cash".
        val list = listOf(pending("v1", 150.0, "vodafone_cash"))
        assertTrue(
            VerifyMatcher.findMatch(parsed(150.0, "VF-Cash"), list, now = 1_050_000L)
                is MatchResult.Match
        )
        assertTrue(VerifyMatcher.sameProvider("BanK-AlAhly", "instapay_nbe"))
    }

    // ── 1.2 Amount fallback ambiguity ────────────────────────────────────

    @Test
    fun amount_twoCandidates_isAmbiguous_neverConfirmed() {
        val list = listOf(
            pending("v1", 500.0, "VF-Cash"),
            pending("v2", 500.0, "VF-Cash")
        )
        val hit = VerifyMatcher.findMatch(parsed(500.0, "VF-Cash"), list, now = 1_050_000L)
        assertTrue(hit is MatchResult.Ambiguous)
        assertEquals(2, (hit as MatchResult.Ambiguous).candidates)
    }

    @Test
    fun amount_singleCandidate_confirms() {
        val list = listOf(
            pending("v1", 500.0, "VF-Cash"),
            pending("v2", 300.0, "VF-Cash")
        )
        assertMatchOf(
            VerifyMatcher.findMatch(parsed(500.0, "VF-Cash"), list, now = 1_050_000L), "v1"
        )
    }

    @Test
    fun amount_zeroCandidates_isPlainNoMatch() {
        val list = listOf(pending("v1", 500.0, "VF-Cash"))
        assertEquals(
            MatchResult.NoMatch,
            VerifyMatcher.findMatch(parsed(499.98, "VF-Cash"), list, now = 1_050_000L)
        )
    }

    @Test
    fun referenceBeatsAmount_evenWhenAmountAlsoMatches() {
        val list = listOf(
            pending("byRef", 150.0, "VF-Cash", "023650505952"),
            pending("byAmount", 150.0, "VF-Cash")
        )
        // Amount alone would be ambiguous (2 candidates); the reference decides.
        assertMatchOf(
            VerifyMatcher.findMatch(parsed(150.0, "VF-Cash", "023650505952"), list, now = 1_050_000L),
            "byRef"
        )
    }

    @Test
    fun referenceAmountMismatch_neverFallsBackToAnotherDeposit() {
        val list = listOf(
            pending("byRef", 999.0, "VF-Cash", "023650505952"),
            pending("byAmount", 150.0, "VF-Cash")
        )
        val hit = VerifyMatcher.findMatch(parsed(150.0, "VF-Cash", "023650505952"), list, now = 1_050_000L)
        assertTrue("expected AmountMismatch, got $hit", hit is MatchResult.AmountMismatch)
    }

    @Test
    fun reference_twoDuplicateHints_isAmbiguous() {
        val list = listOf(
            pending("v1", 150.0, "VF-Cash", "023650505952"),
            pending("v2", 150.0, "VF-Cash", "023650505952")
        )
        val hit = VerifyMatcher.findMatch(parsed(150.0, "VF-Cash", "023650505952"), list, now = 1_050_000L)
        assertTrue(hit is MatchResult.Ambiguous)
        assertEquals("duplicate_reference", (hit as MatchResult.Ambiguous).reason)
    }

    // ── 1.4 Fallback switch ──────────────────────────────────────────────

    @Test
    fun fallbackDisabled_amountNeverConfirms() {
        val list = listOf(pending("v1", 200.0, "BanK-AlAhly"))
        assertEquals(
            MatchResult.NoMatch,
            VerifyMatcher.findMatch(parsed(200.0, "BanK-AlAhly"), list, now = 1_050_000L, allowAmountFallback = false)
        )
    }

    @Test
    fun fallbackDisabled_referenceStillConfirms() {
        val list = listOf(pending("v1", 200.0, "BanK-AlAhly", "501087662186"))
        assertMatchOf(
            VerifyMatcher.findMatch(
                parsed(200.0, "BanK-AlAhly", "501087662186"), list, now = 1_050_000L,
                allowAmountFallback = false
            ), "v1"
        )
    }

    // ── 1.1 Safe reference normalization ─────────────────────────────────

    @Test
    fun reference_arabicIndicDigits_normalizeToWestern() {
        val list = listOf(pending("v1", 150.0, "VF-Cash", "023650505952"))
        assertMatchOf(
            VerifyMatcher.findMatch(
                parsed(150.0, "VF-Cash", "٠٢٣٦٥٠٥٠٥٩٥٢"), list, now = 1_050_000L
            ), "v1"
        )
    }

    @Test
    fun reference_persianDigits_normalizeToWestern() {
        assertEquals("501087662186", VerifyMatcher.normalizedReference("۵۰۱۰۸۷۶۶۲۱۸۶"))
    }

    @Test
    fun reference_whitespaceAndDashes_strippedButExactOtherwise() {
        // "023650 505-952" must equal "023650505952" after normalization…
        assertEquals("023650505952", VerifyMatcher.normalizedReference(" 023650 505-952 "))
        val list = listOf(pending("v1", 150.0, "VF-Cash", "0236 5050-5952"))
        assertMatchOf(
            VerifyMatcher.findMatch(parsed(150.0, "VF-Cash", "023650505952"), list, now = 1_050_000L), "v1"
        )
        // …but a genuinely different reference must never match.
        // (fallback off: only the reference path is under test)
        val list2 = listOf(pending("v1", 150.0, "VF-Cash", "023650505953"))
        assertEquals(
            MatchResult.NoMatch,
            VerifyMatcher.findMatch(
                parsed(150.0, "VF-Cash", "023650505952"), list2, now = 1_050_000L,
                allowAmountFallback = false
            )
        )
    }

    @Test
    fun reference_noSubstringMatching() {
        // hint is a prefix of the parsed ref — must NOT match (fallback off so
        // only the reference path is under test; same amount would otherwise
        // legitimately match via the amount fallback).
        val list = listOf(pending("v1", 150.0, "VF-Cash", "023650"))
        assertEquals(
            MatchResult.NoMatch,
            VerifyMatcher.findMatch(
                parsed(150.0, "VF-Cash", "023650505952"), list, now = 1_050_000L,
                allowAmountFallback = false
            )
        )
    }

    // ── Concurrency (1.5): mutex serializes claims ───────────────────────

    @Test
    fun matchLock_parallelClaims_produceSingleMatch() = kotlinx.coroutines.test.runTest {
        // Mirrors the repository: the claimed row is removed INSIDE the lock,
        // so every contender after the winner sees nothing left to match.
        val pendingList = java.util.concurrent.CopyOnWriteArrayList(
            listOf(pending("v1", 150.0, "VF-Cash"))
        )
        val confirmations = java.util.concurrent.atomic.AtomicInteger(0)
        val jobs = List(32) {
            launch(kotlinx.coroutines.Dispatchers.Default) {
                VerifyMatcher.withMatchLock {
                    val hit = VerifyMatcher.findMatch(
                        parsed(150.0, "VF-Cash"), pendingList, now = 1_050_000L
                    )
                    if (hit is MatchResult.Match) {
                        pendingList.remove(hit.verify)
                        confirmations.incrementAndGet()
                    }
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(1, confirmations.get())
    }
}
