package com.comics8.monitor.ui.series

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.comics8.core.model.EpisodeItem
import com.comics8.monitor.ui.theme.LocalStrings
import com.comics8.monitor.ui.ListingUiState
import com.comics8.monitor.ui.ListingViewModel
import com.comics8.monitor.ui.components.ErrorPane
import com.comics8.monitor.ui.components.LoadingPane
import com.comics8.monitor.ui.components.PageBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatReadDate(millis: Long): String {
    val sdf = SimpleDateFormat("yy.MM.dd", Locale.getDefault())
    return sdf.format(Date(millis))
}

@Composable
fun SeriesPane(
    state: ListingUiState,
    viewModel: ListingViewModel,
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
        state.episodeLoading && state.episodes.isEmpty() -> LoadingPane(modifier)
        state.episodeError != null && state.episodes.isEmpty() ->
            ErrorPane(
                message = state.episodeError,
                modifier = modifier,
                onRetry = viewModel::refresh,
            )
        else -> {
            Column(modifier = modifier.fillMaxSize()) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(state.episodes.distinctBy { it.wrId }, key = { it.wrId }) { episode ->
                        EpisodeRow(
                            episode = episode,
                            highlighted = episode.wrId == state.highlightedEpisodeId,
                            isDownloaded = episode.wrId in state.downloadedWrIds,
                            isDownloading = state.downloadProgress.currentTask?.episode?.wrId == episode.wrId,
                            refreshEpoch = state.refreshEpoch,
                            onClick = { viewModel.openEpisode(episode) },
                            onDownload = if (state.writesDownloads) {
                                { viewModel.startDownloadEpisode(episode) }
                            } else {
                                null
                            },
                            onPickArtist = if (episode.artistChoices.size >= 2) {
                                {
                                    val series = state.series ?: return@EpisodeRow
                                    viewModel.openArtistPicker(
                                        series.copy(artistChoices = episode.artistChoices),
                                        entryEpisodeId = episode.wrId,
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
                if (state.episodeLastPage > 1) {
                    PageBar(
                        page = state.episodePage,
                        lastPage = state.episodeLastPage,
                        onPrev = { viewModel.goToEpisodePage(state.episodePage - 1) },
                        onNext = { viewModel.goToEpisodePage(state.episodePage + 1) },
                        onJump = { viewModel.togglePageJump(true) },
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
fun EpisodeRow(
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
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (highlighted) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .border(
                width = 1.dp,
                color = if (highlighted) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                },
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            val thumbUrl = episode.thumbUrl
            if (thumbUrl != null) {
                var isError by remember(thumbUrl) { mutableStateOf(false) }
                val retryKey = if (isError) refreshEpoch else 0L
                val context = LocalContext.current
                val imageModel = remember(thumbUrl, retryKey) {
                    if (retryKey > 0L) {
                        ImageRequest.Builder(context)
                            .data(thumbUrl)
                            .memoryCachePolicy(CachePolicy.WRITE_ONLY)
                            .diskCachePolicy(CachePolicy.WRITE_ONLY)
                            .build()
                    } else {
                        thumbUrl
                    }
                }
                AsyncImage(
                    model = imageModel,
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
                    onError = { isError = true },
                    onSuccess = { isError = false },
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
                fontWeight = if (episode.isRead) FontWeight.Normal else FontWeight.SemiBold,
                color = if (episode.isRead) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            val date = episode.date
            if (!date.isNullOrBlank()) {
                Text(
                    text = date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
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
