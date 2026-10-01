package com.smsextractor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

class FilterTotalsCsvTest {

    private var nextId = 1L

    private fun tx(
        sender: String,
        date: LocalDate?,
        type: TxType,
        amount: String?,
        currency: String? = "EGP",
        balance: String? = null,
        key: String? = null,
    ) = Transaction(
        id = nextId++,
        dedupKey = key ?: "K${nextId}",
        sender = sender,
        smsTimestamp = null,
        originalText = "sms",
        date = date,
        dateText = date?.toString(),
        partialMonth = date?.monthValue,
        partialDay = date?.dayOfMonth,
        time = "10:00",
        action = "تم",
        type = type,
        amount = amount?.let(::BigDecimal),
        currency = currency,
        fromParty = null, toParty = null, merchant = null, accountSuffix = null, reference = null,
        balance = balance?.let(::BigDecimal), balanceCurrency = balance?.let { "EGP" },
        needsReview = false, reviewNotes = "",
    )

    private val d24 = LocalDate.of(2026, 9, 24)
    private val d25 = LocalDate.of(2026, 9, 25)
    private val d26 = LocalDate.of(2026, 9, 26)

    private val data = listOf(
        tx("NBE", d24, TxType.INCOMING_TRANSFER, "1000.10"),
        tx("NBE", d24, TxType.INCOMING_TRANSFER, "500.20"),
        tx("NBE", d24, TxType.OUTGOING_TRANSFER, "700"),
        tx("CIB", d24, TxType.CARD_PURCHASE, "450.00", balance = "99999"),
        tx("CIB", d24, TxType.CASH_WITHDRAWAL, "1000"),
        tx("NBE", d24, TxType.DEPOSIT, "2000"),
        tx("NBE", d24, TxType.BANK_FEE, "25"),
        tx("CIB", d24, TxType.REFUND, "100"),
        tx("BankX", d24, TxType.UNKNOWN, "50"),
        tx("CIB", d24, TxType.CARD_PURCHASE, "20.00", currency = "USD"),
        tx("NBE", d25, TxType.INCOMING_TRANSFER, "300"),
        tx("NBE", d26, TxType.OUTGOING_TRANSFER, "10"),
        tx("NBE", null, TxType.INCOMING_TRANSFER, "400"), // year not confirmed yet
    )

    private fun totalOf(t: Totals, type: TxType, cur: String = "EGP") = t.byType.single { it.type == type && it.currency == cur }

    @Test
    fun singleDay_allSenders_separateTotalsPerType() {
        val rows = Filtering.apply(data, TransactionFilter(null, d24, d24))
        assertEquals(10, rows.size)
        val t = TotalsCalculator.calculate(rows)
        assertEquals(10, t.transactionCount)
        assertEquals(BigDecimal("1500.30"), totalOf(t, TxType.INCOMING_TRANSFER).total)
        assertEquals(2, totalOf(t, TxType.INCOMING_TRANSFER).count)
        assertEquals(BigDecimal("700"), totalOf(t, TxType.OUTGOING_TRANSFER).total)
        assertEquals(BigDecimal("450.00"), totalOf(t, TxType.CARD_PURCHASE).total) // balance 99999 NOT included
        assertEquals(BigDecimal("1000"), totalOf(t, TxType.CASH_WITHDRAWAL).total)
        assertEquals(BigDecimal("2000"), totalOf(t, TxType.DEPOSIT).total)
        assertEquals(BigDecimal("25"), totalOf(t, TxType.BANK_FEE).total)
        assertEquals(BigDecimal("100"), totalOf(t, TxType.REFUND).total)
        assertEquals(BigDecimal("50"), totalOf(t, TxType.UNKNOWN).total)
        // Different currency kept apart
        assertEquals(BigDecimal("20.00"), totalOf(t, TxType.CARD_PURCHASE, "USD").total)
        // Grand totals: incoming transfers only; refunds/deposits not counted as incoming transfers
        assertEquals(mapOf("EGP" to BigDecimal("1500.30")), t.incomingByCurrency)
        assertEquals(BigDecimal("2175.00"), t.outgoingByCurrency["EGP"]) // 700 + 450 + 1000 + 25
        assertEquals(BigDecimal("20.00"), t.outgoingByCurrency["USD"])
    }

    @Test
    fun multipleSenders() {
        val rows = Filtering.apply(data, TransactionFilter(setOf("CIB", "BankX"), d24, d24))
        assertEquals(setOf("CIB", "BankX"), rows.map { it.sender }.toSet())
        assertEquals(5, rows.size)
        val oneSender = Filtering.apply(data, TransactionFilter(setOf("NBE"), d24, d24))
        assertTrue(oneSender.all { it.sender == "NBE" })
        assertEquals(5, oneSender.size)
    }

    @Test
    fun dateRange_andRecalculationWhenFiltersChange() {
        val day = TotalsCalculator.calculate(Filtering.apply(data, TransactionFilter(setOf("NBE"), d24, d24)))
        val range = TotalsCalculator.calculate(Filtering.apply(data, TransactionFilter(setOf("NBE"), d24, d26)))
        val later = TotalsCalculator.calculate(Filtering.apply(data, TransactionFilter(setOf("NBE"), d25, d26)))
        assertEquals(BigDecimal("1500.30"), totalOf(day, TxType.INCOMING_TRANSFER).total)
        assertEquals(BigDecimal("1800.30"), totalOf(range, TxType.INCOMING_TRANSFER).total)
        assertEquals(BigDecimal("710"), totalOf(range, TxType.OUTGOING_TRANSFER).total)
        assertEquals(BigDecimal("300"), totalOf(later, TxType.INCOMING_TRANSFER).total)
        assertEquals(2, later.transactionCount)
    }

    @Test
    fun undatedTransactionsAreNotPutOnAnyDay() {
        val rows = Filtering.apply(data, TransactionFilter(null, LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1)))
        assertFalse(rows.any { it.date == null })
        assertEquals(1, Filtering.undated(data, TransactionFilter(setOf("NBE"), d24, d24)).size)
        assertEquals(0, Filtering.undated(data, TransactionFilter(setOf("CIB"), d24, d24)).size)
    }

    @Test
    fun sameTransactionNeverCountedTwice() {
        val a = tx("NBE", d24, TxType.INCOMING_TRANSFER, "100", key = "SAME")
        val b = a.copy(id = 999)
        val rows = Filtering.apply(listOf(a, b), TransactionFilter(null, d24, d24))
        assertEquals(1, rows.size)
        assertEquals(BigDecimal("100"), TotalsCalculator.calculate(listOf(a, b)).incomingByCurrency["EGP"])
    }

    @Test
    fun exactDecimalArithmetic() {
        val rows = (1..10).map { tx("NBE", d24, TxType.BANK_FEE, "0.10") }
        assertEquals(BigDecimal("1.00"), TotalsCalculator.calculate(rows).byType.single().total)
    }

    @Test
    fun missingAmountNotSummed() {
        val rows = listOf(tx("NBE", d24, TxType.UNKNOWN, null), tx("NBE", d24, TxType.UNKNOWN, "5"))
        val t = TotalsCalculator.calculate(rows)
        assertEquals(2, t.transactionCount)
        assertEquals(1, t.missingAmountCount)
        assertEquals(BigDecimal("5"), t.byType.single().total)
    }

    @Test
    fun duplicateImports() {
        val msgs = listOf(
            RawSms("NBE", SmsParserTest.SAMPLE_1, 1000L),
            RawSms("NBE", SmsParserTest.SAMPLE_2, 2000L),
            RawSms("NBE", SmsParserTest.SAMPLE_3, 3000L),
            RawSms("NBE", "كود التفعيل 1234", 4000L),
        )
        val first = Importer.process(msgs, emptySet(), ZoneOffset.UTC)
        assertEquals(3, first.newTransactions.size)
        assertEquals(1, first.notTransactions)
        val second = Importer.process(msgs, first.newTransactions.map { it.dedupKey }.toSet(), ZoneOffset.UTC)
        assertEquals(0, second.newTransactions.size)
        assertEquals(3, second.duplicates)
        // Same SMS twice in one batch, or pasted by hand after importing from the inbox.
        val pasted = Importer.process(listOf(RawSms("Pasted", SmsParserTest.SAMPLE_1, null)), first.newTransactions.map { it.dedupKey }.toSet())
        assertEquals(1, pasted.duplicates)
    }

    @Test
    fun splitPastedMessages() {
        val parts = Importer.splitPasted("${SmsParserTest.SAMPLE_1}\n\n${SmsParserTest.SAMPLE_2}\r\n\r\n  \n${SmsParserTest.SAMPLE_3}")
        assertEquals(3, parts.size)
    }

    @Test
    fun csvExport() {
        val rows = Filtering.apply(data, TransactionFilter(setOf("CIB"), d24, d24))
        val withArabic = rows + tx("CIB", d24, TxType.INCOMING_TRANSFER, "15", key = "AR").copy(
            fromParty = "بسمه، على \"حسن\"", originalText = "سطر1\nسطر2"
        )
        val csv = CsvExporter.export(withArabic, setOf("CIB"), d24, d24)
        assertTrue(csv.startsWith("﻿"))
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertEquals(CsvExporter.HEADER.joinToString(","), lines[0])
        assertTrue(csv.contains("\"بسمه، على \"\"حسن\"\"\"")) // Arabic kept, quotes escaped
        assertTrue(csv.contains("\"سطر1\nسطر2\""))
        assertTrue(csv.contains("Selected senders,CIB"))
        assertTrue(csv.contains("Date range,2026-09-24 to 2026-09-24"))
        assertTrue(csv.contains("Transaction count,5"))
        assertTrue(csv.contains("Card purchase/payment,EGP,1,450.00"))
        assertTrue(csv.contains("Card purchase/payment,USD,1,20.00"))
        assertTrue(csv.contains("Cash withdrawal,EGP,1,1000"))
        assertTrue(csv.contains("Refund/reversal,EGP,1,100"))
        assertTrue(csv.contains("Incoming transfer,EGP,1,15"))
        // Summary totals match the exported rows
        val exportedAmounts = lines.drop(1).takeWhile { it.isNotEmpty() }.count()
        assertEquals(5, exportedAmounts)
        assertEquals("'=SUM(A1)", CsvExporter.escape("=SUM(A1)"))
        assertEquals("-5", CsvExporter.escape("-5"))
    }
}
