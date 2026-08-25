package com.comics8.monitor.ui.components

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comics8.core.source.SourceRegistry
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ViewMode
import com.comics8.monitor.ui.ListingUiState
import com.comics8.monitor.ui.ListingViewModel
import com.comics8.monitor.ui.Screen

@Composable
fun TopBar(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
    inOverlay: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (inOverlay) {
                    Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f))
                        .statusBarsPadding()
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    sources = state.installedSources,
                    registry = state.sourceRegistry,
                    onSelect = viewModel::setActiveSource,
                    onOpenSourceManager = viewModel::openSourceManager,
                    onOpenSettings = viewModel::openSettings,
                    titleStyle = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    text = when (state.screen) {
                        Screen.Reader -> state.currentEpisode?.title.orEmpty().ifBlank { "만화 보기" }
                        Screen.Series -> state.series?.title.orEmpty()
                        Screen.Browse -> state.sourceRegistry.chipLabel(state.activeSourceId.orEmpty())
                            .ifBlank { EMPTY_SOURCE_MESSAGE }
                        Screen.History -> "히스토리"
                        Screen.Downloads -> {
                            val label = state.sourceRegistry.chipLabel(state.activeSourceId.orEmpty())
                            if (label.isBlank()) "저장소" else "$label 저장소"
                        }
                        Screen.Settings -> "설정"
                        Screen.SourceManager -> "소스 관리"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (state.updateState.hasUpdate) {
                IconButton(onClick = viewModel::openUpdateDialog) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = "업데이트 알림",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (state.screen == Screen.Browse) {
                if (state.writesDownloads) {
                    IconButton(onClick = viewModel::openDownloads) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "다운로드 보관함",
                        )
                    }
                }
                IconButton(onClick = viewModel::openHistory) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = "히스토리",
                    )
                }
                IconButton(
                    onClick = viewModel::toggleSearchBar,
                    enabled = state.activeSourceId != null,
                ) {
                    Icon(
                        imageVector = if (state.searchExpanded) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (state.searchExpanded) "검색 닫기" else "검색",
                    )
                }
            }
            if (state.screen == Screen.History) {
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
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(2.dp))
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
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "처음부터",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                if (state.writesDownloads) {
                    IconButton(onClick = viewModel::openDownloadOptions) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "오프라인 다운로드",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
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
            IconButton(
                onClick = { viewModel.refresh() },
                enabled = state.activeSourceId != null,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "새로고침")
            }
        }

        // Reader Options Bar (Row 2)
        if (state.screen == Screen.Reader) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
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
                    // 1. Scroll mode
                    IconButton(
                        onClick = { viewModel.setViewMode(ViewMode.SCROLL) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = "세로 스크롤",
                            tint = if (state.viewMode == ViewMode.SCROLL) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    // 2. Single Page mode
                    IconButton(
                        onClick = { viewModel.setViewMode(ViewMode.PAGE) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = "단면 페이지",
                            tint = if (state.viewMode == ViewMode.PAGE) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    // 3. Dual Page mode
                    IconButton(
                        onClick = { viewModel.setViewMode(ViewMode.DUAL) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = "양쪽보기",
                            tint = if (state.viewMode == ViewMode.DUAL) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                // Direction Switcher [ RL | LR ] - Shown for paging modes
                if (state.viewMode != ViewMode.SCROLL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(2.dp),
                    ) {
                        // RL (Right-to-Left)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (state.readDirection == ReadDirection.RIGHT_TO_LEFT) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable { viewModel.setReadDirection(ReadDirection.RIGHT_TO_LEFT) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "RL",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (state.readDirection == ReadDirection.RIGHT_TO_LEFT) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        // LR (Left-to-Right)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (state.readDirection == ReadDirection.LEFT_TO_RIGHT) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable { viewModel.setReadDirection(ReadDirection.LEFT_TO_RIGHT) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "LR",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (state.readDirection == ReadDirection.LEFT_TO_RIGHT) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }

                // Split Mode Switcher [ Fit | Slice ] - Shown for Single Page mode
                if (state.viewMode == ViewMode.PAGE) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .padding(2.dp),
                    ) {
                        // Fit
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (state.splitMode == SplitMode.FIT) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable { viewModel.setSplitMode(SplitMode.FIT) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Fit",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (state.splitMode == SplitMode.FIT) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        // Slice
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (state.splitMode == SplitMode.SLICE) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .clickable { viewModel.setSplitMode(SplitMode.SLICE) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Slice",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (state.splitMode == SplitMode.SLICE) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }

        if (state.screen == Screen.Browse) {
            AnimatedVisibility(visible = state.searchExpanded) {
                SearchBar(state = state, viewModel = viewModel)
            }
            if (state.isSearch) {
                Text(
                    text = "검색 · ${state.items.size}건",
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(chipScrollState),
            ) {
                catalogsList.forEach { tab ->
                    FilterChip(
                        selected = !state.isSearch && state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        label = { Text(tab.label, fontWeight = if (!state.isSearch && state.tab == tab) FontWeight.Bold else FontWeight.Normal) },
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

        state.error?.takeIf { state.items.isNotEmpty() }?.let { msg ->
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun SearchBar(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(state.searchExpanded) {
        if (state.searchExpanded) requester.requestFocus()
    }
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.searchInput,
            onValueChange = viewModel::updateSearchInput,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .focusRequester(requester),
            singleLine = true,
            placeholder = {
                Text(state.sourceRegistry.searchPlaceholder(state.activeSourceId.orEmpty()))
            },
            trailingIcon = {
                if (state.searchInput.isNotEmpty()) {
                    IconButton(onClick = { viewModel.updateSearchInput("") }) {
                        Icon(Icons.Default.Close, contentDescription = "검색어 지우기")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focus.clearFocus()
                    viewModel.submitSearch()
                },
            ),
        )
        state.searchSuggestions.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.applySearchSuggestion(item) }
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
    var expanded by remember { mutableStateOf(false) }
    val orderedSources = sources.sortedBy { it.id != com.comics8.core.source.WorkId.LOCAL_SOURCE }
    val label = orderedSources.firstOrNull { it.id == activeSourceId }?.displayName
        ?: registry.chipLabel(activeSourceId.orEmpty()).ifBlank { "소스 선택" }
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 4.dp, vertical = 2.dp),
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
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = source.displayName,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            trailingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "선택됨",
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
                    text = { Text("소스 관리") },
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
                    text = { Text("설정") },
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
