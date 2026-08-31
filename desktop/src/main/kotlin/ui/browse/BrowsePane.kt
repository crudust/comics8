package com.comics8.desktop.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.model.BrowseTab
import com.comics8.core.model.ToonItem
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.components.Badge
import com.comics8.desktop.ui.components.EmptySourcePane
import com.comics8.desktop.ui.components.ErrorPane
import com.comics8.desktop.ui.components.LoadingPane
import com.comics8.desktop.ui.components.sourceChipLabel
import com.comics8.desktop.ui.theme.LocalStrings
import com.comics8.desktop.ui.theme.MonochromeTheme
import com.comics8.desktop.ui.util.DesktopAsyncImage

@Composable
fun BrowsePane(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val isLocal = state.activeSourceId == WorkId.LOCAL_SOURCE
    when {
        !state.packsReady -> {
            LoadingPane(modifier = modifier)
        }
        state.activeSourceId == null -> {
            EmptySourcePane(
                modifier = modifier,
                onAddSource = viewModel::openAddSourceSheet,
                onImportJs = {
                    com.comics8.desktop.ui.components.pickJsFile()?.let(viewModel::importJsFile)
                },
            )
        }
        isLocal && state.libraryRoots.isEmpty() && !state.loading -> {
            EmptyLibraryPane(
                onAddFolder = viewModel::addLibraryRoot,
                modifier = modifier,
            )
        }
        state.loading && state.items.isEmpty() -> {
            Column(modifier = modifier.fillMaxSize()) {
                LoadingPane(Modifier.weight(1f).fillMaxWidth())
                BrowsePageBar(state, viewModel)
            }
        }
        state.error != null && state.items.isEmpty() -> {
            ErrorPane(
                message = state.error,
                onRetry = viewModel::refresh,
                modifier = modifier,
            )
        }
        else -> {
            Column(modifier = modifier.fillMaxSize()) {
                if (state.tab is BrowseTab.Favorite && state.items.isEmpty() && !state.isSearch) {
                    ErrorPane(
                        message = strings.emptyFavorites,
                        actionLabel = strings.navBackToLibrary,
                        onRetry = {
                            val dest = state.browseTabs.firstOrNull { it is BrowseTab.Remote } ?: state.tab
                            dest?.let(viewModel::selectTab)
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                } else if (isLocal && state.items.isEmpty() && !state.isSearch) {
                    EmptyLibraryScanHint(modifier = Modifier.weight(1f).fillMaxWidth())
                } else {
                    val gridState = rememberLazyGridState()
                    LaunchedEffect(state.scrollToTopTrigger) {
                        if (state.scrollToTopTrigger > 0) {
                            gridState.scrollToItem(0)
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        state = gridState,
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    ) {
                        items(state.items.distinctBy { it.listingKey() }, key = { it.listingKey() }) { item ->
                            ToonCard(
                                item = item,
                                onClick = { viewModel.onListingOpen(item) },
                                onToggleFavorite = { viewModel.toggleFavorite(item) },
                                showSourceChip = false,
                                sourceRegistry = state.sourceRegistry,
                                refreshEpoch = state.refreshEpoch,
                            )
                        }
                    }
                }

                BrowsePageBar(state, viewModel)
            }
        }
    }
}

@Composable
private fun EmptyLibraryPane(
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text(
            text = strings.promptAddMangaFolder,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onAddFolder) {
            Text(strings.actionAddFolder)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = strings.hintLibraryScan,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyLibraryScanHint(modifier: Modifier = Modifier) {
    val strings = LocalStrings.current
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(24.dp),
    ) {
        Text(
            text = strings.hintLibraryScan,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BrowsePageBar(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
) {
    val strings = LocalStrings.current
    if (state.tab?.paginated != true || state.isSearch || state.lastPage <= 1) return
    Row(
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(vertical = 8.dp),
    ) {
        IconButton(
            onClick = { viewModel.goToPage(state.page - 1) },
            enabled = !state.loading && state.page > 1,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = strings.actionPrev)
        }

        FilledTonalButton(
            onClick = { viewModel.togglePageJump(true) },
            enabled = !state.loading,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                text = "${state.page} / ${state.lastPage}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }

        IconButton(
            onClick = { viewModel.goToPage(state.page + 1) },
            enabled = !state.loading && state.page < state.lastPage,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = strings.actionNext)
        }
    }
}

@Composable
fun ToonCard(
    item: ToonItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    showSourceChip: Boolean = false,
    sourceRegistry: SourceRegistry,
    refreshEpoch: Long = 0L,
) {
    val strings = LocalStrings.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                ),
        ) {
            DesktopAsyncImage(
                cacheRole = ImageCacheRole.GRID,
                url = item.thumbUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                refreshEpoch = refreshEpoch,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.isNew || showSourceChip) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                ) {
                    if (showSourceChip) {
                        Badge(sourceChipLabel(item.sourceId, sourceRegistry, strings))
                    }
                    if (item.isNew) {
                        Badge("NEW")
                    }
                }
            }
            val progress = item.readProgress
            if (!progress.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f))
                        .border(
                            width = 0.5.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = progress,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (item.isFavorite) strings.actionRemoveFavorite else strings.actionAddFavorite,
                    tint = if (item.isFavorite) MonochromeTheme.Gold else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.82f))
                        .padding(3.dp),
                )
            }
        }
        Text(
            text = item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        val updatedAt = item.updatedAt
        if (item.genre.isNotBlank() || !updatedAt.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (item.genre.isNotBlank()) {
                    Text(
                        text = item.genre,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!updatedAt.isNullOrBlank()) {
                    Text(
                        text = updatedAt,
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
