package com.comics8.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.StarBorder
import com.comics8.core.i18n.displayLabel
import com.comics8.core.i18n.displaySearchPlaceholder
import com.comics8.core.i18n.displaySourceTitle
import com.comics8.core.i18n.displayTitle
import com.comics8.core.i18n.getSourceOrNull
import com.comics8.core.source.SourceType
import com.comics8.core.source.resolveSourceType
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comics8.core.source.SourceRegistry
import com.comics8.core.model.EpisodeSortOrder
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ViewMode
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.Screen
import com.comics8.desktop.ui.theme.LocalStrings
import com.comics8.desktop.ui.theme.MonochromeTheme

@Composable
fun TopBar(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
    inOverlay: Boolean = false,
) {
    val strings = LocalStrings.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (inOverlay) {
                    Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.screen == Screen.Reader) {
                IconButton(onClick = viewModel::closeReader) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.actionBack)
                }
            } else if (state.screen == Screen.Series) {
                IconButton(onClick = viewModel::closeSeries) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.actionBack)
                }
            } else if (state.screen == Screen.History) {
                IconButton(onClick = viewModel::closeHistory) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.actionBack)
                }
            } else if (state.screen == Screen.Downloads) {
                IconButton(onClick = viewModel::closeDownloads) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.actionBack)
                }
            }
            if (state.screen == Screen.Browse) {
                SourceTitleDropdown(
                    activeSourceId = state.activeSourceId,
                    sources = state.installedSources,
                    registry = state.sourceRegistry,
                    onSelect = viewModel::setActiveSource,
                    onOpenSourceManager = viewModel::openSourceManager,
                    onOpenSettings = viewModel::openSettings,
                    titleStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    text = when (state.screen) {
                        Screen.Reader -> state.currentEpisode?.title.orEmpty().ifBlank { strings.navReader }
                        Screen.Series -> state.series?.title.orEmpty()
                        Screen.Browse -> state.sourceRegistry.displaySourceTitle(state.activeSourceId, strings)
                        Screen.History -> strings.navHistory
                        Screen.Downloads -> {
                            val label = state.sourceRegistry.displaySourceTitle(state.activeSourceId, strings)
                            strings.navStorageNamed(label)
                        }
                        Screen.Settings -> strings.navSettings
                        Screen.SourceManager -> strings.navSourceManager
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.updateState.hasUpdate) {
                IconButton(onClick = viewModel::openUpdateDialog) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = strings.labelUpdateNotification,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (state.screen == Screen.Browse) {
                if (state.writesDownloads) {
                    IconButton(onClick = viewModel::openDownloads) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = strings.navDownloads,
                        )
                    }
                }
                IconButton(onClick = viewModel::openHistory) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = strings.navHistory,
                    )
                }
                IconButton(
                    onClick = viewModel::toggleSearchBar,
                    enabled = state.activeSourceId != null,
                ) {
                    Icon(
                        imageVector = if (state.searchExpanded) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (state.searchExpanded) strings.actionSearchClose else strings.actionSearch,
                    )
                }
            }
            if (state.screen == Screen.History) {
                if (state.historyItems.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearAllHistory) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = strings.actionClearAllHistory,
                        )
                    }
                }
            }
            if (state.screen == Screen.Series) {
                val hist = state.seriesHistory
                if (hist != null) {
                    TextButton(
                        onClick = viewModel::resumeSeries,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = strings.actionResumeSeriesWithProgress(state.progressLabel(hist)),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    TextButton(
                        onClick = viewModel::openFirstEpisode,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = strings.actionStartFromBeginning,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (state.writesDownloads) {
                    IconButton(onClick = viewModel::openDownloadOptions) {
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
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(strings.sortNameAsc)
                                    if (state.episodeSortOrder == EpisodeSortOrder.NAME_ASC) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                                    }
                                }
                            },
                            onClick = {
                                viewModel.setEpisodeSortOrder(EpisodeSortOrder.NAME_ASC)
                                showSortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(strings.sortNameDesc)
                                    if (state.episodeSortOrder == EpisodeSortOrder.NAME_DESC) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                                    }
                                }
                            },
                            onClick = {
                                viewModel.setEpisodeSortOrder(EpisodeSortOrder.NAME_DESC)
                                showSortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(strings.sortDateDesc)
                                    if (state.episodeSortOrder == EpisodeSortOrder.DATE_DESC) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                                    }
                                }
                            },
                            onClick = {
                                viewModel.setEpisodeSortOrder(EpisodeSortOrder.DATE_DESC)
                                showSortMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(strings.sortDateAsc)
                                    if (state.episodeSortOrder == EpisodeSortOrder.DATE_ASC) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp))
                                    }
                                }
                            },
                            onClick = {
                                viewModel.setEpisodeSortOrder(EpisodeSortOrder.DATE_ASC)
                                showSortMenu = false
                            },
                        )
                    }
                }
                IconButton(onClick = viewModel::toggleFavorite) {
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
                onClick = viewModel::refresh,
                enabled = state.activeSourceId != null,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = strings.actionRefresh)
            }
        }

        // Reader Options Bar (Row 2)
        if (state.screen == Screen.Reader) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                // View Mode Switcher [ Scroll | Page | Dual ]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(2.dp),
                ) {
                    ViewModeChip(
                        selected = state.viewMode == ViewMode.SCROLL,
                        onClick = { viewModel.setViewMode(ViewMode.SCROLL) },
                        icon = Icons.Default.SwapVert,
                        label = strings.viewModeScrollShort,
                    )
                    ViewModeChip(
                        selected = state.viewMode == ViewMode.PAGE,
                        onClick = { viewModel.setViewMode(ViewMode.PAGE) },
                        icon = Icons.Default.Description,
                        label = strings.viewModeSingleShort,
                    )
                    ViewModeChip(
                        selected = state.viewMode == ViewMode.DUAL,
                        onClick = { viewModel.setViewMode(ViewMode.DUAL) },
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        label = strings.viewModeDualShort,
                    )
                }

                // Read Direction Switcher [ 우→좌 | 좌→우 ]
                if (state.viewMode != ViewMode.SCROLL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(2.dp),
                    ) {
                        DirectionChip(
                            selected = state.readDirection == ReadDirection.RIGHT_TO_LEFT,
                            onClick = { viewModel.setReadDirection(ReadDirection.RIGHT_TO_LEFT) },
                            label = strings.readDirectionRTLShort,
                        )
                        DirectionChip(
                            selected = state.readDirection == ReadDirection.LEFT_TO_RIGHT,
                            onClick = { viewModel.setReadDirection(ReadDirection.LEFT_TO_RIGHT) },
                            label = strings.readDirectionLTRShort,
                        )
                    }
                }

                // Split Mode Switcher [ Fit | Slice ] (Only in Single Page mode)
                if (state.viewMode == ViewMode.PAGE) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(2.dp),
                    ) {
                        SplitModeChip(
                            selected = state.splitMode == SplitMode.FIT,
                            onClick = { viewModel.setSplitMode(SplitMode.FIT) },
                            label = "Fit",
                        )
                        SplitModeChip(
                            selected = state.splitMode == SplitMode.SLICE,
                            onClick = { viewModel.setSplitMode(SplitMode.SLICE) },
                            label = "Slice",
                        )
                    }
                }
            }
        }

        // Search Bar in Browse
        AnimatedVisibility(visible = state.searchExpanded && state.screen == Screen.Browse) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = state.searchInput,
                        onValueChange = viewModel::updateSearchInput,
                        placeholder = {
                            Text(state.sourceRegistry.getSourceOrNull(state.activeSourceId).displaySearchPlaceholder(strings))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.search(state.searchInput) }),
                        trailingIcon = {
                            if (state.searchInput.isNotEmpty()) {
                                IconButton(onClick = {
                                    viewModel.updateSearchInput("")
                                    if (state.isSearch) state.tab?.let(viewModel::selectTab)
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = strings.actionSearchClear)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.searchSuggestions.forEach { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.applySearchSuggestion(item) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
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
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { viewModel.search(state.searchInput) }) {
                    Icon(Icons.Default.Search, contentDescription = strings.actionSearchExecute)
                }
            }
        }

        // Category Chips in Browse
        if (state.screen == Screen.Browse && !state.isSearch) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                state.browseTabs.forEach { tab ->
                    val isSelected = state.tab == tab
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.selectTab(tab) },
                        label = { Text(tab.displayLabel(strings), fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        shape = RoundedCornerShape(10.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            labelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewModeChip(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            )
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
private fun DirectionChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondary else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SplitModeChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.tertiary else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onTertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceTitleDropdown(
    activeSourceId: String?,
    sources: List<com.comics8.core.source.ComicSource>,
    registry: SourceRegistry,
    onSelect: (String) -> Unit,
    onOpenSourceManager: () -> Unit,
    onOpenSettings: () -> Unit,
    titleStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    var expanded by remember { mutableStateOf(false) }
    val orderedSources = sources
    val activeSource = orderedSources.firstOrNull { it.id == activeSourceId }
    val label = activeSource.displayTitle(strings)
    val activeType = activeSource?.resolveSourceType() ?: com.comics8.core.source.SourceType.LOCAL
    val activeIcon = when (activeType) {
        com.comics8.core.source.SourceType.LOCAL -> Icons.Default.Folder
        com.comics8.core.source.SourceType.SMB -> Icons.Default.Dns
        com.comics8.core.source.SourceType.WEBDAV -> Icons.Default.Cloud
        com.comics8.core.source.SourceType.JS -> Icons.Default.Description
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
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(12.dp),
                    ),
            ) {
                if (orderedSources.isNotEmpty()) {
                    orderedSources.forEach { source ->
                        val isSelected = source.id == activeSourceId
                        val sType = source.resolveSourceType()
                        val itemIcon = when (sType) {
                            com.comics8.core.source.SourceType.LOCAL -> Icons.Default.Folder
                            com.comics8.core.source.SourceType.SMB -> Icons.Default.Dns
                            com.comics8.core.source.SourceType.WEBDAV -> Icons.Default.Cloud
                            com.comics8.core.source.SourceType.JS -> Icons.Default.Description
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
