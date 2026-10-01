package com.smsextractor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class SmsParserTest {
    private val zone: ZoneId = ZoneOffset.UTC

    private fun parse(body: String, sender: String = "NBE", ts: Long? = null) =
        SmsParser.parse(RawSms(sender, body, ts), zone)

    @Test
    fun sample1_incomingTransfer() {
        val t = parse(SAMPLE_1)!!
        assertEquals(TxType.INCOMING_TRANSFER, t.type)
        assertEquals("تم إضافة", t.action)
        assertEquals(BigDecimal("400.00"), t.amount)
        assertEquals("EGP", t.currency)
        assertEquals("بسمه على صلاح الدين على حسن", t.fromParty)
        assertNull(t.toParty)
        assertEquals("0110", t.accountSuffix)
        assertEquals("762823659352", t.reference)
        assertEquals("22:08", t.time)
        // Year is not in the SMS: no full date is invented, month/day are kept, and it is flagged.
        assertNull(t.date)
        assertEquals(9, t.partialMonth)
        assertEquals(24, t.partialDay)
        assertEquals("09-24", t.dateText)
        assertTrue(t.needsReview)
        assertTrue(t.reviewNotes.contains("Year"))
        assertNull(t.balance)
    }

    @Test
    fun sample2_outgoingTransfer() {
        val t = parse(SAMPLE_2)!!
        assertEquals(TxType.OUTGOING_TRANSFER, t.type)
        assertEquals("تم تنفيذ", t.action)
        assertEquals(BigDecimal("300.00"), t.amount)
        assertEquals("EGP", t.currency)
        assertEquals("NADA R******", t.toParty)
        assertNull(t.fromParty)
        assertEquals("0110", t.accountSuffix)
        assertEquals("597237570385", t.reference)
        assertEquals("00:32", t.time)
        assertNull(t.date)
        assertEquals(9, t.partialMonth)
        assertEquals(25, t.partialDay)
        assertTrue(t.needsReview)
    }

    @Test
    fun sample3_cardPurchase() {
        val t = parse(SAMPLE_3)!!
        assertEquals(TxType.CARD_PURCHASE, t.type)
        assertEquals("تم خصم", t.action)
        assertEquals(BigDecimal("215.72"), t.amount)
        assertEquals("EGP", t.currency)
        assertEquals("1796", t.accountSuffix)
        assertEquals("WE-Mobile-Pre Giz", t.merchant)
        assertEquals(LocalDate.of(2026, 9, 27), t.date)
        assertEquals("20:43", t.time)
        assertEquals(BigDecimal("937.87"), t.balance)
        assertEquals("EGP", t.balanceCurrency)
        assertNull(t.reference)
        assertFalse(t.reviewNotes, t.needsReview)
    }

    @Test
    fun arabicIndicDigits() {
        val t = parse("تم خصم ٢١٥٫٧٢ جم من بطاقة الخصم المباشر رقم ١٧٩٦ عند كارفور يوم ٢٧/٠٩/٢٠٢٦ الساعه ٢٠:٤٣ المتاح ١٬٩٣٧٫٨٧ جم")!!
        assertEquals(BigDecimal("215.72"), t.amount)
        assertEquals("1796", t.accountSuffix)
        assertEquals(LocalDate.of(2026, 9, 27), t.date)
        assertEquals("20:43", t.time)
        assertEquals(BigDecimal("1937.87"), t.balance)
        assertEquals("كارفور", t.merchant)
    }

    @Test
    fun englishMessages() {
        val purchase = parse("Your card ending 4321 was debited with USD 25.50 at AMAZON MKTPLACE on 15/09/2026 13:05. Available balance USD 1,000.00")!!
        assertEquals(TxType.CARD_PURCHASE, purchase.type)
        assertEquals(BigDecimal("25.50"), purchase.amount)
        assertEquals("USD", purchase.currency)
        assertEquals("4321", purchase.accountSuffix)
        assertEquals("AMAZON MKTPLACE", purchase.merchant)
        assertEquals(LocalDate.of(2026, 9, 15), purchase.date)
        assertEquals(BigDecimal("1000.00"), purchase.balance)

        val atm = parse("EGP 1,000.00 withdrawn from your account ***7788 at ATM CAIRO on 2026-09-24 10:15")!!
        assertEquals(TxType.CASH_WITHDRAWAL, atm.type)
        assertEquals(BigDecimal("1000.00"), atm.amount)
        assertEquals(LocalDate.of(2026, 9, 24), atm.date)

        val incoming = parse("Your account 5566 has been credited with EGP 1,500.00 transfer from AHMED ALI ref 99887766 on 24-09-2026")!!
        assertEquals(TxType.INCOMING_TRANSFER, incoming.type)
        assertEquals("AHMED ALI", incoming.fromParty)
        assertEquals("99887766", incoming.reference)
    }

    @Test
    fun otherTypes() {
        assertEquals(TxType.DEPOSIT, parse("تم إيداع مبلغ 2000 جم نقدا في حسابكم رقم 0110 يوم 24/09/2026")!!.type)
        assertEquals(TxType.BANK_FEE, parse("تم خصم 25 جم رسوم خدمة الرسائل من حسابكم رقم 0110 يوم 24/09/2026")!!.type)
        assertEquals(TxType.REFUND, parse("تم استرداد مبلغ 100.00 جم لبطاقتكم رقم 1796 من AMAZON يوم 24/09/2026")!!.type)
        assertEquals(TxType.CASH_WITHDRAWAL, parse("تم سحب 1000 جم من ماكينة الصراف الآلي ببطاقتكم رقم 1796 يوم 24/09/2026")!!.type)
    }

    @Test
    fun refundIsNotIncomingTransfer() {
        val t = parse("تم إضافة مبلغ 150 جم لحسابكم رقم 0110 استرداد تحويل لحظي رقم مرجعي 1234567890 يوم 24/09/2026")!!
        assertEquals(TxType.REFUND, t.type)
    }

    @Test
    fun unknownTypeIsFlagged() {
        val t = parse("تم إضافة 50.00 جم لحسابكم رقم 0110 يوم 24/09/2026")!!
        assertEquals(TxType.UNKNOWN, t.type)
        assertTrue(t.needsReview)
        assertTrue(t.reviewNotes.contains("type"))
    }

    @Test
    fun nonTransactionsAreSkipped() {
        assertNull(parse("كود التفعيل الخاص بك هو 123456 لا تشاركه مع أحد"))
        assertNull(parse("Your OTP for purchase of EGP 100.00 is 554433"))
        assertNull(parse("مرحبا بكم في البنك الأهلي"))
    }

    @Test
    fun noInventedYear_andSuggestionFromReceivedDate() {
        val t = parse(SAMPLE_1)!!
        assertNull(t.date)
        val received = LocalDate.of(2026, 9, 24).atTime(22, 9).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(LocalDate.of(2026, 9, 24), SmsParser.suggestDate(9, 24, received, zone))
        // A message received on 2 Jan about 31 Dec belongs to the previous year.
        val jan = LocalDate.of(2027, 1, 2).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(LocalDate.of(2026, 12, 31), SmsParser.suggestDate(12, 31, jan, zone))
        assertNull(SmsParser.suggestDate(9, 24, null, zone))
    }

    @Test
    fun dateFarFromReceivedDateIsFlagged() {
        val ts = LocalDate.of(2026, 3, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val t = parse(SAMPLE_3, ts = ts)!!
        assertTrue(t.needsReview)
    }

    @Test
    fun dedupKeyIsStable() {
        assertEquals(parse(SAMPLE_1)!!.dedupKey, parse(SAMPLE_1, ts = 123L)!!.dedupKey)
        assertNotNull(parse(SAMPLE_3)!!.dedupKey)
    }

    companion object {
        const val SAMPLE_1 =
            "تم إضافة تحويل لحظي لحسابكم رقم 0110 بمبلغ 400.00 جم من بسمه على صلاح الدين على حسن رقم مرجعي 762823659352 يوم 09-24 الساعة 22:08 للمزيد اتصل بـ 19623"
        const val SAMPLE_2 =
            "تم تنفيذ تحويل لحظي من حسابكم رقم 0110 بمبلغ 300.00 جم إلى NADA R****** رقم مرجعي 597237570385 يوم 09-25 الساعة 00:32 للمزيد اتصل بـ 19623"
        const val SAMPLE_3 =
            "تم خصم 215.72 EGP من بطاقة الخصم المباشر رقم 1796 عند WE-Mobile-Pre Giz يوم 27/09/26 الساعه 20:43 المتاح 937.87EGP للمزيد إتصل ب ١٩٦٢٣"
    }
}
