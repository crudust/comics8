package com.comics8.desktop.ui.series

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comics8.desktop.data.EpisodeItem
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.components.ErrorPane
import com.comics8.desktop.ui.components.LoadingPane

@Composable
fun SeriesPane(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
) {
    val series = state.series
    if (series == null) {
        ErrorPane(
            message = "작품 정보가 없습니다.",
            actionLabel = "목록으로",
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
            Column(modifier = modifier.fillMaxSize()) {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    items(state.episodes, key = { it.wrId }) { ep ->
                        EpisodeCard(
                            episode = ep,
                            onClick = { viewModel.openEpisode(ep) },
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
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "이전")
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
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음")
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
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (episode.isRead) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        },
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (episode.isRead) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "읽음",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }

            Text(
                text = episode.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (episode.isRead) FontWeight.Normal else FontWeight.SemiBold,
                color = if (episode.isRead) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            if (!episode.date.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = episode.date,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
