package com.smsextractor.core

import java.math.BigDecimal
import java.time.LocalDate

/** Builds an Excel-friendly CSV (UTF-8 with BOM so Arabic text opens correctly in Excel). */
object CsvExporter {
    const val BOM = "﻿"

    val HEADER = listOf(
        "Date", "Time", "Action", "Transaction type", "Amount", "Currency",
        "Sender/person", "Recipient/person", "Merchant", "Account/card suffix",
        "Reference number", "Available balance", "SMS sender ID", "Needs review", "Original SMS",
    )

    fun export(
        transactions: List<Transaction>,
        senders: Set<String>?,
        start: LocalDate,
        end: LocalDate,
    ): String {
        val sb = StringBuilder(BOM)
        row(sb, HEADER)
        for (t in transactions) {
            row(
                sb, listOf(
                    t.date?.toString() ?: (t.dateText ?: ""),
                    t.time ?: "",
                    t.action ?: "",
                    t.type.english,
                    t.amount?.toPlainString() ?: "",
                    t.currency ?: "",
                    t.fromParty ?: "",
                    t.toParty ?: "",
                    t.merchant ?: "",
                    t.accountSuffix ?: "",
                    t.reference ?: "",
                    t.balance?.let { b -> b.toPlainString() + (t.balanceCurrency?.let { " $it" } ?: "") } ?: "",
                    t.sender,
                    if (t.needsReview) "Yes" else "No",
                    t.originalText,
                )
            )
        }

        // Summary section – computed from exactly the rows above.
        val totals = TotalsCalculator.calculate(transactions)
        sb.append("\r\n")
        row(sb, listOf("SUMMARY"))
        row(sb, listOf("Selected senders", senders?.sorted()?.joinToString(" | ") ?: "All senders"))
        row(sb, listOf("Date range", "$start to $end"))
        row(sb, listOf("Transaction count", totals.transactionCount.toString()))
        if (totals.missingAmountCount > 0) {
            row(sb, listOf("Transactions without amount/currency (not in totals)", totals.missingAmountCount.toString()))
        }
        sb.append("\r\n")
        row(sb, listOf("Transaction type", "Currency", "Count", "Total amount"))
        for (tt in totals.byType) row(sb, listOf(tt.type.english, tt.currency, tt.count.toString(), tt.total.toPlainString()))
        sb.append("\r\n")
        for ((cur, v) in totals.incomingByCurrency) row(sb, listOf("Grand total incoming transfers", cur, "", v.toPlainString()))
        for ((cur, v) in totals.outgoingByCurrency) row(sb, listOf("Grand total outgoing (transfers+purchases+withdrawals+fees)", cur, "", v.toPlainString()))
        return sb.toString()
    }

    private fun row(sb: StringBuilder, cells: List<String>) {
        sb.append(cells.joinToString(",") { escape(it) }).append("\r\n")
    }

    fun escape(value: String): String {
        var v = value.replace("\r\n", "\n").replace('\r', '\n')
        // Stop Excel from treating text such as "=..." as a formula. Plain numbers are left alone.
        if (v.isNotEmpty() && v[0] in "=+-@" && runCatching { BigDecimal(v) }.isFailure) v = "'$v"
        return if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }
}
