package com.comics8.core.network

import com.comics8.core.parser.ComicSite
import com.comics8.core.source.hitomi.HitomiUrls

object ImageReferer {
    fun forUrl(url: String): String =
        if (HitomiUrls.isHitomiHost(url)) HitomiUrls.REFERER else ComicSite.ORIGIN
}
