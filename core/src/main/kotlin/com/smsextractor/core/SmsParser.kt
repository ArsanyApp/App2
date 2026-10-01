package com.smsextractor.core

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Extracts transaction details from bank SMS text (Arabic and English).
 *
 * Rules of thumb:
 *  - Nothing is guessed. A field that cannot be found stays null, and anything uncertain
 *    (missing year, unknown type, missing amount, ...) is listed in [Transaction.reviewNotes].
 *  - Regexes run on a normalised "match key" (unified Arabic letters, ASCII digits, lower case);
 *    extracted values are cut from the base text so names keep their original spelling.
 */
object SmsParser {

    private const val NUM = """\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?"""

    // Currency spellings in the match key (letters already unified / lower-cased).
    private val currencyAliases: List<Pair<String, String>> = listOf(
        """egp""" to "EGP",
        """ج\.م\.?""" to "EGP",
        """جم""" to "EGP",
        """جنيه(?:ا|ات)?(?: مصري)?""" to "EGP",
        """l\.e\.?""" to "EGP",
        """le""" to "EGP",
        """usd""" to "USD",
        """\$""" to "USD",
        """دولار(?: امريكي)?""" to "USD",
        """eur""" to "EUR",
        """€""" to "EUR",
        """يورو""" to "EUR",
        """sar""" to "SAR",
        """ريال(?: سعودي)?""" to "SAR",
        """gbp""" to "GBP",
        """£""" to "GBP",
        """جنيه استرليني""" to "GBP",
        """aed""" to "AED",
        """درهم(?: اماراتي)?""" to "AED",
        """kwd""" to "KWD",
        """دينار(?: كويتي)?""" to "KWD",
    )

    private val CUR = currencyAliases.joinToString("|") { it.first }
    private const val L = """a-zء-ي"""

    private val amountWithCurrency = Regex(
        """(?<![$L])($CUR)(?![$L])\s?(?<![\d.,])($NUM)(?![\d])|(?<![\d.,])($NUM)\s?(?<![$L])($CUR)(?![$L])"""
    )
    private val amountKeyword = Regex("""(?:بمبلغ|مبلغ|amount(?: of)?|قيمه)\s*:?\s*($NUM)(?![\d])""")
    private val balanceBefore = Regex(
        """(?:الرصيد المتاح|رصيدكم المتاح|رصيد حسابكم|رصيدكم|رصيدك|الرصيد|المتاح|رصيد|available balance|avail(?:able)?\.? ?bal(?:ance)?\.?|balance|bal\.?)\s*(?:الحالي|هو|is)?\s*[:=-]?\s*$"""
    )

    private val skipRegex = Regex(
        """otp|one[- ]time|verification|passcode|password|رمز التحقق|رمز التفعيل|كود التفعيل|كلمه المرور|كلمه السر|الرقم السري|لا تشارك|do not share|(?:^|\s)كود(?:\s|$)|(?:^|\s)رمز(?:\s|$)|declined|failed|unsuccessful|تم رفض|مرفوضه|لم تتم|غير ناجحه|فشل"""
    )

    private val actionArabic = Regex("""(?:لقد\s+)?تم\s+[ء-ي]+""")
    private val actionEnglish = Regex(
        """(?:has been |have been |was |were |is )?(?:credited|debited|deducted|withdrawn|deposited|transferred|received|sent|charged|refunded|reversed|paid|spent)"""
    )
    private val actionArabicFallback = Regex("""(?<![ء-ي])(?:خصم|ايداع|سحب|اضافه|شراء|تحويل|استرداد)(?![ء-ي])""")

    private val refundRegex = Regex("""استرداد|استرجاع|رد مبلغ|رد قيمه|عكس|الغاء|refund|revers|chargeback|returned""")
    private val feeRegex = Regex("""رسوم|عموله|مصاريف|مصروفات|(?<![a-z])fees?(?![a-z])|commission|service charge""")
    private val transferRegex = Regex("""تحويل|حواله|transfer|instapay|انستاباي|(?<![a-z])ipn(?![a-z])""")
    private val inMarkers = Regex("""لحسابكم|لحسابك|الي حسابكم|الي حسابك|في حسابكم|تم اضافه|تم ايداع|وارد|credited|received|to your (?:account|a/c)|into your""")
    private val outMarkers = Regex("""من حسابكم|من حسابك|تم تنفيذ|تم خصم|تم تحويل|صادر|debited|deducted|(?<![a-z])sent(?![a-z])|from your (?:account|a/c)""")
    private val strongIn = Regex("""تم اضافه|تم ايداع|credited|received""")
    private val strongOut = Regex("""تم تنفيذ|تم خصم|debited|deducted|(?<![a-z])sent(?![a-z])""")
    private val withdrawalRegex = Regex("""سحب|صراف|(?<![a-z])atm(?![a-z])|withdraw""")
    private val cardRegex = Regex("""بطاق|(?<![a-z])card(?![a-z])|(?<![a-z])pos(?![a-z])|purchase|شراء|مشتريات""")
    private val debitRegex = Regex("""خصم|debit|charged|purchase|شراء|مشتريات|spent|paid|(?:^|\s)عند\s|(?:^|\s)لدي\s|(?<![a-z])at\s""")
    private val depositRegex = Regex("""ايداع|deposit""")

    private const val STOP_WORDS =
        """رقم مرجعي|برقم مرجعي|رقم|مرجع|يوم|بتاريخ|في تاريخ|تاريخ|الساعه|للمزيد|بمبلغ|المتاح|الرصيد|ref|reference|on|at|via|with|date|avail|bal|balance"""
    private const val STOP = """(?=\s+(?:$STOP_WORDS)(?:\s|$|[.,:،])|\s*[.،,;]|\s*$)"""

    private val fromArabic = Regex("""(?:^|\s)من\s+(?!حساب|بطاق|رصيد|خلال|فضلك|ماكينه|صراف)(.+?)$STOP""")
    private val toArabic = Regex("""(?:^|\s)(?:الي|لصالح|للمستفيد)\s+(?!حساب|بطاق)(.+?)$STOP""")
    private val fromEnglish = Regex("""(?<![a-z])from\s+(?!your|a/c|account|card|acc)(.+?)$STOP""")
    private val toEnglish = Regex("""(?<![a-z])to\s+(?!your|a/c|account|card|acc)(.+?)$STOP""")
    private val merchantRegex = Regex("""(?:^|\s)(?:عند|لدي|at|@)\s+(?!\d)(.+?)$STOP""")

    private val suffixRegex = Regex(
        """(?:حسابكم|حسابك|حساب|بطاقتكم|بطاقتك|بطاقه|(?<![a-z])card|account|a/c|acct)(?:\s+(?!بمبلغ|مبلغ|amount|with|of|egp|بقيمه)[^\d\s]+){0,3}?\s*(?:رقم|no\.?|number|ending(?: with| in)?)?\s*[:#]?\s*[*x]*(\d{3,6})(?![\d.,])"""
    )
    private val referenceRegex = Regex(
        """(?:الرقم المرجعي|برقم مرجعي|رقم مرجعي|رقم المرجع|مرجع|reference(?: no\.?| number)?|ref(?:\.|erence)?(?: no\.?| number| #)?|rrn|txn id|transaction id|trx id)\s*[:#.]?\s*([a-z0-9]{6,})"""
    )
    private val timeRegex = Regex("""(?<![\d:])(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*(am|pm|ص|م)(?=\s|$|[.,،]))?""")

    private val isoDate = Regex("""(?<![\d])(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})(?![\d])""")
    private val dmyFull = Regex("""(?<![\d.,])(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})(?![\d])""")
    private val dmyShort = Regex("""(?<![\d.,/-])(\d{1,2})[-/](\d{1,2})[-/](\d{2})(?![\d/-])""")
    private val partialDate = Regex("""(?<![\d/.:,-])(\d{1,2})([-/])(\d{1,2})(?![\d/.:,-])""")
    private val monthNames: List<Pair<String, Int>> = listOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
        "يناير" to 1, "فبراير" to 2, "مارس" to 3, "ابريل" to 4, "مايو" to 5, "يونيو" to 6,
        "يوليو" to 7, "اغسطس" to 8, "سبتمبر" to 9, "اكتوبر" to 10, "نوفمبر" to 11, "ديسمبر" to 12,
    )
    private val textDate = Regex(
        """(?<![\d])(\d{1,2})[\s-]?(${monthNames.joinToString("|") { it.first }})[a-z]*\.?(?:[\s,-]+(\d{4}|\d{2})(?![\d:]))?"""
    )

    /** Returns null when the message is not a completed financial transaction (e.g. OTP, ads). */
    fun parse(sms: RawSms, zone: ZoneId = ZoneId.systemDefault()): Transaction? {
        val base = TextNormalizer.base(sms.body)
        if (base.isEmpty()) return null
        val key = TextNormalizer.matchKey(base)
        if (skipRegex.containsMatchIn(key)) return null

        val notes = mutableListOf<String>()

        // ---- amounts ---------------------------------------------------------------------
        var amount: BigDecimal? = null
        var amountEnd = -1
        var currency: String? = null
        var balance: BigDecimal? = null
        var balanceCurrency: String? = null
        for (m in amountWithCurrency.findAll(key)) {
            val curText = m.groupValues[1].ifEmpty { m.groupValues[4] }
            val numText = m.groupValues[2].ifEmpty { m.groupValues[3] }
            val value = toDecimal(numText) ?: continue
            val before = key.substring(maxOf(0, m.range.first - 40), m.range.first)
            if (balanceBefore.containsMatchIn(before)) {
                if (balance == null) {
                    balance = value
                    balanceCurrency = currencyCode(curText)
                }
            } else if (amount == null) {
                amount = value
                currency = currencyCode(curText)
                amountEnd = m.range.last
            }
        }
        if (amount == null) {
            amountKeyword.find(key)?.let { m ->
                amount = toDecimal(m.groupValues[1])
                amountEnd = m.range.last
                if (amount != null) notes += "Currency not found"
            }
        }
        if (amount == null && balance == null) return null // nothing financial in this message
        if (amount == null) notes += "Transaction amount not found"

        // ---- action phrase ---------------------------------------------------------------
        val action = (actionArabic.find(key) ?: actionEnglish.find(key) ?: actionArabicFallback.find(key))
            ?.let { base.substring(it.range).trim() }

        // ---- type ------------------------------------------------------------------------
        val type = classify(key, amountEnd)
        if (type == TxType.UNKNOWN) notes += "Transaction type not recognised"

        // ---- parties / merchant ----------------------------------------------------------
        var fromParty: String? = null
        var toParty: String? = null
        var merchant: String? = null
        when (type) {
            TxType.INCOMING_TRANSFER -> fromParty = extract(fromArabic, key, base) ?: extract(fromEnglish, key, base)
            TxType.OUTGOING_TRANSFER -> toParty = extract(toArabic, key, base) ?: extract(toEnglish, key, base)
            else -> merchant = extract(merchantRegex, key, base)
        }

        val suffix = suffixRegex.find(key)?.groupValues?.get(1)
        val reference = referenceRegex.find(key)?.groups?.get(1)?.let { base.substring(it.range) }
        val time = findTime(key)

        // ---- date ------------------------------------------------------------------------
        val d = findDate(key)
        if (d.fullDate == null) {
            when {
                d.month != null -> notes += "Year not written in SMS – please confirm the date"
                else -> notes += "Transaction date not found"
            }
        }
        if (d.note != null) notes += d.note
        if (d.fullDate != null && sms.timestamp != null) {
            val received = Instant.ofEpochMilli(sms.timestamp).atZone(zone).toLocalDate()
            if (kotlin.math.abs(ChronoUnit.DAYS.between(d.fullDate, received)) > 45) {
                notes += "Date is far from the SMS received date – please check"
            }
        }

        val dedupKey = when {
            reference != null -> "REF:${reference.uppercase()}|${amount?.toPlainString()}|$currency"
            d.text != null && time != null -> "TXT:" + sha256(base)
            sms.timestamp != null -> "SMS:${sms.sender}|${sms.timestamp}|" + sha256(base)
            else -> "TXT:" + sha256(base)
        }

        return Transaction(
            dedupKey = dedupKey,
            sender = sms.sender,
            smsTimestamp = sms.timestamp,
            originalText = sms.body,
            date = d.fullDate,
            dateText = d.text,
            partialMonth = d.month,
            partialDay = d.day,
            time = time,
            action = action,
            type = type,
            amount = amount,
            currency = currency,
            fromParty = fromParty,
            toParty = toParty,
            merchant = merchant,
            accountSuffix = suffix,
            reference = reference,
            balance = balance,
            balanceCurrency = balanceCurrency,
            needsReview = notes.isNotEmpty(),
            reviewNotes = notes.joinToString("; "),
        )
    }

    internal fun classify(key: String, amountEnd: Int): TxType {
        if (refundRegex.containsMatchIn(key)) return TxType.REFUND
        // A fee is only the transaction itself when the fee word comes before / right after the amount
        // (e.g. "تم خصم 5 جم رسوم ..."), not when a transfer SMS merely mentions its fee at the end.
        feeRegex.find(key)?.let { if (amountEnd < 0 || it.range.first <= amountEnd + 25) return TxType.BANK_FEE }
        val isTransfer = transferRegex.containsMatchIn(key)
        if (!isTransfer && withdrawalRegex.containsMatchIn(key)) return TxType.CASH_WITHDRAWAL
        if (isTransfer) {
            val inc = inMarkers.containsMatchIn(key)
            val out = outMarkers.containsMatchIn(key)
            return when {
                inc && !out -> TxType.INCOMING_TRANSFER
                out && !inc -> TxType.OUTGOING_TRANSFER
                inc && out -> {
                    val si = strongIn.containsMatchIn(key)
                    val so = strongOut.containsMatchIn(key)
                    when {
                        si && !so -> TxType.INCOMING_TRANSFER
                        so && !si -> TxType.OUTGOING_TRANSFER
                        else -> TxType.UNKNOWN
                    }
                }
                else -> TxType.UNKNOWN
            }
        }
        if (cardRegex.containsMatchIn(key) && debitRegex.containsMatchIn(key)) return TxType.CARD_PURCHASE
        if (depositRegex.containsMatchIn(key)) return TxType.DEPOSIT
        return TxType.UNKNOWN
    }

    private fun extract(regex: Regex, key: String, base: String): String? {
        val g = regex.find(key)?.groups?.get(1) ?: return null
        val value = base.substring(g.range).trim().trim('-', ':', ',', '،')
        return value.takeIf { it.isNotEmpty() && it.length <= 60 }
    }

    private fun findTime(key: String): String? {
        for (m in timeRegex.findAll(key)) {
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val ampm = m.groupValues[4]
            if (min > 59) continue
            when (ampm) {
                "pm", "م" -> { if (h !in 1..12) continue; if (h != 12) h += 12 }
                "am", "ص" -> { if (h !in 1..12) continue; if (h == 12) h = 0 }
                else -> if (h > 23) continue
            }
            return "%02d:%02d".format(h, min)
        }
        return null
    }

    internal data class DateFound(
        val fullDate: LocalDate? = null,
        val month: Int? = null,
        val day: Int? = null,
        val text: String? = null,
        val note: String? = null,
    )

    internal fun findDate(key: String): DateFound {
        isoDate.find(key)?.let { m ->
            val (y, mo, d) = m.destructured
            date(y.toInt(), mo.toInt(), d.toInt())?.let { return DateFound(it, it.monthValue, it.dayOfMonth, m.value) }
        }
        dmyFull.find(key)?.let { m ->
            val (a, b, y) = m.destructured
            dayMonth(a.toInt(), b.toInt(), y.toInt(), m.value)?.let { return it }
        }
        dmyShort.find(key)?.let { m ->
            val (a, b, y) = m.destructured
            dayMonth(a.toInt(), b.toInt(), 2000 + y.toInt(), m.value)?.let { return it }
        }
        textDate.find(key)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = monthNames.first { m.groupValues[2] == it.first }.second
            val yText = m.groupValues[3]
            if (yText.isNotEmpty()) {
                val y = if (yText.length == 2) 2000 + yText.toInt() else yText.toInt()
                date(y, month, day)?.let { return DateFound(it, month, day, m.value.trim()) }
            } else if (date(2000, month, day) != null) {
                return DateFound(null, month, day, m.value.trim())
            }
        }
        partialDate.find(key)?.let { m ->
            val a = m.groupValues[1].toInt()
            val sep = m.groupValues[2]
            val b = m.groupValues[3].toInt()
            // "09-24" (dash) is month-day as used by NBE; "24/09" (slash) is day/month.
            var (month, day) = if (sep == "-") a to b else b to a
            if (month > 12 && day <= 12) { val t = month; month = day; day = t }
            if (date(2000, month, day) == null) return DateFound()
            val note = if (a <= 12 && b <= 12 && a != b) "Day/month order is uncertain" else null
            return DateFound(null, month, day, m.value, note)
        }
        return DateFound()
    }

    private fun dayMonth(a: Int, b: Int, y: Int, text: String): DateFound? {
        date(y, b, a)?.let { return DateFound(it, it.monthValue, it.dayOfMonth, text) }
        // Month written first (e.g. 09/27/2026) – only accepted when it is the only valid reading.
        date(y, a, b)?.let { return DateFound(it, it.monthValue, it.dayOfMonth, text) }
        return null
    }

    private fun date(y: Int, m: Int, d: Int): LocalDate? =
        if (y !in 2000..2100 || m !in 1..12 || d !in 1..31) null
        else runCatching { LocalDate.of(y, m, d) }.getOrNull()

    private fun toDecimal(text: String): BigDecimal? = runCatching { BigDecimal(text.replace(",", "")) }.getOrNull()

    private fun currencyCode(alias: String): String? {
        for ((pattern, code) in currencyAliases) if (Regex(pattern).matches(alias)) return code
        return null
    }

    /**
     * Suggests the year for a day/month-only date from the SMS received time: the latest
     * occurrence of that day that is not after the received date. The user must confirm it.
     */
    fun suggestDate(month: Int, day: Int, smsTimestamp: Long?, zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
        if (smsTimestamp == null) return null
        val received = Instant.ofEpochMilli(smsTimestamp).atZone(zone).toLocalDate()
        for (y in listOf(received.year, received.year - 1)) {
            val candidate = date(y, month, day) ?: continue
            if (!candidate.isAfter(received.plusDays(1))) return candidate
        }
        return null
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
