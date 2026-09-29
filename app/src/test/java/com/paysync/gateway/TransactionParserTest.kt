package com.paysync.gateway

import com.paysync.gateway.data.TransactionParser
import org.junit.Assert.*
import org.junit.Test

class TransactionParserTest {

    /** REAL production VF-Cash SMS (integer amount, bare مبلغ). */
    private val vfRealSample =
        "تم استلام مبلغ 150 جنيه من رقم 01000000001 المسجل بإسم Test Sender " +
            "على رقم محفظتك 01000000003. رصيدك الحالي: 561.92 جنيه تاريخ العملية: 16:32 26-09-15 رقم العملية: 023732288590..."

    private val vfSample =
        "تم استلام مبلغ 150.00 جنيه من رقم 01000000002 المسجل بإسم Test Receiver Name " +
            "على رقم محفظتك 01000000003. رصيدك الحالي: 315.42 جنيه تاريخ العملية: 23:23 26-09-12 رقم العملية: 023650505952..."

    private val nbeSample =
        "تم إضافة تحويل لحظي لبطاقتكم مسبقة الدفع بمبلغ 200.00 جم من MOHAMED YASSER ELSAYED رقم مرجعي 501087662186 يوم 09-09 الساعة 21:43..."

    @Test
    fun vodafone_realSms_integerAmount() {
        val p = TransactionParser.parse("VF-Cash", vfRealSample, 1726000000000L)!!
        assertEquals("vodafone_cash", p.type)
        assertEquals(150.0, p.amount, 0.0)
        assertEquals("01000000001", p.senderPhone)
        assertEquals("Test Sender", p.senderName)
        assertEquals("023732288590", p.referenceId)
        assertEquals("VF-Cash", p.provider)
        assertEquals("EGP", p.currency)
        assertEquals(vfRealSample, p.rawBody)
    }

    @Test
    fun vodafone_parsesAllFields() {
        val p = TransactionParser.parse("VF-Cash", vfSample, 1726000000000L)!!
        assertEquals("vodafone_cash", p.type)
        assertEquals(150.00, p.amount, 0.0)
        assertEquals("01000000002", p.senderPhone)
        assertEquals("Test Receiver Name", p.senderName)
        assertEquals("023650505952", p.referenceId)
    }

    @Test
    fun nbe_parsesAllFields() {
        val p = TransactionParser.parse("BanK-AlAhly", nbeSample, 1726000000000L)!!
        assertEquals("instapay_nbe", p.type)
        assertEquals(200.00, p.amount, 0.0)
        assertEquals("MOHAMED YASSER ELSAYED", p.senderName)
        assertEquals("501087662186", p.referenceId)
    }

    @Test
    fun nbe_toleratesBareMablagh() {
        val body = "تم إضافة تحويل لحظي لبطاقتكم مسبقة الدفع مبلغ 200 جم من SOMEONE NAME رقم مرجعي 111222333444"
        val p = TransactionParser.parse("BanK-AlAhly", body)!!
        assertEquals("instapay_nbe", p.type)
        assertEquals(200.0, p.amount, 0.0)
    }

    @Test
    fun generic_fallsBackAndKeepsRawBody() {
        val body = "CIB: تم خصم 500.50 جم من حسابكم رقم مرجعي 987654321098"
        val p = TransactionParser.parse("CIB", body)!!
        assertEquals("generic_bank", p.type)
        assertEquals(500.50, p.amount, 0.0)
        assertEquals("987654321098", p.referenceId)
        assertEquals(body, p.rawBody)
    }

    @Test
    fun generic_catchesBareLongNumberAsReference() {
        val body = "BM: رصيدك 75 جم عملية رقم 987654"
        val p = TransactionParser.parse("BM", body)!!
        assertEquals("generic_bank", p.type)
        assertEquals("987654", p.referenceId)
    }

    @Test
    fun generic_extractsLongestReferenceRun() {
        val body = "BM: تم تحويل 75 EGP Ref 1234567890123 يوم 12"
        val p = TransactionParser.parse("BM", body)!!
        assertEquals("generic_bank", p.type)
        assertEquals(75.0, p.amount, 0.0)
        assertEquals("1234567890123", p.referenceId)
    }

    @Test
    fun senderMatching_isExactCaseInsensitive() {
        val allowed = setOf("VF-Cash", "BanK-AlAhly", "Vodafone")
        assertTrue(TransactionParser.matchesAllowedSender("vf-cash", allowed))
        assertTrue(TransactionParser.matchesAllowedSender("BANK-ALAHLY", allowed))
        // Exact match only: partial/promo senders must NOT match
        assertFalse(TransactionParser.matchesAllowedSender("VF-Cash Promo", allowed))
        assertFalse(TransactionParser.matchesAllowedSender("OrangeCash", allowed))
        assertFalse(TransactionParser.matchesAllowedSender(null, allowed))
        assertEquals("VF-Cash", TransactionParser.resolveMatchedProvider("VF-CASH", allowed))
    }

    // ── 2.1 Sender trust: near-miss hardening ────────────────────────────

    @Test
    fun sender_trailingWhitespace_stillMatchesExactly() {
        val allowed = setOf("VF-Cash")
        assertTrue(TransactionParser.matchesAllowedSender("VF-Cash ", allowed))
        assertTrue(TransactionParser.matchesAllowedSender(" VF-Cash", allowed))
    }

    @Test
    fun sender_caseDifference_matches() {
        val allowed = setOf("VF-Cash")
        assertTrue(TransactionParser.matchesAllowedSender("vF-cAsH", allowed))
    }

    @Test
    fun sender_prefixSuffixVariants_neverMatch() {
        val allowed = setOf("VF-Cash", "BanK-AlAhly")
        // Suffix / prefix / containment variants of allowed senders must be dropped.
        assertFalse(TransactionParser.matchesAllowedSender("VF-Cash2", allowed))
        assertFalse(TransactionParser.matchesAllowedSender("2VF-Cash", allowed))
        assertFalse(TransactionParser.matchesAllowedSender("VF-CashX", allowed))
        assertFalse(TransactionParser.matchesAllowedSender("XVF-Cash", allowed))
        assertFalse(TransactionParser.matchesAllowedSender("VF-Cash-Promo", allowed))
        assertFalse(TransactionParser.matchesAllowedSender("MyVF-Cash", allowed))
        assertFalse(TransactionParser.matchesAllowedSender("BanK-AlAhlyBank", allowed))
        assertFalse(TransactionParser.matchesAllowedSender("AlAhly", allowed))
        assertFalse(TransactionParser.matchesAllowedSender("Bank", allowed))
    }

    @Test
    fun sender_numericSenders_neverMatch() {
        val allowed = setOf("VF-Cash")
        assertFalse(TransactionParser.matchesAllowedSender("123456", allowed))
        assertFalse(TransactionParser.matchesAllowedSender("+201234567890", allowed))
        assertFalse(TransactionParser.matchesAllowedSender("01", allowed))
    }

    @Test
    fun sender_emptyAllowedList_orBlankSender_drops() {
        assertFalse(TransactionParser.matchesAllowedSender("VF-Cash", emptySet()))
        assertFalse(TransactionParser.matchesAllowedSender("", setOf("VF-Cash")))
        assertFalse(TransactionParser.matchesAllowedSender("   ", setOf("VF-Cash")))
    }

    @Test
    fun sender_resolvedProvider_isTheExactAllowedEntry() {
        val allowed = setOf("VF-Cash")
        // Case-insensitive hit resolves to the canonical allow-list label.
        assertEquals("VF-Cash", TransactionParser.resolveMatchedProvider("  vF-CaSh  ", allowed))
        assertEquals("unknown", TransactionParser.resolveMatchedProvider(null, allowed))
    }

    @Test
    fun arabicIndicDigits_normalizeBeforeParsing() {
        assertEquals("150.00", TransactionParser.normalizeDigits("١٥٠.٠٠"))
        val body = "تم استلام بمبلغ ١٥٠.٠٠ جنيه من رقم ٠١٠٠٠٠٠٠٠٠٢ المسجل بإسم Test Name على رقم محفظتك رقم العملية: ١٢٣٤٥٦"
        val p = TransactionParser.parse("VF-Cash", body)!!
        assertEquals("vodafone_cash", p.type)
        assertEquals(150.0, p.amount, 0.0)
        assertEquals("01000000002", p.senderPhone)
        assertEquals("123456", p.referenceId)
    }

    @Test
    fun blankBody_returnsNull() {
        assertNull(TransactionParser.parse("VF-Cash", "   "))
    }
}
