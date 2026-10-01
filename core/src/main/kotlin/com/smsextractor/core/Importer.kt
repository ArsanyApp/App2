package com.smsextractor.core

import java.time.ZoneId

/** Turns raw SMS into new transactions, dropping non-transactions and anything already imported. */
object Importer {
    data class Result(val newTransactions: List<Transaction>, val duplicates: Int, val notTransactions: Int)

    fun process(messages: List<RawSms>, existingKeys: Set<String>, zone: ZoneId = ZoneId.systemDefault()): Result {
        val seen = existingKeys.toMutableSet()
        val out = mutableListOf<Transaction>()
        var duplicates = 0
        var skipped = 0
        for (sms in messages) {
            val t = SmsParser.parse(sms, zone)
            when {
                t == null -> skipped++
                !seen.add(t.dedupKey) -> duplicates++
                else -> out += t
            }
        }
        return Result(out, duplicates, skipped)
    }

    /** Splits pasted text into separate messages (messages separated by an empty line). */
    fun splitPasted(text: String): List<String> =
        text.replace("\r\n", "\n").split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
}
