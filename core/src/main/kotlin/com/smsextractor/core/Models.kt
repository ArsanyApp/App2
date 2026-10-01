package com.smsextractor.core

import java.math.BigDecimal
import java.time.LocalDate

/** Transaction categories. The original bank wording is kept separately in [Transaction.action]. */
enum class TxType(val english: String, val arabic: String) {
    INCOMING_TRANSFER("Incoming transfer", "تحويل وارد"),
    OUTGOING_TRANSFER("Outgoing transfer", "تحويل صادر"),
    CARD_PURCHASE("Card purchase/payment", "شراء بالبطاقة"),
    CASH_WITHDRAWAL("Cash withdrawal", "سحب نقدي"),
    DEPOSIT("Deposit", "إيداع"),
    BANK_FEE("Bank fee", "رسوم بنكية"),
    REFUND("Refund/reversal", "استرداد"),
    UNKNOWN("Other/unknown", "غير معروف");

    val label: String get() = "$english ($arabic)"
}

/**
 * One extracted transaction.
 *
 * [date] is only set when the full date (day, month AND year) is known — either written in the
 * SMS or confirmed by the user. When the SMS only has day and month, [date] stays null,
 * [partialMonth]/[partialDay] keep what was found and [needsReview] is true.
 */
data class Transaction(
    val id: Long = 0,
    val dedupKey: String,
    val sender: String,
    val smsTimestamp: Long?,
    val originalText: String,
    val date: LocalDate?,
    val dateText: String?,
    val partialMonth: Int?,
    val partialDay: Int?,
    val time: String?,
    val action: String?,
    val type: TxType,
    val amount: BigDecimal?,
    val currency: String?,
    val fromParty: String?,
    val toParty: String?,
    val merchant: String?,
    val accountSuffix: String?,
    val reference: String?,
    val balance: BigDecimal?,
    val balanceCurrency: String?,
    val needsReview: Boolean,
    val reviewNotes: String,
    val userEdited: Boolean = false,
)

/** A raw SMS (from the inbox or pasted by hand). */
data class RawSms(
    val sender: String,
    val body: String,
    /** Time the phone received the SMS (epoch millis); null for pasted text. */
    val timestamp: Long?,
)
