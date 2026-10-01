package com.smsextractor.app.data

import android.content.ContentResolver
import android.provider.Telephony
import com.smsextractor.core.RawSms

/** Reads the phone's existing SMS inbox. Only called when the user taps a button. */
object SmsReader {

    /** Every sender address/name found in the inbox, with its number of messages. */
    fun senders(resolver: ContentResolver): Map<String, Int> {
        val counts = HashMap<String, Int>()
        resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0)?.trim().orEmpty()
                if (address.isNotEmpty()) counts[address] = (counts[address] ?: 0) + 1
            }
        }
        return counts
    }

    /** Messages from the given senders (null = all senders). */
    fun messages(resolver: ContentResolver, senders: Set<String>?): List<RawSms> {
        val out = ArrayList<RawSms>()
        resolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, Telephony.Sms.DATE + " ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0)?.trim().orEmpty()
                if (address.isEmpty() || (senders != null && address !in senders)) continue
                val body = c.getString(1) ?: continue
                out += RawSms(address, body, c.getLong(2))
            }
        }
        return out
    }
}
