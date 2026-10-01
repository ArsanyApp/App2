package com.smsextractor.core

/**
 * Text helpers. Normalisation is done in two steps so extracted values (names, merchants) keep
 * their original spelling:
 *  - [base]: digits converted to ASCII, tashkeel/tatweel removed, whitespace collapsed.
 *  - [matchKey]: same length as [base], with Arabic letter variants unified and lower-cased, so
 *    regexes can be run on the key and the matched ranges cut out of the base text.
 */
object TextNormalizer {

    fun base(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                in '٠'..'٩' -> sb.append('0' + (c - '٠')) // Arabic-Indic digits
                in '۰'..'۹' -> sb.append('0' + (c - '۰')) // Eastern Arabic-Indic (Persian)
                '٫' -> sb.append('.') // Arabic decimal separator
                '٬' -> sb.append(',') // Arabic thousands separator
                'ـ' -> {} // tatweel
                in 'ً'..'ٟ', 'ٰ' -> {} // tashkeel (diacritics)
                '‎', '‏', '​', '‪', '‫', '‬', '‭', '‮', '⁦', '⁧', '⁨', '⁩', '﻿' -> {} // direction marks
                ' ', '\t', '\r', '\n' -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return sb.toString().replace(Regex(" {2,}"), " ").trim()
    }

    fun matchKey(base: String): String {
        val sb = StringBuilder(base.length)
        for (c in base) {
            sb.append(
                when (c) {
                    'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                    'ة' -> 'ه'
                    'ى' -> 'ي'
                    'ؤ' -> 'و'
                    'ئ' -> 'ي'
                    else -> c.lowercaseChar()
                }
            )
        }
        return sb.toString()
    }

    /** Converts a user-typed number (any digits, commas) to a plain decimal string, or null. */
    fun cleanNumber(text: String?): String? {
        if (text == null) return null
        val t = base(text).replace(",", "").replace(" ", "")
        return if (Regex("""\d+(\.\d+)?""").matches(t)) t else null
    }
}
