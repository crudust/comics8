package com.comics8.desktop.ui.downloads

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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.sp
import com.comics8.core.i18n.AppStrings
import com.comics8.core.model.DownloadedToonSummary
import com.comics8.core.model.ToonItem
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.components.EmptySourcePane
import com.comics8.desktop.ui.components.ErrorPane
import com.comics8.desktop.ui.components.LoadingPane
import com.comics8.desktop.ui.theme.LocalStrings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DesktopDownloadsPane(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Column(modifier = modifier.fillMaxSize()) {
        if (state.downloadProgress.isRunning) {
            DesktopDownloadProgressBanner(
                progress = state.downloadProgress,
                onCancel = viewModel::cancelDownloads,
            )
        }

        when {
            !state.packsReady -> {
                LoadingPane(modifier = Modifier.weight(1f))
            }
            state.activeSourceId == null -> {
                EmptySourcePane(
                    modifier = Modifier.weight(1f),
                    onAddSource = viewModel::openAddSourceSheet,
                    onImportJs = {
                        com.comics8.desktop.ui.components.pickJsFile()?.let(viewModel::importJsFile)
                    },
                )
            }
            state.downloadLoading && state.downloadSummaries.isEmpty() -> {
                LoadingPane(modifier = Modifier.weight(1f))
            }
            state.downloadSummaries.isEmpty() -> {
                ErrorPane(
                    message = strings.emptyDownloads,
                    actionLabel = strings.navBackToList,
                    onRetry = viewModel::closeDownloads,
                    modifier = Modifier.weight(1f),
                )
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(state.downloadSummaries.distinctBy { it.workId().storageKey() }, key = { it.workId().storageKey() }) { item ->
                        DownloadedItemCard(
                            item = item,
                            onOpenSeries = {
                                viewModel.openSeries(
                                    ToonItem(
                                        id = item.toonId,
                                        title = item.toonTitle,
                                        thumbUrl = item.toonThumbUrl,
                                        href = item.toonHref,
                                        sourceId = item.sourceId,
                                    )
                                )
                            },
                            onDelete = { viewModel.deleteToonDownloads(item.workId()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopDownloadProgressBanner(
    progress: com.comics8.desktop.data.DesktopDownloadProgressState,
    onCancel: () -> Unit,
) {
    val strings = LocalStrings.current
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (progress.activeToonTitle.isNotBlank()) {
                        strings.downloadInProgressNamed(progress.activeToonTitle)
                    } else {
                        strings.downloadInProgress
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = strings.actionCancel,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            if (progress.totalImages > 0) {
                val fraction = progress.currentImage.toFloat() / progress.totalImages.toFloat()
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                )
            }

            Spacer(Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (progress.totalImages > 0) {
                        strings.downloadProgressImages(progress.currentImage, progress.totalImages, progress.queueSize)
                    } else {
                        strings.downloadProgressPreparing(progress.queueSize)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun DownloadedItemCard(
    item: DownloadedToonSummary,
    onOpenSeries: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSeries),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = item.toonTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = strings.badgeDownloadedEpisodeCount(item.episodeCount),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${formatFileSize(item.totalBytes)} · ${formatRelativeTime(item.latestDownloadedAt, strings)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = onOpenSeries,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = strings.actionViewEpisodes,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = strings.actionDelete,
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
        else -> "$bytes B"
    }
}

private fun formatRelativeTime(timestamp: Long, strings: AppStrings): String {
    if (timestamp <= 0) return ""
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        minutes < 1 -> strings.timeJustNow
        minutes < 60 -> strings.timeMinutesAgo(minutes)
        hours < 24 -> strings.timeHoursAgo(hours)
        days < 7 -> strings.timeDaysAgo(days)
        else -> SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(Date(timestamp))
    }
}
