package com.paysync.gateway.data

/**
 * Regex engine for Egyptian payment SMS (BILINGUAL AR/EN). All amounts are EGP.
 *
 * Real-world hardened: every amount pattern treats the leading بـ as
 * OPTIONAL (production VF-Cash SMS write "مبلغ 150 جنيه", not "بمبلغ"),
 * accepts integer or decimal amounts, and accepts جنيه / ج.م / جم / EGP.
 * English variants ("received 150.00 EGP", "transfer of 200.00 EGP",
 * "Transaction ID:", "Reference:") match with IGNORE_CASE.
 * Digits are normalized (Arabic-Indic → Western) BEFORE any regex runs;
 * the verbatim [ParsedTransaction.rawBody] is always preserved for the server.
 */
object TransactionParser {

    // ---------- A. Vodafone Cash (BILINGUAL AR/EN) ----------
    // AR sample: "تم استلام مبلغ 150 جنيه من رقم 01000000001 المسجل بإسم
    //   Test Sender على رقم محفظتك 01000000003. ... رقم العملية: 023732288590..."
    // EN sample: "You have received 150.00 EGP from 01000000004. Transaction ID: 023732288590. New balance: 561.92 EGP."
    private val VF_AMOUNT = Regex(
        """(?:بـ?\s*)?(?:مبلغ|Amount|received)\s*([\d.,]+)\s*(?:جنيه|ج\.م|جم|EGP)""",
        RegexOption.IGNORE_CASE
    )
    private val VF_SENDER_PHONE = Regex(
        """(?:من رقم|from)\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )
    private val VF_SENDER_NAME = Regex("""المسجل بإسم\s*(.*?)\s*على رقم""")
    private val VF_REFERENCE = Regex(
        """(?:رقم العملية|Transaction ID|Reference)\s*:?\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )

    // Spelling/punctuation fallbacks (باسم without hamza, missing colon) — bilingual
    private val VF_SENDER_NAME_TOLERANT = Regex("""المسجل\s*ب[إا]سم\s*(.*?)\s*على رقم""")
    private val VF_REFERENCE_TOLERANT = Regex(
        """(?:رقم العملية|Transaction ID|Reference)\s*:?\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )

    // ---------- B. InstaPay / NBE (BILINGUAL AR/EN) ----------
    // AR sample: "تم إضافة تحويل لحظي لبطاقتكم مسبقة الدفع بمبلغ 200.00 جم من
    //   MOHAMED YASSER ELSAYED رقم مرجعي 501087662186 ..."
    // EN sample: "Instant transfer of 200.00 EGP received from MOHAMED YASSER. Reference: 501087662186."
    private val NBE_AMOUNT = Regex(
        """(?:بـ?\s*)?(?:مبلغ|transfer of|received)\s*([\d.,]+)\s*(?:جم|جنيه|ج\.م|EGP)""",
        RegexOption.IGNORE_CASE
    )
    private val NBE_SENDER_NAME = Regex(
        """(?:من|from)\s+(.+?)\s+(?:رقم مرجعي|Reference)""",
        RegexOption.IGNORE_CASE
    )
    private val NBE_REFERENCE = Regex(
        """(?:رقم مرجعي|Reference)\s*:?\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )

    // ---------- C. Generic fallback (BM, CIB, Orange, Etisalat, …) ----------
    private val GENERIC_AMOUNT =
        Regex("""([\d.,]+)\s*(?:جنيه|ج\.م|جم|EGP)""", RegexOption.IGNORE_CASE)
    private val GENERIC_REF_LABELED =
        Regex("""(?:رقم|مرجع|عملية|ref(?:erence)?)[^\d]*(\d{6,})""", RegexOption.IGNORE_CASE)
    private val GENERIC_REF_BARE = Regex("""\b(\d{6,})\b""")
    private val GENERIC_EG_PHONE = Regex("""\b(01[0-9]{9})\b""")

    // ---------- Sender gating: EXACT match, case-insensitive ----------
    fun matchesAllowedSender(originatingAddress: String?, allowed: Set<String>): Boolean {
        if (originatingAddress.isNullOrBlank() || allowed.isEmpty()) return false
        val origin = originatingAddress.trim()
        return allowed.any { it.trim().equals(origin, ignoreCase = true) }
    }

    /** Canonical allow-list entry for the `provider` field. */
    fun resolveMatchedProvider(originatingAddress: String?, allowed: Set<String>): String {
        if (originatingAddress.isNullOrBlank()) return "unknown"
        val origin = originatingAddress.trim()
        return allowed.firstOrNull { it.trim().equals(origin, ignoreCase = true) } ?: origin
    }

    // ---------- Entry point ----------

    fun parse(provider: String, body: String, timestamp: Long = System.currentTimeMillis()): ParsedTransaction? {
        if (body.isBlank()) return null
        val text = normalizeDigits(body)
        // An outgoing/debit SMS (money LEFT the wallet) must never be read as
        // a customer deposit — otherwise the merchant's own transfer of the
        // same amount could auto-confirm someone's pending deposit.
        if (isOutgoing(text)) return null
        return when {
            isVodafoneHint(provider, text) ->
                parseVodafone(provider, body, text, timestamp) ?: parseGeneric(provider, body, text, timestamp)
            isNbeHint(provider, text) ->
                parseNbe(provider, body, text, timestamp) ?: parseGeneric(provider, body, text, timestamp)
            else -> parseGeneric(provider, body, text, timestamp)
        }
    }

    // ---------- Specific parsers (regexes run on normalized text) ----------

    fun parseVodafone(provider: String, rawBody: String, normalized: String = normalizeDigits(rawBody), timestamp: Long = System.currentTimeMillis()): ParsedTransaction? {
        val amount = VF_AMOUNT.find(normalized)
            ?.groupValues?.getOrNull(1)?.let(::parseAmount) ?: return null
        val senderPhone = VF_SENDER_PHONE.find(normalized)?.groupValues?.getOrNull(1)?.trim()
        val senderName = (VF_SENDER_NAME.find(normalized) ?: VF_SENDER_NAME_TOLERANT.find(normalized))
            ?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        val referenceId = (VF_REFERENCE.find(normalized) ?: VF_REFERENCE_TOLERANT.find(normalized))
            ?.groupValues?.getOrNull(1)?.trim()
        return ParsedTransaction(provider, "vodafone_cash", amount, "EGP", senderPhone, senderName, referenceId, rawBody, timestamp)
    }

    fun parseNbe(provider: String, rawBody: String, normalized: String = normalizeDigits(rawBody), timestamp: Long = System.currentTimeMillis()): ParsedTransaction? {
        val amount = NBE_AMOUNT.find(normalized)
            ?.groupValues?.getOrNull(1)?.let(::parseAmount) ?: return null
        val senderName = NBE_SENDER_NAME.find(normalized)?.groupValues?.getOrNull(1)
            ?.trim()?.trimEnd('.', ':', '،', ';', '-', '–')?.trim()
            ?.takeIf { it.isNotEmpty() }
        val referenceId = NBE_REFERENCE.find(normalized)?.groupValues?.getOrNull(1)?.trim()
        return ParsedTransaction(provider, "instapay_nbe", amount, "EGP", null, senderName, referenceId, rawBody, timestamp)
    }

    fun parseGeneric(provider: String, rawBody: String, normalized: String = normalizeDigits(rawBody), timestamp: Long = System.currentTimeMillis()): ParsedTransaction? {
        // First amount that is not a balance figure ("رصيدك 5000 جنيه" /
        // "balance: 5000 EGP"): a balance-only SMS is not a deposit at all.
        var prevEnd = 0
        val amount = GENERIC_AMOUNT.findAll(normalized)
            .filterNot { m ->
                val isBalance = isBalanceAmount(normalized, prevEnd, m.range.first)
                prevEnd = m.range.last + 1
                isBalance
            }
            .firstNotNullOfOrNull { it.groupValues.getOrNull(1)?.let(::parseAmount) }
            ?: return null
        // Labeled reference first, else the longest bare digit run ≥ 6.
        // Egyptian mobile numbers ("من رقم 01012345678") are the sender's
        // phone, never a transaction reference.
        val referenceId = GENERIC_REF_LABELED.findAll(normalized)
            .map { it.groupValues[1] }
            .filterNot(::isEgyptianMobile)
            .maxByOrNull { it.length }
            ?: GENERIC_REF_BARE.findAll(normalized)
                .map { it.groupValues[1] }
                .filterNot(::isEgyptianMobile)
                .maxByOrNull { it.length }
        val senderPhone = GENERIC_EG_PHONE.find(normalized)?.groupValues?.getOrNull(1)
        return ParsedTransaction(provider, "generic_bank", amount, "EGP", senderPhone, null, referenceId, rawBody, timestamp)
    }

    // ---------- Direction / balance guards ----------

    /** Money-in markers (AR/EN). Any of these makes the SMS an incoming credit. */
    private val INCOMING_MARKERS = Regex(
        """استلام|استلمت|إضافة|اضافة|إيداع|ايداع|لحسابك|لبطاقتك|لمحفظتك|إليك|اليك|استرداد|received|credited|deposited|incoming|refund""",
        RegexOption.IGNORE_CASE
    )

    /** Money-out markers (AR/EN): debits, withdrawals, purchases, transfers TO someone. */
    private val OUTGOING_MARKERS = Regex(
        """خصم|سحب|شراء|مشتريات|تم الدفع|دفع مبلغ|إرسال|ارسال|تحويل[^.\n]{0,80}?(?:إلى|الى)\s|""" +
            """debited|withdraw|purchase|you have sent|you sent|sent to|transferred to|paid to""",
        RegexOption.IGNORE_CASE
    )

    private val BALANCE_MARKER = Regex("""رصيد|balance""", RegexOption.IGNORE_CASE)
    private val EG_MOBILE = Regex("""01[0125]\d{8}""")

    /** True for a debit/outgoing SMS with no incoming marker at all. */
    fun isOutgoing(normalizedBody: String): Boolean =
        OUTGOING_MARKERS.containsMatchIn(normalizedBody) &&
            !INCOMING_MARKERS.containsMatchIn(normalizedBody)

    /**
     * The amount at [amountStart] is labeled as a balance (e.g. "رصيدك الحالي:
     * 561.92 جنيه"). Only the text since the previous amount ([prevEnd]) and
     * at most 25 chars back counts, so a balance label never "leaks" onto
     * the next amount.
     */
    private fun isBalanceAmount(text: String, prevEnd: Int, amountStart: Int): Boolean {
        val windowStart = maxOf(prevEnd, amountStart - 25, 0)
        return BALANCE_MARKER.containsMatchIn(text.substring(windowStart, amountStart))
    }

    private fun isEgyptianMobile(digits: String): Boolean = EG_MOBILE.matches(digits)

    // ---------- Hints (content markers unique to each rail) ----------

    private fun isVodafoneHint(provider: String, normalizedBody: String): Boolean {
        val p = provider.trim().lowercase()
        if (p.contains("vf") || p.contains("vodafone")) return true
        if (normalizedBody.contains("محفظتك") || normalizedBody.contains("رقم العملية")) return true
        // English hints: "Transaction ID", "New balance", "You have received"
        val lower = normalizedBody.lowercase()
        return lower.contains("transaction id") ||
            lower.contains("new balance") ||
            lower.contains("you have received")
    }

    private fun isNbeHint(provider: String, normalizedBody: String): Boolean {
        val p = provider.trim().lowercase()
        if (p.contains("alahly") || p.contains("ahly") || p == "nbe" || p.contains("instapay")) return true
        if (normalizedBody.contains("تحويل لحظي") || normalizedBody.contains("لبطاقتكم")) return true
        // English hints: "Instant transfer of ... Reference:"
        val lower = normalizedBody.lowercase()
        return lower.contains("instant transfer") ||
            (lower.contains("transfer of") && lower.contains("reference"))
    }

    // ---------- Normalization ----------

    /**
     * Normalizes Arabic-Indic (٠-٩), Extended Arabic-Indic (۰-۹) and Arabic
     * separators to Western digits BEFORE parsing. Thousand separators
     * (٬ , ،) are stripped only when a digit sits on BOTH sides ("1,500" →
     * "1500"); spaces are never stripped, so "ID: 023732288590 2024-10-05"
     * keeps the reference intact. The Arabic decimal separator (٫) becomes '.'.
     */
    fun normalizeDigits(raw: String): String {
        val sb = StringBuilder(raw.length)
        for ((i, ch) in raw.withIndex()) {
            when (ch) {
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                '٫' -> sb.append('.')
                '٬', ',', '،' -> {
                    val prevIsDigit = sb.isNotEmpty() && sb.last().isDigit()
                    val nextIsDigit = i + 1 < raw.length && isAnyDigit(raw[i + 1])
                    if (!(prevIsDigit && nextIsDigit)) sb.append(ch)
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun isAnyDigit(ch: Char): Boolean =
        ch in '0'..'9' || ch in '٠'..'٩' || ch in '۰'..'۹'

    fun parseAmount(raw: String): Double? {
        val cleaned = raw.replace(",", "").trim()
        return cleaned.toDoubleOrNull()
            ?: Regex("""\d+(?:\.\d+)?""").find(cleaned)?.value?.toDoubleOrNull()
    }
}
