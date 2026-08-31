package com.comics8.core.image

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageMemoryPolicyTest {
    @Test
    fun partition_preservesTotalAndRoleShares() {
        val total = 304L * 1024L * 1024L

        val budgets = ImageMemoryPolicy.partition(total)

        assertThat(budgets.totalBytes).isEqualTo(total)
        assertThat(budgets.gridBytes).isEqualTo(total * 32L / 100L)
        assertThat(budgets.episodeBytes).isEqualTo(total * 5L / 100L)
        assertThat(budgets.readerBytes).isEqualTo(total - budgets.gridBytes - budgets.episodeBytes)
    }

    @Test
    fun cacheKey_roundTripsRole() {
        ImageCacheRole.entries.forEach { role ->
            val key = role.cacheKey("https://example.test/image.jpg")
            assertThat(ImageCacheRole.fromCacheKey(key)).isEqualTo(role)
        }
        assertThat(ImageCacheRole.fromCacheKey("unclassified")).isNull()
    }
}
