package com.paysync.gateway

/**
 * Test-only SMS-DELIVER PDU builder (GSM 03.40/03.38), used to feed realistic
 * wallet SMS through the real [com.paysync.gateway.receiver.SmsReceiver] via
 * SmsMessage.createFromPdu / Telephony.Sms.Intents.getMessagesFromIntent.
 *
 * Supports:
 *  - alphanumeric senders ("VF-Cash", "BanK-AlAhly") via GSM-7 packed address
 *  - numeric senders ("01000000001") via semi-octet-swapped address
 *  - GSM 7-bit bodies (ASCII-safe Latin text) and UCS-2 bodies (Arabic,
 *    Arabic-Indic digits)
 */
object FakeSms {

    // GSM 03.38 default alphabet, indexed by septet value 0x00-0x7F.
    // (0x1B is the escape slot; test bodies avoid it and the extension table.)
    private const val GSM7_DEFAULT_ALPHABET = ("@£$¥èéùìòÇ\nØø\rÅå" +
        "Δ_ΦΓΛΩΠΨΣΘΞ\u001BÆæßÉ" +
        " !\"#¤%&'()*+,-./0123456789:;<=>?" +
        "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§" +
        "¿abcdefghijklmnopqrstuvwxyzäöñüà")

    fun deliverPdu(sender: String, body: String, timestamp: Long = System.currentTimeMillis()): ByteArray {
        val out = ArrayList<Byte>()
        out.add((0x00).toByte()) // SMSC length: none (SMS-DELIVER as delivered to MS)

        out.add((0x04).toByte()) // first octet: TP-MTI=00 (SMS-DELIVER), TP-MMS=1

        val isAlphanumeric = sender.any { it.isLetter() }
        if (isAlphanumeric) {
            val septets = sender.map { toGsm7(it) }
            // Address-Length for alphanumeric senders is the number of
            // SEMI-OCTETS the field occupies (ceil(septets*7/4)) — verified
            // against SmsMessage.createFromPdu on-device.
            val addrLen = (septets.size * 7 + 3) / 4
            out.add(addrLen.toByte())
            out.add((0xD0).toByte()) // type-of-number=alphanumeric, numbering plan=unknown
            packGsm7(septets).forEach { out.add(it) }
        } else {
            val digits = sender.removePrefix("+")
            out.add(digits.length.toByte())
            out.add(((if (sender.startsWith("+")) 0x91 else 0x81)).toByte())
            semiOctets(digits).forEach { out.add(it) }
        }

        out.add((0x00).toByte()) // TP-PID
        val bodyIsGsm7 = body.all { it == '\n' || it == '\r' || it in GSM7_DEFAULT_ALPHABET }
        if (bodyIsGsm7) {
            out.add((0x00).toByte()) // TP-DCS: GSM 7-bit default alphabet
            out.addAll(timestampBytes(timestamp))
            val septets = body.map { toGsm7(it) }
            out.add(septets.size.toByte()) // TP-UDL = septet count
            packGsm7(septets).forEach { out.add(it) }
        } else {
            out.add((0x08).toByte()) // TP-DCS: UCS-2
            out.addAll(timestampBytes(timestamp))
            val ud = body.toByteArray(Charsets.UTF_16BE)
            require(ud.size <= 255) { "UCS-2 body needs multi-part encoding (>255 bytes): ${body.length} chars" }
            out.add(ud.size.toByte()) // TP-UDL = octet count
            ud.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    private fun toGsm7(c: Char): Int {
        val idx = GSM7_DEFAULT_ALPHABET.indexOf(c)
        require(idx in 0..127) { "Char '$c' is not in the GSM-7 default alphabet subset; use a UCS-2 body" }
        return idx
    }

    private fun packGsm7(septets: List<Int>): ByteArray {
        val out = ArrayList<Byte>()
        var carry = 0
        var carryBits = 0
        for (s in septets) {
            carry = carry or (s shl carryBits)
            carryBits += 7
            while (carryBits >= 8) {
                out.add((carry and 0xFF).toByte())
                carry = carry ushr 8
                carryBits -= 8
            }
        }
        if (carryBits > 0) out.add((carry and 0xFF).toByte())
        return out.toByteArray()
    }

    private fun semiOctets(digits: String): List<Byte> {
        val d = if (digits.length % 2 == 1) digits + "F" else digits
        return d.chunked(2).map { pair ->
            val hi = if (pair[1] == 'F') 0xF else pair[1] - '0'
            val lo = pair[0] - '0'
            ((hi shl 4) or lo).toByte()
        }
    }

    /** TP-SCTS: 7 semi-octet-swapped BCD bytes (YY MM DD HH MM SS TZ). */
    private fun timestampBytes(epochMs: Long): List<Byte> {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
        fun bcd(v: Int): Int {
            val t = v % 100
            return ((t % 10) shl 4) or (t / 10)
        }
        // SMS timestamps carry the year 0-99 (interpreted near the current year).
        val year2 = cal.get(java.util.Calendar.YEAR) % 100
        return listOf(
            bcd(year2),
            bcd(cal.get(java.util.Calendar.MONTH) + 1),
            bcd(cal.get(java.util.Calendar.DAY_OF_MONTH)),
            bcd(cal.get(java.util.Calendar.HOUR_OF_DAY)),
            bcd(cal.get(java.util.Calendar.MINUTE)),
            bcd(cal.get(java.util.Calendar.SECOND)),
            0x00 // timezone half-hours west of GMT (sign bit unused in tests)
        ).map { it.toByte() }
    }

    /** Test accessor: packs a body into GSM-7, returning (bytes, septetCount). */
    fun packGsm7Test(body: String): Pair<ByteArray, Int> {
        val septets = body.map { toGsm7(it) }
        return packGsm7(septets) to septets.size
    }
}
