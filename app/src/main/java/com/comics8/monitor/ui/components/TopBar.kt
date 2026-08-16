package com.comics8.monitor.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ChromeReaderMode
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.StarBorder
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comics8.monitor.data.Catalog
import com.comics8.monitor.data.ReadDirection
import com.comics8.monitor.data.ViewMode
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
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
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
            }
            Text(
                text = when (state.screen) {
                    Screen.Reader -> state.currentEpisode?.title.orEmpty().ifBlank { "만화 보기" }
                    Screen.Series -> state.series?.title.orEmpty()
                    Screen.Browse -> "Comics8"
                    Screen.History -> "히스토리"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.screen == Screen.Browse) {
                IconButton(onClick = viewModel::openSyncDialog) {
                    Icon(
                        imageVector = Icons.Outlined.CloudSync,
                        contentDescription = "클라우드 동기화",
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
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "이어보기 (${hist.lastReadOrder}/${hist.totalEpisodes})",
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
                        .padding(end = 4.dp)
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
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
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
                            imageVector = Icons.AutoMirrored.Filled.ChromeReaderMode,
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
                            .padding(end = 4.dp)
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
            }
            IconButton(onClick = { viewModel.refresh() }) {
                Icon(Icons.Default.Refresh, contentDescription = "새로고침")
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                Catalog.entries.forEach { catalog ->
                    FilterChip(
                        selected = !state.isSearch && state.catalog == catalog,
                        onClick = { viewModel.selectCatalog(catalog) },
                        label = { Text(catalog.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }

        val showPager = when (state.screen) {
            Screen.Series -> state.episodeLastPage > 1
            Screen.Browse -> !state.isSearch && state.catalog.paginated && state.lastPage > 1
            Screen.Reader -> false
            Screen.History -> false
        }
        if (showPager) {
            val page = if (state.screen == Screen.Series) state.episodePage else state.page
            val last = if (state.screen == Screen.Series) state.episodeLastPage else state.lastPage
            PageBar(
                page = page,
                lastPage = last,
                onPrev = {
                    if (state.screen == Screen.Series) {
                        viewModel.goToEpisodePage(page - 1)
                    } else {
                        viewModel.prevPage()
                    }
                },
                onNext = {
                    if (state.screen == Screen.Series) {
                        viewModel.goToEpisodePage(page + 1)
                    } else {
                        viewModel.nextPage()
                    }
                },
                onJump = { viewModel.togglePageJump(true) },
            )
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
    OutlinedTextField(
        value = state.searchInput,
        onValueChange = viewModel::updateSearchInput,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .focusRequester(requester),
        singleLine = true,
        placeholder = { Text("제목 검색") },
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
}
