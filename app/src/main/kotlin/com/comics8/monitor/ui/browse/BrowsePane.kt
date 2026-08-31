package com.comics8.monitor.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.comics8.core.model.ToonItem
import com.comics8.core.source.SourceRegistry
import com.comics8.monitor.ui.components.Badge
import com.comics8.monitor.ui.components.sourceChipLabel
import com.comics8.monitor.ui.theme.LocalStrings
import com.comics8.monitor.ui.theme.MonochromeTheme

import androidx.compose.foundation.border

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.comics8.core.image.ImageCacheRole
import com.comics8.monitor.data.local.imageCacheRole

@Composable
fun ListingGrid(
    items: List<ToonItem>,
    onOpen: (ToonItem) -> Unit,
    onToggleFavorite: (ToonItem) -> Unit,
    modifier: Modifier = Modifier,
    scrollToTopTrigger: Int = 0,
    showSourceChip: Boolean = false,
    sourceRegistry: SourceRegistry,
    refreshEpoch: Long = 0L,
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(scrollToTopTrigger) {
        if (scrollToTopTrigger > 0) {
            gridState.scrollToItem(0)
        }
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 120.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(items.distinctBy { it.listingKey() }, key = { it.listingKey() }) { item ->
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
            var isError by remember(item.thumbUrl) { mutableStateOf(false) }
            val retryKey = if (isError) refreshEpoch else 0L
            val context = LocalContext.current
            val imageModel = remember(item.thumbUrl, retryKey) {
                ImageRequest.Builder(context)
                    .data(item.thumbUrl)
                    .imageCacheRole(ImageCacheRole.GRID, item.thumbUrl)
                    .apply {
                        if (retryKey > 0L) {
                            memoryCachePolicy(CachePolicy.WRITE_ONLY)
                            diskCachePolicy(CachePolicy.WRITE_ONLY)
                        }
                    }
                    .build()
            }
            AsyncImage(
                model = imageModel,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                onError = { isError = true },
                onSuccess = { isError = false },
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
        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
