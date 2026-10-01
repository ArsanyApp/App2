package com.smsextractor.core

import java.math.BigDecimal
import java.time.LocalDate

/** The filters chosen on the main screen. [senders] = null means "All senders". */
data class TransactionFilter(
    val senders: Set<String>?,
    val start: LocalDate,
    val end: LocalDate,
) {
    fun matchesSender(t: Transaction): Boolean = senders == null || t.sender in senders

    /** Only transactions with a known, full date can fall inside a date range. */
    fun matches(t: Transaction): Boolean {
        val d = t.date ?: return false
        return matchesSender(t) && !d.isBefore(start) && !d.isAfter(end)
    }
}

object Filtering {
    fun apply(all: List<Transaction>, filter: TransactionFilter): List<Transaction> =
        all.asSequence()
            .filter { filter.matches(it) }
            .distinctBy { it.dedupKey } // never show / count the same transaction twice
            .sortedWith(compareBy<Transaction>({ it.date }, { it.time ?: "" }, { it.id }))
            .toList()

    /** Transactions from the selected senders that have no confirmed date (shown for review). */
    fun undated(all: List<Transaction>, filter: TransactionFilter): List<Transaction> =
        all.filter { it.date == null && filter.matchesSender(it) }
}

data class TypeTotal(val type: TxType, val currency: String, val count: Int, val total: BigDecimal)

data class Totals(
    /** One row per (type, currency); different currencies are never added together. */
    val byType: List<TypeTotal>,
    val transactionCount: Int,
    /** Transactions counted in [transactionCount] whose amount or currency is missing (not summed). */
    val missingAmountCount: Int,
    /** Grand total of incoming transfers, per currency. */
    val incomingByCurrency: Map<String, BigDecimal>,
    /** Grand total of money going out (outgoing transfers, card purchases, cash withdrawals, bank fees), per currency. */
    val outgoingByCurrency: Map<String, BigDecimal>,
)

object TotalsCalculator {
    val OUTGOING_TYPES = setOf(TxType.OUTGOING_TRANSFER, TxType.CARD_PURCHASE, TxType.CASH_WITHDRAWAL, TxType.BANK_FEE)

    fun calculate(transactions: List<Transaction>): Totals {
        val unique = transactions.distinctBy { it.dedupKey }
        val withAmount = unique.filter { it.amount != null && !it.currency.isNullOrBlank() }

        val byType = withAmount
            .groupBy { it.type to it.currency!! }
            .map { (k, list) -> TypeTotal(k.first, k.second, list.size, sum(list)) }
            .sortedWith(compareBy({ it.type.ordinal }, { it.currency }))

        fun grand(types: Set<TxType>): Map<String, BigDecimal> =
            withAmount.filter { it.type in types }.groupBy { it.currency!! }
                .mapValues { sum(it.value) }.toSortedMap()

        return Totals(
            byType = byType,
            transactionCount = unique.size,
            missingAmountCount = unique.size - withAmount.size,
            incomingByCurrency = grand(setOf(TxType.INCOMING_TRANSFER)),
            outgoingByCurrency = grand(OUTGOING_TYPES),
        )
    }

    // BigDecimal addition is exact – no floating point rounding.
    private fun sum(list: List<Transaction>): BigDecimal = list.fold(BigDecimal.ZERO) { acc, t -> acc + t.amount!! }
}
