package com.comics8.core.source.local

import java.math.BigInteger

/**
 * Tokenize into non-digit runs + digit runs.
 * Digit runs compare as BigInteger values (`1` == `01` == `001`);
 * equal values put the shorter digit string first (`1` < `01` < `001`).
 * Non-digits use [String.CASE_INSENSITIVE_ORDER]. Original string is the last tiebreaker.
 */
object NaturalSort : Comparator<String> {
    override fun compare(a: String?, b: String?): Int {
        if (a === b) return 0
        if (a == null) return -1
        if (b == null) return 1
        val left = tokens(a)
        val right = tokens(b)
        val n = minOf(left.size, right.size)
        for (i in 0 until n) {
            val c = compareToken(left[i], right[i])
            if (c != 0) return c
        }
        val len = left.size.compareTo(right.size)
        if (len != 0) return len
        return a.compareTo(b)
    }

    private data class Token(val raw: String, val number: BigInteger?)

    private fun tokens(s: String): List<Token> {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<Token>()
        val buf = StringBuilder()
        var inDigit = s[0] in '0'..'9'
        fun flush() {
            if (buf.isEmpty()) return
            val raw = buf.toString()
            out += if (inDigit) Token(raw, raw.toBigInteger()) else Token(raw, null)
            buf.clear()
        }
        for (ch in s) {
            val digit = ch in '0'..'9'
            if (digit != inDigit) {
                flush()
                inDigit = digit
            }
            buf.append(ch)
        }
        flush()
        return out
    }

    private fun compareToken(a: Token, b: Token): Int {
        val an = a.number
        val bn = b.number
        if (an != null && bn != null) {
            val value = an.compareTo(bn)
            if (value != 0) return value
            return a.raw.length.compareTo(b.raw.length)
        }
        return String.CASE_INSENSITIVE_ORDER.compare(a.raw, b.raw)
    }
}
