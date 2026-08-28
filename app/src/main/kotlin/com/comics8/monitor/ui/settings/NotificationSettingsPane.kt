package com.comics8.monitor.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comics8.core.i18n.displayTitle
import com.comics8.monitor.ui.ListingUiState
import com.comics8.monitor.ui.ListingViewModel
import com.comics8.monitor.ui.theme.LocalStrings

@Composable
fun NotificationSettingsPane(
    state: ListingUiState,
    viewModel: ListingViewModel,
    permissionLauncher: ManagedActivityResultLauncher<String, Boolean>,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        SectionTitle(title = strings.sectionNotificationSettings, icon = Icons.Default.Notifications)

        SettingCard {
            // 1. 알림 활성화 스위치
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.labelPeriodicUpdateNotification,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = strings.descPeriodicUpdateNotification,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = state.notificationEnabled,
                    onCheckedChange = { checked ->
                        viewModel.toggleNotificationEnabled(checked)
                        if (checked && !state.hasNotificationPermission && Build.VERSION.SDK_INT >= 33) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }

            // 2. 권한 부족 경고 배너
            if (state.notificationEnabled && !state.hasNotificationPermission && Build.VERSION.SDK_INT >= 33) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = strings.labelNotificationPermissionRequired,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = strings.descNotificationPermissionRequired,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(strings.actionGrantPermission, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            if (state.notificationEnabled) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                Spacer(Modifier.height(16.dp))

                // 3. 알림 확인 간격
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = strings.labelNotificationInterval,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val intervals = listOf(
                        15L to strings.interval15m,
                        30L to strings.interval30m,
                        60L to strings.interval1h,
                        180L to strings.interval3h,
                        360L to strings.interval6h,
                        720L to strings.interval12h,
                        1440L to strings.interval24h,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        intervals.chunked(4).forEach { rowIntervals ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                rowIntervals.forEach { (mins, label) ->
                                    val selected = state.notificationIntervalMinutes == mins
                                    ViewModeOption(
                                        label = label,
                                        selected = selected,
                                        onClick = { viewModel.setNotificationIntervalMinutes(mins) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(4 - rowIntervals.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // 4. 알림 대상 소스
                if (state.installedSources.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                    Spacer(Modifier.height(16.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = strings.labelNotificationSources,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = strings.descNotificationSources,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        state.installedSources.forEach { src ->
                            val isSrcEnabled = state.notificationSources[src.id]
                                ?: viewModel.getSourceNotificationEnabled(src.id)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(
                                    text = src.displayTitle(strings),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = isSrcEnabled,
                                    onCheckedChange = { checked ->
                                        viewModel.toggleSourceNotification(src.id, checked)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
