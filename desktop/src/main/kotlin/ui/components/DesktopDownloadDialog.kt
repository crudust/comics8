package com.comics8.desktop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
import com.comics8.desktop.ui.theme.LocalStrings

private enum class DownloadMode {
    ALL,
    UNREAD,
    AFTER_ORDER,
}

@Composable
fun DesktopDownloadDialog(
    series: ToonItem,
    episodes: List<EpisodeItem>,
    catalogLoading: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (List<EpisodeItem>) -> Unit,
) {
    val strings = LocalStrings.current
    var selectedMode by remember { mutableStateOf(DownloadMode.UNREAD) }
    var afterOrderInput by remember { mutableStateOf("1") }

    val unreadEpisodes = remember(episodes) { episodes.filter { !it.isRead } }
    val countLabel = if (catalogLoading) strings.loadingSimple else strings.downloadTotalEpisodes(episodes.size)
    val unreadLabel = if (catalogLoading) strings.loadingSimple else strings.downloadUnreadCount(unreadEpisodes.size)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = strings.titleOfflineDownload,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = series.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                if (catalogLoading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = strings.loadingAllEpisodes,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OptionRow(
                    title = strings.downloadOptionAll,
                    subtitle = countLabel,
                    selected = selectedMode == DownloadMode.ALL,
                    onClick = { selectedMode = DownloadMode.ALL },
                )

                OptionRow(
                    title = strings.downloadOptionUnread,
                    subtitle = unreadLabel,
                    selected = selectedMode == DownloadMode.UNREAD,
                    onClick = { selectedMode = DownloadMode.UNREAD },
                )

                OptionRow(
                    title = strings.downloadOptionAfterOrder,
                    subtitle = strings.downloadOptionAfterOrderDesc,
                    selected = selectedMode == DownloadMode.AFTER_ORDER,
                    onClick = { selectedMode = DownloadMode.AFTER_ORDER },
                )

                if (selectedMode == DownloadMode.AFTER_ORDER) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = afterOrderInput,
                        onValueChange = { input ->
                            if (input.all { it.isDigit() }) {
                                afterOrderInput = input
                            }
                        },
                        label = {
                            Text(
                                if (catalogLoading) strings.labelDownloadStartOrder else strings.labelDownloadStartOrderRange(episodes.size),
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = strings.noticeSequentialDownload,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !catalogLoading && episodes.isNotEmpty(),
                onClick = {
                    val targetList = when (selectedMode) {
                        DownloadMode.ALL -> episodes
                        DownloadMode.UNREAD -> unreadEpisodes
                        DownloadMode.AFTER_ORDER -> {
                            val startNum = afterOrderInput.toIntOrNull() ?: 1
                            episodes.filterIndexed { index, _ ->
                                val order = episodes.size - index
                                order >= startNum
                            }
                        }
                    }
                    onConfirm(targetList)
                }
            ) {
                Text(strings.actionStartDownload, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.actionCancel)
            }
        },
    )
}

@Composable
private fun OptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    fontSize = 14.sp,
                )
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
