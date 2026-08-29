package com.comics8.desktop.ui.series

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.sp
import com.comics8.core.model.EpisodeItem
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.components.ErrorPane
import com.comics8.desktop.ui.components.LoadingPane
import com.comics8.desktop.ui.theme.LocalStrings
import com.comics8.desktop.ui.util.DesktopAsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatReadDate(millis: Long): String {
    val sdf = SimpleDateFormat("yy.MM.dd", Locale.getDefault())
    return sdf.format(Date(millis))
}

@Composable
fun SeriesPane(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val series = state.series
    if (series == null) {
        ErrorPane(
            message = strings.errorNoSeriesInfo,
            actionLabel = strings.navBackToList,
            onRetry = viewModel::closeSeries,
            modifier = modifier,
        )
        return
    }

    when {
        state.episodeLoading && state.episodes.isEmpty() -> {
            LoadingPane(modifier = modifier)
        }
        state.episodeError != null && state.episodes.isEmpty() -> {
            ErrorPane(
                message = state.episodeError,
                onRetry = { viewModel.loadEpisodes(series, state.episodePage) },
                modifier = modifier,
            )
        }
        else -> {
            val listState = rememberLazyListState()
            LaunchedEffect(state.scrollToTopTrigger) {
                if (state.scrollToTopTrigger > 0) {
                    listState.scrollToItem(0)
                }
            }
            Column(modifier = modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(state.episodes.distinctBy { it.wrId }, key = { it.wrId }) { ep ->
                        EpisodeCard(
                            episode = ep,
                            highlighted = ep.wrId == state.highlightedEpisodeId,
                            isDownloaded = ep.wrId in state.downloadedWrIds,
                            isDownloading = state.downloadProgress.currentTask?.episode?.wrId == ep.wrId,
                            refreshEpoch = state.refreshEpoch,
                            onClick = { viewModel.openEpisode(ep) },
                            onDownload = if (state.writesDownloads) {
                                { viewModel.startDownloadEpisode(ep) }
                            } else {
                                null
                            },
                            onPickArtist = if (ep.artistChoices.size >= 2) {
                                {
                                    viewModel.openArtistPicker(
                                        series.copy(artistChoices = ep.artistChoices),
                                        entryEpisodeId = ep.wrId,
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }

                // Pagination
                if (state.episodeLastPage > 1) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                            .padding(vertical = 8.dp),
                    ) {
                        IconButton(
                            onClick = { viewModel.goToEpisodePage(state.episodePage - 1) },
                            enabled = state.episodePage > 1,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = strings.actionPrev)
                        }

                        FilledTonalButton(
                            onClick = { viewModel.togglePageJump(true) },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = "${state.episodePage} / ${state.episodeLastPage}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        IconButton(
                            onClick = { viewModel.goToEpisodePage(state.episodePage + 1) },
                            enabled = state.episodePage < state.episodeLastPage,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = strings.actionNext)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EpisodeCard(
    episode: EpisodeItem,
    onClick: () -> Unit,
    onDownload: (() -> Unit)?,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    refreshEpoch: Long = 0L,
    onPickArtist: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = when {
            highlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            episode.isRead -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp),
                    ),
            ) {
                val thumbUrl = episode.thumbUrl
                if (thumbUrl != null) {
                    DesktopAsyncImage(
                        url = thumbUrl,
                        contentDescription = episode.title,
                        contentScale = ContentScale.Crop,
                        refreshEpoch = refreshEpoch,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(
                modifier = Modifier
                    .padding(horizontal = 10.dp)
                    .weight(1f),
            ) {
                Text(
                    text = episode.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (episode.isRead) FontWeight.Normal else FontWeight.Medium,
                    color = if (episode.isRead) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                val date = episode.date
                if (!date.isNullOrBlank()) {
                    Text(
                        text = date,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (episode.isRead) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(end = 6.dp),
                ) {
                    Text(
                        text = strings.badgeRead,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    val readAt = episode.readAt
                    if (readAt != null) {
                        Text(
                            text = formatReadDate(readAt),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            if (onPickArtist != null) {
                IconButton(
                    onClick = onPickArtist,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = strings.actionOtherArtists,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (onDownload != null) {
                IconButton(
                    onClick = onDownload,
                    enabled = !isDownloaded && !isDownloading,
                    modifier = Modifier.size(36.dp),
                ) {
                    when {
                        isDownloading -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        isDownloaded -> Icon(
                            imageVector = Icons.Filled.DownloadDone,
                            contentDescription = strings.labelDownloaded,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        else -> Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = strings.actionDownloadThisEpisode,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
