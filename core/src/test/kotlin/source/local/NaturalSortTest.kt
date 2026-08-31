package com.comics8.core.source.local

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaturalSortTest {
    @Test
    fun numericChunksCompareByValueThenShorterDigitString() {
        val sorted = listOf("10.jpg", "001.jpg", "2.jpg", "01.jpg", "1.jpg").sortedWith(NaturalSort)
        assertThat(sorted).containsExactly("1.jpg", "01.jpg", "001.jpg", "2.jpg", "10.jpg").inOrder()
    }

    @Test
    fun embeddedNumbersBeatLexicographicOrder() {
        val sorted = listOf("page10.png", "page2.png", "page1.png").sortedWith(NaturalSort)
        assertThat(sorted).containsExactly("page1.png", "page2.png", "page10.png").inOrder()
    }

    @Test
    fun nonDigitsAreCaseInsensitiveWithOriginalTiebreaker() {
        assertThat(NaturalSort.compare("A.jpg", "a.jpg")).isLessThan(0)
        assertThat(NaturalSort.compare("B.JPG", "a.jpg")).isGreaterThan(0)
        val sorted = listOf("b.jpg", "A.jpg", "a.jpg").sortedWith(NaturalSort)
        assertThat(sorted).containsExactly("A.jpg", "a.jpg", "b.jpg").inOrder()
    }

    @Test
    fun shorterTokenListWinsWhenPrefixEqual() {
        assertThat(NaturalSort.compare("img", "img1")).isLessThan(0)
    }

    @Test
    fun handlesLargeNumbersWithoutOverflow() {
        val n1 = "file1000000000000000000000000000000.jpg"
        val n2 = "file2000000000000000000000000000000.jpg"
        val n3 = "file10000000000000000000000000000000.jpg"
        val sorted = listOf(n3, n2, n1).sortedWith(NaturalSort)
        assertThat(sorted).containsExactly(n1, n2, n3).inOrder()
    }

    @Test
    fun handlesNullAndIdentical() {
        assertThat(NaturalSort.compare(null, null)).isEqualTo(0)
        assertThat(NaturalSort.compare(null, "a")).isLessThan(0)
        assertThat(NaturalSort.compare("a", null)).isGreaterThan(0)
        assertThat(NaturalSort.compare("same", "same")).isEqualTo(0)
    }
}
