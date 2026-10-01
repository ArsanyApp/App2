package com.smsextractor.app.ui

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val amountFormat = DecimalFormat("#,##0.00###", DecimalFormatSymbols(Locale.US))
private val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

fun formatAmount(v: BigDecimal?): String = v?.let { amountFormat.format(it) } ?: ""

fun formatMoney(v: BigDecimal?, currency: String?): String =
    if (v == null) "" else formatAmount(v) + (currency?.let { " $it" } ?: "")

fun formatDate(d: LocalDate): String = d.format(dateFormat)
