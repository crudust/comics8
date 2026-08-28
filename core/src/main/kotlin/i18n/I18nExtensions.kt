package com.comics8.core.i18n

import com.comics8.core.model.BrowseTab
import com.comics8.core.model.PageTapZone
import com.comics8.core.model.ProgressDisplayMode
import com.comics8.core.source.ComicSource
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.SourceType
import com.comics8.core.source.WorkId

fun BrowseTab.displayLabel(strings: AppStrings): String = when (this) {
    is BrowseTab.Favorite -> strings.tabFavorite
    is BrowseTab.Remote -> {
        if (sourceId == WorkId.LOCAL_SOURCE) {
            when (catalog.id.uppercase()) {
                "LIBRARY" -> strings.catalogLibrary
                "LATEST" -> strings.catalogLatest
                else -> catalog.label
            }
        } else {
            catalog.label
        }
    }
}

fun ComicSource?.displayTitle(strings: AppStrings): String {
    if (this == null || id == WorkId.LOCAL_SOURCE) return strings.navStorage
    return displayName
}

fun SourceRegistry.getSourceOrNull(sourceId: String?): ComicSource? {
    if (sourceId.isNullOrBlank()) return null
    return getOrNull(sourceId)
}

fun SourceRegistry.displaySourceTitle(sourceId: String?, strings: AppStrings): String {
    if (sourceId.isNullOrBlank() || sourceId == WorkId.LOCAL_SOURCE) return strings.navStorage
    return getOrNull(sourceId)?.displayName ?: strings.navStorage
}

fun ComicSource?.displaySearchPlaceholder(strings: AppStrings): String {
    if (this == null || id == WorkId.LOCAL_SOURCE) return strings.placeholderSearchFileName
    if (searchPlaceholder.isBlank()) return strings.placeholderSearchTitle
    return searchPlaceholder
}

fun ProgressDisplayMode.displayLabel(strings: AppStrings): String = when (this) {
    ProgressDisplayMode.LATEST_EPISODE -> strings.progressModeLatestEpisode
    ProgressDisplayMode.READ_COUNT -> strings.progressModeReadCount
    ProgressDisplayMode.PERCENTAGE -> strings.progressModePercentage
    ProgressDisplayMode.HIDDEN -> strings.progressModeHidden
}

fun ProgressDisplayMode.displayDescription(strings: AppStrings): String = when (this) {
    ProgressDisplayMode.LATEST_EPISODE -> strings.progressModeLatestEpisodeDesc
    ProgressDisplayMode.READ_COUNT -> strings.progressModeReadCountDesc
    ProgressDisplayMode.PERCENTAGE -> strings.progressModePercentageDesc
    ProgressDisplayMode.HIDDEN -> strings.progressModeHiddenDesc
}

fun SourceType.displayLabel(strings: AppStrings): String = when (this) {
    SourceType.LOCAL -> strings.labelSourceLocal
    SourceType.SMB -> strings.labelSourceSmb
    SourceType.WEBDAV -> strings.labelSourceWebDav
    SourceType.JS -> strings.labelSourceJs
}

fun PageTapZone.displayLabel(strings: AppStrings): String = when (this) {
    PageTapZone.FOLLOW_DIRECTION -> strings.pageTapZoneDirection
    PageTapZone.RIGHT_NEXT -> strings.pageTapZoneRightNext
    PageTapZone.LEFT_NEXT -> strings.pageTapZoneLeftNext
}

fun PageTapZone.displayDescription(strings: AppStrings): String = when (this) {
    PageTapZone.FOLLOW_DIRECTION -> strings.descPageTapZoneDirection
    PageTapZone.RIGHT_NEXT -> strings.descPageTapZoneRightNext
    PageTapZone.LEFT_NEXT -> strings.descPageTapZoneLeftNext
}
