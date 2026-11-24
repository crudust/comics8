package com.comics8.shared.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comics8.core.app.AppUiState
import com.comics8.core.app.SharedAppController
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.model.BrowseTab
import com.comics8.core.model.ToonItem
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.shared.ui.ComicImage
import com.comics8.shared.ui.components.Badge
import com.comics8.shared.ui.components.EmptySourcePane
import com.comics8.shared.ui.components.ErrorPane
import com.comics8.shared.ui.components.LoadingPane
import com.comics8.shared.ui.components.PageBar
import com.comics8.shared.ui.components.sourceChipLabel
import com.comics8.shared.ui.theme.LocalStrings
import com.comics8.shared.ui.theme.MonochromeTheme

@Composable
fun BrowsePane(
    state: AppUiState,
    controller: SharedAppController,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    onAddSource: (() -> Unit)? = null,
    onImportJs: (() -> Unit)? = null,
    onAddLibraryFolder: () -> Unit = {},
) {
    val strings = LocalStrings.current
    val isLocal = state.activeSourceId == WorkId.LOCAL_SOURCE
    val pageCatalog = state.tab ?: state.browseTabs.firstOrNull()
    val errorMsg = state.error

    when {
        !state.packsReady -> {
            LoadingPane(modifier = modifier.fillMaxSize())
        }
        state.activeSourceId == null -> {
            EmptySourcePane(
                modifier = modifier.fillMaxSize(),
                onAddSource = onAddSource ?: controller::openAddSourceSheet,
                onImportJs = onImportJs,
            )
        }
        isLocal && state.libraryRoots.isEmpty() && !state.loading && !state.isSearch -> {
            EmptyLibraryPane(
                onAddFolder = onAddLibraryFolder,
                modifier = modifier.fillMaxSize(),
            )
        }
        state.isSearch -> {
            when {
                state.loading && state.items.isEmpty() -> LoadingPane(modifier = modifier.fillMaxSize())
                errorMsg != null && state.items.isEmpty() -> ErrorPane(
                    message = errorMsg,
                    onRetry = { controller.refresh(force = true) },
                    modifier = modifier.fillMaxSize(),
                )
                state.items.isEmpty() -> ErrorPane(
                    message = strings.searchResultEmpty(state.searchQuery.orEmpty()),
                    actionLabel = strings.navBackToList,
                    onRetry = { state.tab?.let(controller::selectTab) },
                    modifier = modifier.fillMaxSize(),
                )
                else -> ListingGrid(
                    items = state.items,
                    gridState = gridState,
                    scrollToTopTrigger = state.scrollToTopTrigger,
                    viewportWidthDp = state.viewportWidthDp,
                    onOpen = controller::onListingOpen,
                    onToggleFavorite = { controller.toggleFavorite(it) },
                    showSourceChip = false,
                    sourceRegistry = state.sourceRegistry,
                    refreshEpoch = state.refreshEpoch,
                    modifier = modifier.fillMaxSize(),
                )
            }
        }
        pageCatalog == null -> {
            LoadingPane(modifier = modifier.fillMaxSize())
        }
        state.loading && state.items.isEmpty() -> {
            Column(modifier = modifier.fillMaxSize()) {
                LoadingPane(Modifier.weight(1f).fillMaxWidth())
                if (pageCatalog.paginated && state.lastPage > 1) {
                    PageBar(
                        page = state.page,
                        lastPage = state.lastPage,
                        onPrev = controller::prevPage,
                        onNext = controller::nextPage,
                        onJump = { controller.togglePageJump(true) },
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        errorMsg != null && state.items.isEmpty() -> {
            ErrorPane(
                message = errorMsg,
                onRetry = { controller.refresh(force = true) },
                modifier = modifier.fillMaxSize(),
            )
        }
        pageCatalog is BrowseTab.Favorite && state.items.isEmpty() -> {
            ErrorPane(
                message = strings.emptyFavorites,
                actionLabel = if (state.viewportWidthDp >= 720) strings.navBackToLibrary else strings.navToLatest,
                onRetry = {
                    val dest = state.browseTabs.firstOrNull { it is BrowseTab.Remote } ?: state.tab
                    dest?.let(controller::selectTab)
                },
                modifier = modifier.fillMaxSize(),
            )
        }
        else -> {
            Column(modifier = modifier.fillMaxSize()) {
                if (isLocal && state.items.isEmpty() && pageCatalog !is BrowseTab.Favorite) {
                    EmptyLibraryScanHint(modifier = Modifier.weight(1f).fillMaxWidth())
                } else {
                    ListingGrid(
                        items = state.items,
                        gridState = gridState,
                        scrollToTopTrigger = state.scrollToTopTrigger,
                        viewportWidthDp = state.viewportWidthDp,
                        onOpen = controller::onListingOpen,
                        onToggleFavorite = { controller.toggleFavorite(it) },
                        showSourceChip = false,
                        sourceRegistry = state.sourceRegistry,
                        refreshEpoch = state.refreshEpoch,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                if (pageCatalog.paginated && state.lastPage > 1) {
                    PageBar(
                        page = state.page,
                        lastPage = state.lastPage,
                        onPrev = controller::prevPage,
                        onNext = controller::nextPage,
                        onJump = { controller.togglePageJump(true) },
                        enabled = !state.loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyLibraryPane(
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
fun EmptyLibraryScanHint(modifier: Modifier = Modifier) {
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
fun ListingGrid(
    items: List<ToonItem>,
    onOpen: (ToonItem) -> Unit,
    onToggleFavorite: (ToonItem) -> Unit,
    modifier: Modifier = Modifier,
    gridState: LazyGridState = rememberLazyGridState(),
    scrollToTopTrigger: Int = 0,
    viewportWidthDp: Int = 0,
    showSourceChip: Boolean = false,
    sourceRegistry: SourceRegistry,
    refreshEpoch: Long = 0L,
) {
    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            gridState.scrollToItem(0)
        }
    }

    val isDesktopLayout = viewportWidthDp >= 720
    val minCellSize = if (isDesktopLayout) 160.dp else 120.dp
    val distinctItems = remember(items) { items.distinctBy { it.listingKey() } }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = minCellSize),
        contentPadding = PaddingValues(
            horizontal = if (isDesktopLayout) 16.dp else 12.dp,
            vertical = if (isDesktopLayout) 16.dp else 10.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(if (isDesktopLayout) 12.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (isDesktopLayout) 16.dp else 14.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(distinctItems, key = { it.listingKey() }) { item ->
            ToonCard(
                item = item,
                onClick = { onOpen(item) },
                onToggleFavorite = { onToggleFavorite(item) },
                showSourceChip = showSourceChip,
                sourceRegistry = sourceRegistry,
                refreshEpoch = refreshEpoch,
            )
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
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp),
                ),
        ) {
            ComicImage(
                url = item.thumbUrl,
                cacheRole = ImageCacheRole.GRID,
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
                    tint = if (item.isFavorite) {
                        MonochromeTheme.Gold
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    },
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
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
        val updatedAt = item.updatedAt
        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (item.genre.isNotBlank() || !updatedAt.isNullOrBlank()) {
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
