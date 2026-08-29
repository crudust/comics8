package com.comics8.core.source.local


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

        var ia = 0
        var ib = 0
        val lenA = a.length
        val lenB = b.length

        while (ia < lenA && ib < lenB) {
            val isDigitA = a[ia] in '0'..'9'
            val startA = ia
            while (ia < lenA && (a[ia] in '0'..'9') == isDigitA) ia++
            val endA = ia

            val isDigitB = b[ib] in '0'..'9'
            val startB = ib
            while (ib < lenB && (b[ib] in '0'..'9') == isDigitB) ib++
            val endB = ib

            if (isDigitA && isDigitB) {
                var nonZeroA = startA
                while (nonZeroA < endA && a[nonZeroA] == '0') nonZeroA++
                var nonZeroB = startB
                while (nonZeroB < endB && b[nonZeroB] == '0') nonZeroB++

                val numLenA = endA - nonZeroA
                val numLenB = endB - nonZeroB

                if (numLenA != numLenB) return numLenA.compareTo(numLenB)

                for (k in 0 until numLenA) {
                    val ca = a[nonZeroA + k]
                    val cb = b[nonZeroB + k]
                    if (ca != cb) return ca.compareTo(cb)
                }

                val rawLenA = endA - startA
                val rawLenB = endB - startB
                if (rawLenA != rawLenB) return rawLenA.compareTo(rawLenB)
            } else {
                val tLenA = endA - startA
                val tLenB = endB - startB
                val minLen = minOf(tLenA, tLenB)
                for (k in 0 until minLen) {
                    var ca = a[startA + k]
                    var cb = b[startB + k]
                    if (ca != cb) {
                        ca = ca.uppercaseChar()
                        cb = cb.uppercaseChar()
                        if (ca != cb) {
                            ca = ca.lowercaseChar()
                            cb = cb.lowercaseChar()
                            if (ca != cb) return ca.compareTo(cb)
                        }
                    }
                }
                if (tLenA != tLenB) return tLenA.compareTo(tLenB)
            }
        }

        val tokensRemainingA = countRemainingTokens(a, ia)
        val tokensRemainingB = countRemainingTokens(b, ib)
        if (tokensRemainingA != tokensRemainingB) {
            return tokensRemainingA.compareTo(tokensRemainingB)
        }

        return a.compareTo(b)
    }

    private fun countRemainingTokens(s: String, from: Int): Int {
        if (from >= s.length) return 0
        var count = 0
        var i = from
        while (i < s.length) {
            val isDigit = s[i] in '0'..'9'
            count++
            while (i < s.length && (s[i] in '0'..'9') == isDigit) i++
        }
        return count
    }
}
