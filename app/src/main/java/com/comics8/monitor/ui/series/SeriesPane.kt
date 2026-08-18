package com.comics8.monitor.ui.series

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.comics8.monitor.data.EpisodeItem
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
                    items(state.episodes, key = { it.wrId }) { episode ->
                        EpisodeRow(
                            episode = episode,
                            highlighted = episode.wrId == state.highlightedEpisodeId,
                            isDownloaded = episode.wrId in state.downloadedWrIds,
                            isDownloading = state.downloadProgress.currentTask?.episode?.wrId == episode.wrId,
                            onClick = { viewModel.openEpisode(episode) },
                            onDownload = { viewModel.startDownloadEpisode(episode) },
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
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    isDownloaded: Boolean = false,
    isDownloading: Boolean = false,
    onPickArtist: (() -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (highlighted) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 72.dp, height = 54.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (episode.thumbUrl != null) {
                AsyncImage(
                    model = episode.thumbUrl,
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
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
                    text = "읽음",
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
                    contentDescription = "다른 작가",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
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
                    contentDescription = "다운로드됨",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                else -> Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "이 회차 다운로드",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
