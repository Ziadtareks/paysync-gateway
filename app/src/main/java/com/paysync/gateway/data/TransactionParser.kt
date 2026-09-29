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
        val amount = GENERIC_AMOUNT.find(normalized)?.groupValues?.getOrNull(1)?.let(::parseAmount) ?: return null
        // Labeled reference first, else the longest bare digit run ≥ 6.
        val referenceId = GENERIC_REF_LABELED.findAll(normalized)
            .map { it.groupValues[1] }
            .maxByOrNull { it.length }
            ?: GENERIC_REF_BARE.findAll(normalized)
                .map { it.groupValues[1] }
                .maxByOrNull { it.length }
        val senderPhone = GENERIC_EG_PHONE.find(normalized)?.groupValues?.getOrNull(1)
        return ParsedTransaction(provider, "generic_bank", amount, "EGP", senderPhone, null, referenceId, rawBody, timestamp)
    }

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
     * between digits are stripped; the Arabic decimal separator (٫) becomes '.'.
     */
    fun normalizeDigits(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                in '٠'..'٩' -> sb.append('0' + (ch - '٠'))
                in '۰'..'۹' -> sb.append('0' + (ch - '۰'))
                '٫' -> sb.append('.')
                '٬', ',', '،', ' ' -> {
                    // Strip thousand separators — but only between digits.
                    val prevIsDigit = sb.isNotEmpty() && sb.last().isDigit()
                    if (!prevIsDigit) sb.append(ch)
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun parseAmount(raw: String): Double? {
        val cleaned = raw.replace(",", "").trim()
        return cleaned.toDoubleOrNull()
            ?: Regex("""\d+(?:\.\d+)?""").find(cleaned)?.value?.toDoubleOrNull()
    }
}
