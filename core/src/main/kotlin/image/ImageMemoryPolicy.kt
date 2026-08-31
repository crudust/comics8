package com.comics8.core.image

enum class ImageCacheRole {
    GRID,
    EPISODE,
    READER;

    private val keyPrefix: String
        get() = "comics8:${name.lowercase()}:"

    fun cacheKey(identity: String): String = keyPrefix + identity

    companion object {
        fun fromCacheKey(key: String): ImageCacheRole? = entries.firstOrNull { role ->
            key.startsWith(role.keyPrefix)
        }
    }
}

data class ImageMemoryBudgets(
    val gridBytes: Long,
    val episodeBytes: Long,
    val readerBytes: Long,
) {
    init {
        require(gridBytes >= 0L)
        require(episodeBytes >= 0L)
        require(readerBytes >= 0L)
    }

    val totalBytes: Long get() = gridBytes + episodeBytes + readerBytes

    operator fun get(role: ImageCacheRole): Long = when (role) {
        ImageCacheRole.GRID -> gridBytes
        ImageCacheRole.EPISODE -> episodeBytes
        ImageCacheRole.READER -> readerBytes
    }
}

object ImageMemoryPolicy {
    private const val GRID_PERCENT = 32L
    private const val EPISODE_PERCENT = 5L

    fun partition(totalBytes: Long): ImageMemoryBudgets {
        require(totalBytes >= 0L)
        val grid = totalBytes / 100L * GRID_PERCENT + totalBytes % 100L * GRID_PERCENT / 100L
        val episode = totalBytes / 100L * EPISODE_PERCENT + totalBytes % 100L * EPISODE_PERCENT / 100L
        return ImageMemoryBudgets(
            gridBytes = grid,
            episodeBytes = episode,
            readerBytes = totalBytes - grid - episode,
        )
    }
}
