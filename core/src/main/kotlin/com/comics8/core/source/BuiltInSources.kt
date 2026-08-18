package com.comics8.core.source

import com.comics8.core.source.eleven.ElevenToonSource
import com.comics8.core.source.hitomi.HitomiSource

/**
 * Only place in the shared tree that names concrete sites.
 * Add or remove a ComicSource here; [SourceRegistry] and the apps stay unchanged.
 */
object BuiltInSources {
    fun all(): List<ComicSource> = listOf(
        ElevenToonSource(),
        HitomiSource(),
    )
}
