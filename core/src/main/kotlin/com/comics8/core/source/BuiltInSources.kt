package com.comics8.core.source

import com.comics8.core.source.eleven.ElevenToonSource
import com.comics8.core.source.hitomi.HitomiSource

/**
 * Test-only site parsers. Production apps start with an empty [SourceRegistry].
 * Tests may construct these via [SourceRegistry.forTests].
 */
object BuiltInSources {
    fun all(): List<ComicSource> = listOf(
        ElevenToonSource(),
        HitomiSource(),
    )
}
