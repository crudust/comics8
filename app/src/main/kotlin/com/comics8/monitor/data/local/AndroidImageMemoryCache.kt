package com.comics8.monitor.data.local

import android.content.Context
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.image.ImageMemoryBudgets

class AndroidImageMemoryCache(
    context: Context,
    budgets: ImageMemoryBudgets,
) : MemoryCache {
    private val caches = ImageCacheRole.entries.associateWith { role ->
        MemoryCache.Builder(context)
            .maxSizeBytes(budgets[role].coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .build()
    }

    override val size: Int get() = caches.values.sumOf { it.size }
    override val maxSize: Int get() = caches.values.sumOf { it.maxSize }
    override val keys: Set<MemoryCache.Key> get() = caches.values.flatMapTo(mutableSetOf()) { it.keys }

    override fun get(key: MemoryCache.Key): MemoryCache.Value? = cacheFor(key)[key]

    override fun set(key: MemoryCache.Key, value: MemoryCache.Value) {
        cacheFor(key)[key] = value
    }

    override fun remove(key: MemoryCache.Key): Boolean = cacheFor(key).remove(key)

    override fun clear() {
        caches.values.forEach(MemoryCache::clear)
    }

    override fun trimMemory(level: Int) {
        caches.values.forEach { it.trimMemory(level) }
    }

    fun clear(role: ImageCacheRole) {
        caches.getValue(role).clear()
    }

    private fun cacheFor(key: MemoryCache.Key): MemoryCache = caches.getValue(
        ImageCacheRole.fromCacheKey(key.key) ?: ImageCacheRole.GRID,
    )
}

fun ImageRequest.Builder.imageCacheRole(role: ImageCacheRole, identity: Any?): ImageRequest.Builder =
    memoryCacheKey(role.cacheKey(identity.toString()))
