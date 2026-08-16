package com.comics8.monitor.data

import com.comics8.core.model.DualSpread
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ToonItem
import com.comics8.core.model.ViewMode
import com.comics8.core.model.buildDualSpreads

typealias ToonItem = ToonItem
typealias ListingPage = ListingPage
typealias EpisodeItem = EpisodeItem
typealias EpisodePage = EpisodePage
typealias ViewMode = ViewMode
typealias ReadDirection = ReadDirection
typealias DualSpread = DualSpread

fun buildDualSpreads(
    imageCount: Int,
    aspectRatios: Map<Int, Float>,
): List<DualSpread> = com.comics8.core.model.buildDualSpreads(imageCount, aspectRatios)
