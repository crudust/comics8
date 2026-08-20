package com.comics8.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ChromeReaderMode
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ViewMode
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.Screen

@Composable
fun TopBar(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
    inOverlay: Boolean = false,
) {
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
            } else if (state.screen == Screen.Series) {
                IconButton(onClick = viewModel::closeSeries) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
            } else if (state.screen == Screen.History) {
                IconButton(onClick = viewModel::closeHistory) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
            } else if (state.screen == Screen.Downloads) {
                IconButton(onClick = viewModel::closeDownloads) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
            }
            if (state.screen == Screen.Browse) {
                SourceTitleDropdown(
                    activeSourceId = state.activeSourceId,
                    onSelect = viewModel::setActiveSource,
                    titleStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    text = when (state.screen) {
                        Screen.Reader -> state.currentEpisode?.title.orEmpty().ifBlank { "만화 보기" }
                        Screen.Series -> state.series?.title.orEmpty()
                        Screen.Browse -> SourceRegistry.chipLabel(state.activeSourceId)
                        Screen.History -> "히스토리"
                        Screen.Downloads -> "${SourceRegistry.chipLabel(state.activeSourceId)} 저장소"
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
                        contentDescription = "업데이트 알림",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (state.screen == Screen.Browse) {
                IconButton(onClick = viewModel::openSyncDialog) {
                    Icon(
                        imageVector = Icons.Outlined.CloudSync,
                        contentDescription = "클라우드 동기화",
                    )
                }
                IconButton(onClick = viewModel::openDownloads) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "다운로드 보관함",
                    )
                }
                IconButton(onClick = viewModel::openHistory) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = "히스토리",
                    )
                }
                IconButton(onClick = viewModel::toggleSearchBar) {
                    Icon(
                        imageVector = if (state.searchExpanded) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (state.searchExpanded) "검색 닫기" else "검색",
                    )
                }
            }
            if (state.screen == Screen.History) {
                IconButton(onClick = viewModel::openSyncDialog) {
                    Icon(
                        imageVector = Icons.Outlined.CloudSync,
                        contentDescription = "클라우드 동기화",
                    )
                }
                if (state.historyItems.isNotEmpty()) {
                    IconButton(onClick = viewModel::clearAllHistory) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "기록 전체 삭제",
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
                            text = "이어보기 (${state.progressLabel(hist)})",
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
                            text = "처음부터",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                IconButton(onClick = viewModel::openDownloadOptions) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "오프라인 다운로드",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = viewModel::toggleFavorite) {
                    Icon(
                        imageVector = if (state.seriesFavorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (state.seriesFavorited) "즐겨찾기 해제" else "즐겨찾기",
                        tint = if (state.seriesFavorited) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        },
                    )
                }
            }
            if (state.screen == Screen.Reader) {
                // View Mode Switcher [ Scroll | Page | Dual ]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(2.dp),
                ) {
                    ViewModeChip(
                        selected = state.viewMode == ViewMode.SCROLL,
                        onClick = { viewModel.setViewMode(ViewMode.SCROLL) },
                        icon = Icons.Default.SwapVert,
                        label = "스크롤",
                    )
                    ViewModeChip(
                        selected = state.viewMode == ViewMode.SINGLE,
                        onClick = { viewModel.setViewMode(ViewMode.SINGLE) },
                        icon = Icons.AutoMirrored.Filled.ChromeReaderMode,
                        label = "단면",
                    )
                    ViewModeChip(
                        selected = state.viewMode == ViewMode.DUAL,
                        onClick = { viewModel.setViewMode(ViewMode.DUAL) },
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        label = "양면",
                    )
                }

                // Read Direction Switcher
                if (state.viewMode != ViewMode.SCROLL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(2.dp),
                    ) {
                        DirectionChip(
                            selected = state.readDirection == ReadDirection.RIGHT_TO_LEFT,
                            onClick = { viewModel.setReadDirection(ReadDirection.RIGHT_TO_LEFT) },
                            label = "우→좌",
                        )
                        DirectionChip(
                            selected = state.readDirection == ReadDirection.LEFT_TO_RIGHT,
                            onClick = { viewModel.setReadDirection(ReadDirection.LEFT_TO_RIGHT) },
                            label = "좌→우",
                        )
                    }
                }

                IconButton(onClick = viewModel::toggleFullscreen) {
                    Icon(
                        imageVector = if (state.isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = "전체화면",
                    )
                }
            }
            IconButton(onClick = viewModel::refresh) {
                Icon(Icons.Default.Refresh, contentDescription = "새로고침")
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
                            Text(SourceRegistry.searchPlaceholder(state.activeSourceId))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.search(state.searchInput) }),
                        trailingIcon = {
                            if (state.searchInput.isNotEmpty()) {
                                IconButton(onClick = {
                                    viewModel.updateSearchInput("")
                                    if (state.isSearch) viewModel.selectTab(state.tab)
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "검색어 지우기")
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
                    Icon(Icons.Default.Search, contentDescription = "검색 실행")
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
                    FilterChip(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        label = { Text(tab.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
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
private fun SourceTitleDropdown(
    activeSourceId: String,
    onSelect: (String) -> Unit,
    titleStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val sources = remember { SourceRegistry.defaults().all() }
    val label = sources.firstOrNull { it.id == activeSourceId }?.displayName
        ?: SourceRegistry.chipLabel(activeSourceId)
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(end = 4.dp),
        ) {
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
                contentDescription = "소스 선택",
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sources.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.displayName) },
                    onClick = {
                        onSelect(source.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
