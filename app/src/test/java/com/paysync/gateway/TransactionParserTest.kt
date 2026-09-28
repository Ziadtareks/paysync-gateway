package com.paysync.gateway

import com.paysync.gateway.data.TransactionParser
import org.junit.Assert.*
import org.junit.Test

class TransactionParserTest {

    /** REAL production VF-Cash SMS (integer amount, bare مبلغ). */
    private val vfRealSample =
        "تم استلام مبلغ 150 جنيه من رقم 01555656781 المسجل بإسم Sohier M Rashwan " +
            "على رقم محفظتك 01017216250. رصيدك الحالي: 561.92 جنيه تاريخ العملية: 16:32 26-09-15 رقم العملية: 023732288590..."

    private val vfSample =
        "تم استلام مبلغ 150.00 جنيه من رقم 01115906129 المسجل بإسم Ahmed M Ahmed Sadek Azzam " +
            "على رقم محفظتك 01017216250. رصيدك الحالي: 315.42 جنيه تاريخ العملية: 23:23 26-09-12 رقم العملية: 023650505952..."

    private val nbeSample =
        "تم إضافة تحويل لحظي لبطاقتكم مسبقة الدفع بمبلغ 200.00 جم من MOHAMED YASSER ELSAYED رقم مرجعي 501087662186 يوم 09-09 الساعة 21:43..."

    @Test
    fun vodafone_realSms_integerAmount() {
        val p = TransactionParser.parse("VF-Cash", vfRealSample, 1726000000000L)!!
        assertEquals("vodafone_cash", p.type)
        assertEquals(150.0, p.amount, 0.0)
        assertEquals("01555656781", p.senderPhone)
        assertEquals("Sohier M Rashwan", p.senderName)
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
        assertEquals("01115906129", p.senderPhone)
        assertEquals("Ahmed M Ahmed Sadek Azzam", p.senderName)
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

    @Test
    fun arabicIndicDigits_normalizeBeforeParsing() {
        assertEquals("150.00", TransactionParser.normalizeDigits("١٥٠.٠٠"))
        val body = "تم استلام بمبلغ ١٥٠.٠٠ جنيه من رقم ٠١١١٥٩٠٦١٢٩ المسجل بإسم Test Name على رقم محفظتك رقم العملية: ١٢٣٤٥٦"
        val p = TransactionParser.parse("VF-Cash", body)!!
        assertEquals("vodafone_cash", p.type)
        assertEquals(150.0, p.amount, 0.0)
        assertEquals("01115906129", p.senderPhone)
        assertEquals("123456", p.referenceId)
    }

    @Test
    fun blankBody_returnsNull() {
        assertNull(TransactionParser.parse("VF-Cash", "   "))
    }
}
