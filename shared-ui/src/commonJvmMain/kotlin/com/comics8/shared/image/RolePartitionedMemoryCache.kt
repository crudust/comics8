package com.comics8.shared.image

import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.image.ImageMemoryBudgets

/**
 * Splits Coil's single memory cache into per-role caches (GRID, EPISODE, READER), so evicting
 * one role (for example after closing the reader) cannot evict grid thumbnails.
 */
class RolePartitionedMemoryCache(
    budgets: ImageMemoryBudgets,
) : MemoryCache {
    private val caches = ImageCacheRole.entries.associateWith { role ->
        MemoryCache.Builder()
            .maxSizeBytes(budgets[role])
            .build()
    }

    override val size: Long get() = caches.values.sumOf { it.size }

    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    override val initialMaxSize: Long get() = caches.values.sumOf { it.initialMaxSize }

    override var maxSize: Long
        get() = caches.values.sumOf { it.maxSize }
        set(value) {
            val share = value / caches.size.coerceAtLeast(1)
            caches.values.forEach { it.maxSize = share }
        }

    override val keys: Set<MemoryCache.Key>
        get() = caches.values.flatMapTo(mutableSetOf()) { it.keys }

    override fun get(key: MemoryCache.Key): MemoryCache.Value? = cacheFor(key).get(key)

    override fun set(key: MemoryCache.Key, value: MemoryCache.Value) {
        cacheFor(key).set(key, value)
    }

    override fun remove(key: MemoryCache.Key): Boolean = cacheFor(key).remove(key)

    override fun trimToSize(size: Long) {
        val share = size / caches.size.coerceAtLeast(1)
        caches.values.forEach { it.trimToSize(share) }
    }

    override fun clear() {
        caches.values.forEach(MemoryCache::clear)
    }

    fun clear(role: ImageCacheRole) {
        caches.getValue(role).clear()
    }

    fun remove(role: ImageCacheRole, identity: Any): Boolean {
        val key = MemoryCache.Key(role.cacheKey(identity.toString()))
        return caches.getValue(role).remove(key)
    }

    /** Removes every entry whose identity starts with [identityPrefix] (reader image slices). */
    fun removeMatching(role: ImageCacheRole, identityPrefix: String): Int {
        val cache = caches.getValue(role)
        val prefix = role.cacheKey(identityPrefix)
        val stale = cache.keys.filter { it.key.startsWith(prefix) }
        stale.forEach { cache.remove(it) }
        return stale.size
    }

    private fun cacheFor(key: MemoryCache.Key): MemoryCache = caches.getValue(
        ImageCacheRole.fromCacheKey(key.key) ?: ImageCacheRole.GRID,
    )
}

fun ImageRequest.Builder.imageCacheRole(role: ImageCacheRole, identity: Any?): ImageRequest.Builder =
    memoryCacheKey(role.cacheKey(identity.toString()))
