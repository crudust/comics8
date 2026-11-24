package com.comics8.shared.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comics8.core.app.AppScreen
import com.comics8.core.app.AppUiState
import com.comics8.core.app.SharedAppController
import com.comics8.core.i18n.displaySearchPlaceholder
import com.comics8.core.i18n.displaySourceTitle
import com.comics8.core.i18n.displayTitle
import com.comics8.core.i18n.displayLabel
import com.comics8.core.i18n.getSourceOrNull
import com.comics8.core.model.EpisodeSortOrder
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ViewMode
import com.comics8.core.source.ComicSource
import com.comics8.core.source.SourceType
import com.comics8.core.source.resolveSourceType
import com.comics8.shared.ui.theme.LocalStrings
import com.comics8.shared.ui.theme.MonochromeTheme

@Composable
fun TopBar(
    state: AppUiState,
    controller: SharedAppController,
    modifier: Modifier = Modifier,
    inOverlay: Boolean = false,
    compactMode: Boolean = false,
) {
    val strings = LocalStrings.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (inOverlay) {
                    val overlayBg = if (compactMode) {
                        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f)
                    } else {
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    }
                    Modifier
                        .background(overlayBg)
                        .then(if (compactMode) Modifier.statusBarsPadding() else Modifier)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = if (compactMode) 12.dp else 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val backDesc = if (compactMode) strings.actionGoBack else strings.actionBack
            if (state.screen == AppScreen.Reader) {
                IconButton(onClick = controller::closeReader) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDesc)
                }
            } else if (state.screen == AppScreen.Series) {
                IconButton(onClick = controller::closeSeries) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDesc)
                }
            } else if (state.screen == AppScreen.History) {
                IconButton(onClick = controller::closeHistory) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDesc)
                }
            } else if (state.screen == AppScreen.Downloads) {
                IconButton(onClick = controller::closeDownloads) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDesc)
                }
            }

            if (state.screen == AppScreen.Browse) {
                SourceTitleDropdown(
                    activeSourceId = state.activeSourceId,
                    sources = state.installedSources,
                    onSelect = controller::setActiveSource,
                    onOpenSourceManager = controller::openSourceManager,
                    onOpenSettings = { controller.openSettings() },
                    titleStyle = if (compactMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    text = when (state.screen) {
                        AppScreen.Reader -> state.currentEpisode?.title.orEmpty().ifBlank { strings.navReader }
                        AppScreen.Series -> state.series?.title.orEmpty()
                        AppScreen.Browse -> state.sourceRegistry.displaySourceTitle(state.activeSourceId, strings)
                        AppScreen.History -> strings.navHistory
                        AppScreen.Downloads -> {
                            val label = state.sourceRegistry.displaySourceTitle(state.activeSourceId, strings)
                            strings.navStorageNamed(label)
                        }
                        AppScreen.Settings -> strings.navSettings
                        AppScreen.SourceManager -> strings.navSourceManager
                    },
                    style = if (compactMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            if (state.updateState.hasUpdate) {
                IconButton(onClick = controller::openUpdateDialog) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = strings.labelUpdateNotification,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (state.screen == AppScreen.Browse) {
                if (state.writesDownloads) {
                    IconButton(onClick = controller::openDownloads) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = strings.navDownloads,
                        )
                    }
                }
                IconButton(onClick = controller::openHistory) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = strings.navHistory,
                    )
                }
                IconButton(
                    onClick = controller::toggleSearchBar,
                    enabled = state.activeSourceId != null,
                ) {
                    Icon(
                        imageVector = if (state.searchExpanded) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (state.searchExpanded) strings.actionSearchClose else strings.actionSearch,
                    )
                }
            }

            if (state.screen == AppScreen.History) {
                if (state.historyItems.isNotEmpty()) {
                    IconButton(onClick = controller::clearAllHistory) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = strings.actionClearAllHistory,
                        )
                    }
                }
            }

            if (state.screen == AppScreen.Series) {
                val hist = state.seriesHistory
                val buttonPadding = PaddingValues(horizontal = if (compactMode) 8.dp else 10.dp, vertical = 4.dp)
                val iconGap = if (compactMode) 2.dp else 4.dp
                if (hist != null) {
                    TextButton(
                        onClick = controller::resumeSeries,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = buttonPadding,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(iconGap))
                        Text(
                            text = strings.actionResumeSeriesWithProgress(state.progressLabel(hist)),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    TextButton(
                        onClick = controller::openFirstEpisode,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = buttonPadding,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(iconGap))
                        Text(
                            text = strings.actionStartFromBeginning,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (state.writesDownloads) {
                    IconButton(onClick = controller::openDownloadOptions) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = strings.titleOfflineDownload,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                var showSortMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = strings.actionSortEpisodes,
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        val sortEntries = listOf(
                            EpisodeSortOrder.DEFAULT to strings.sortDefault,
                            EpisodeSortOrder.NAME_ASC to strings.sortNameAsc,
                            EpisodeSortOrder.NAME_DESC to strings.sortNameDesc,
                            EpisodeSortOrder.DATE_DESC to strings.sortDateDesc,
                            EpisodeSortOrder.DATE_ASC to strings.sortDateAsc,
                        )
                        sortEntries.forEach { (order, orderLabel) ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(orderLabel)
                                        if (state.episodeSortOrder == order) {
                                            Spacer(Modifier.width(8.dp))
                                            Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                                        }
                                    }
                                },
                                onClick = {
                                    controller.setEpisodeSortOrder(order)
                                    showSortMenu = false
                                },
                            )
                        }
                    }
                }
                IconButton(onClick = { controller.toggleFavorite() }) {
                    Icon(
                        imageVector = if (state.seriesFavorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (state.seriesFavorited) strings.actionRemoveFavorite else strings.actionAddFavorite,
                        tint = if (state.seriesFavorited) {
                            MonochromeTheme.Gold
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        },
                    )
                }
            }

            if (state.screen == AppScreen.Reader && state.series != null) {
                IconButton(onClick = { controller.toggleFavorite() }) {
                    Icon(
                        imageVector = if (state.seriesFavorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (state.seriesFavorited) strings.actionRemoveFavorite else strings.actionAddFavorite,
                        tint = if (state.seriesFavorited) {
                            MonochromeTheme.Gold
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        },
                    )
                }
            }

            IconButton(
                onClick = { controller.refresh() },
                enabled = state.activeSourceId != null,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = strings.actionRefresh)
            }
        }

        if (state.screen == AppScreen.Reader) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compactMode) 6.dp else 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (compactMode) 4.dp else 6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(2.dp),
                ) {
                    val viewModeEntries = listOf(
                        Triple(ViewMode.AUTO, Icons.Default.AutoAwesome, strings.viewModeAuto to strings.viewModeAutoShort),
                        Triple(ViewMode.SCROLL, Icons.Default.SwapVert, strings.viewModeScroll to strings.viewModeScrollShort),
                        Triple(ViewMode.PAGE, Icons.Default.Description, strings.viewModeSingle to strings.viewModeSingleShort),
                        Triple(ViewMode.DUAL, Icons.AutoMirrored.Filled.MenuBook, strings.viewModeDual to strings.viewModeDualShort),
                    )
                    viewModeEntries.forEach { (mode, icon, labels) ->
                        val selected = state.viewMode == mode
                        if (compactMode) {
                            IconButton(
                                onClick = { controller.setViewMode(mode) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = labels.first,
                                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            ViewModeChip(
                                selected = selected,
                                onClick = { controller.setViewMode(mode) },
                                icon = icon,
                                label = labels.second,
                            )
                        }
                    }
                }

                if (state.effectiveViewMode != ViewMode.SCROLL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(2.dp),
                    ) {
                        val rtlLabel = if (compactMode) "RL" else strings.readDirectionRTLShort
                        val ltrLabel = if (compactMode) "LR" else strings.readDirectionLTRShort
                        ReaderOptionChip(
                            selected = state.readDirection == ReadDirection.RIGHT_TO_LEFT,
                            onClick = { controller.setReadDirection(ReadDirection.RIGHT_TO_LEFT) },
                            label = rtlLabel,
                            selectedContainer = if (compactMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            selectedContent = if (compactMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary,
                            compact = compactMode,
                        )
                        ReaderOptionChip(
                            selected = state.readDirection == ReadDirection.LEFT_TO_RIGHT,
                            onClick = { controller.setReadDirection(ReadDirection.LEFT_TO_RIGHT) },
                            label = ltrLabel,
                            selectedContainer = if (compactMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                            selectedContent = if (compactMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondary,
                            compact = compactMode,
                        )
                    }
                }

                if (state.effectiveViewMode == ViewMode.PAGE) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(2.dp),
                    ) {
                        ReaderOptionChip(
                            selected = state.splitMode == SplitMode.FIT,
                            onClick = { controller.setSplitMode(SplitMode.FIT) },
                            label = "Fit",
                            selectedContainer = if (compactMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            selectedContent = if (compactMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary,
                            compact = compactMode,
                        )
                        ReaderOptionChip(
                            selected = state.splitMode == SplitMode.SLICE,
                            onClick = { controller.setSplitMode(SplitMode.SLICE) },
                            label = "Slice",
                            selectedContainer = if (compactMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            selectedContent = if (compactMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiary,
                            compact = compactMode,
                        )
                    }
                }

                if (compactMode) {
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = controller::openViewerOptions,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = strings.sectionViewerSettings,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        if (state.screen == AppScreen.Browse) {
            AnimatedVisibility(visible = state.searchExpanded) {
                SearchBar(state = state, controller = controller)
            }
            if (state.isSearch) {
                Text(
                    text = strings.searchResultCount(state.items.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            val chipScrollState = rememberScrollState()
            val catalogsList = state.browseTabs
            LaunchedEffect(state.tab, catalogsList) {
                val idx = catalogsList.indexOf(state.tab)
                if (idx >= 0) {
                    chipScrollState.animateScrollTo((idx * 75).coerceAtLeast(0))
                }
            }
            if (compactMode || !state.isSearch) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (!compactMode) Modifier.padding(top = 8.dp) else Modifier)
                        .horizontalScroll(chipScrollState),
                ) {
                    catalogsList.forEach { tab ->
                        val isSelected = !state.isSearch && state.tab == tab
                        FilterChip(
                            selected = isSelected,
                            onClick = { controller.selectTab(tab) },
                            label = { Text(tab.displayLabel(strings), fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                            shape = RoundedCornerShape(10.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            }
        }

        val currentError = state.error
        if (currentError != null && state.items.isNotEmpty() && state.screen == AppScreen.Browse) {
            Text(
                text = currentError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun SearchBar(
    state: AppUiState,
    controller: SharedAppController,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(state.searchExpanded) {
        if (state.searchExpanded) {
            runCatching { requester.requestFocus() }
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.searchInput,
            onValueChange = controller::updateSearchInput,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .focusRequester(requester),
            singleLine = true,
            placeholder = {
                Text(state.sourceRegistry.getSourceOrNull(state.activeSourceId).displaySearchPlaceholder(strings))
            },
            trailingIcon = {
                if (state.searchInput.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            controller.updateSearchInput("")
                            if (state.isSearch) state.tab?.let(controller::selectTab)
                        },
                    ) {
                        Icon(Icons.Default.Close, contentDescription = strings.actionSearchClear)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focus.clearFocus()
                    controller.submitSearch()
                },
            ),
        )
        state.searchSuggestions.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { controller.applySearchSuggestion(item) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = item.tag,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${item.ns} · ${item.count}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ViewModeChip(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReaderOptionChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    selectedContainer: Color,
    selectedContent: Color,
    compact: Boolean,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) selectedContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = if (compact) 6.dp else 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected || compact) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) selectedContent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceTitleDropdown(
    activeSourceId: String?,
    sources: List<ComicSource>,
    onSelect: (String) -> Unit,
    onOpenSourceManager: () -> Unit,
    onOpenSettings: () -> Unit,
    titleStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    val activeSource = sources.firstOrNull { it.id == activeSourceId }
    val label = activeSource.displayTitle(strings)
    val activeType = activeSource?.resolveSourceType() ?: SourceType.LOCAL
    val activeIcon = when (activeType) {
        SourceType.LOCAL -> Icons.Default.Folder
        SourceType.SMB -> Icons.Default.Dns
        SourceType.WEBDAV -> Icons.Default.Cloud
        SourceType.JS -> Icons.Default.Description
    }

    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Icon(
                imageVector = activeIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = titleStyle,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = strings.promptSelectSource,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        MaterialTheme(
            shapes = MaterialTheme.shapes.copy(
                extraSmall = RoundedCornerShape(12.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(12.dp),
            ),
        ) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                ),
            ) {
                if (sources.isNotEmpty()) {
                    sources.forEach { source ->
                        val isSelected = source.id == activeSourceId
                        val sType = source.resolveSourceType()
                        val itemIcon = when (sType) {
                            SourceType.LOCAL -> Icons.Default.Folder
                            SourceType.SMB -> Icons.Default.Dns
                            SourceType.WEBDAV -> Icons.Default.Cloud
                            SourceType.JS -> Icons.Default.Description
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = source.displayTitle(strings),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = itemIcon,
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            trailingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = strings.labelSelected,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else null,
                            onClick = {
                                onSelect(source.id)
                                expanded = false
                            },
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                DropdownMenuItem(
                    text = { Text(strings.navSourceManager) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        expanded = false
                        onOpenSourceManager()
                    },
                )
                DropdownMenuItem(
                    text = { Text(strings.navSettings) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        expanded = false
                        onOpenSettings()
                    },
                )
            }
        }
    }
}
