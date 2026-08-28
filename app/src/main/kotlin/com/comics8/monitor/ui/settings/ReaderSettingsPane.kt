package com.comics8.monitor.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comics8.core.i18n.displayDescription
import com.comics8.core.i18n.displayLabel
import com.comics8.core.model.PageTapZone
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ViewMode
import com.comics8.monitor.ui.ListingUiState
import com.comics8.monitor.ui.ListingViewModel
import com.comics8.monitor.ui.theme.LocalStrings

@Composable
fun ReaderSettingsPane(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        SectionTitle(title = strings.sectionViewerSettings, icon = Icons.AutoMirrored.Filled.MenuBook)

        // 1. 기본 보기 모드 및 읽기 방향
        SettingCard {
            Text(
                text = strings.labelDefaultViewMode,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ViewModeOption(
                    label = strings.viewModeScroll,
                    selected = state.viewMode == ViewMode.SCROLL,
                    onClick = { viewModel.setViewMode(ViewMode.SCROLL) },
                    modifier = Modifier.weight(1f),
                )
                ViewModeOption(
                    label = strings.viewModeSingleLong,
                    selected = state.viewMode == ViewMode.PAGE,
                    onClick = { viewModel.setViewMode(ViewMode.PAGE) },
                    modifier = Modifier.weight(1f),
                )
                ViewModeOption(
                    label = strings.viewModeDualLong,
                    selected = state.viewMode == ViewMode.DUAL,
                    onClick = { viewModel.setViewMode(ViewMode.DUAL) },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
            Spacer(Modifier.height(16.dp))

            Text(
                text = strings.labelReadDirection,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ViewModeOption(
                    label = strings.readDirectionRightToLeft,
                    selected = state.readDirection == ReadDirection.RIGHT_TO_LEFT,
                    onClick = { viewModel.setReadDirection(ReadDirection.RIGHT_TO_LEFT) },
                    modifier = Modifier.weight(1f),
                )
                ViewModeOption(
                    label = strings.readDirectionLeftToRight,
                    selected = state.readDirection == ReadDirection.LEFT_TO_RIGHT,
                    onClick = { viewModel.setReadDirection(ReadDirection.LEFT_TO_RIGHT) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 2. 페이지 넘김 터치 영역 (3가지 옵션)
        SettingCard {
            Text(
                text = strings.labelPageTapZone,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = strings.descPageTapZone,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            val tapZoneOptions = listOf(
                PageTapZone.FOLLOW_DIRECTION,
                PageTapZone.RIGHT_NEXT,
                PageTapZone.LEFT_NEXT,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tapZoneOptions.forEachIndexed { index, zone ->
                    val isSelected = state.pageTapZone == zone
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setPageTapZone(zone) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.setPageTapZone(zone) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = zone.displayLabel(strings),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = zone.displayDescription(strings),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (index < tapZoneOptions.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                }
            }
        }

        // 3. 볼륨 키 넘김 설정
        SettingCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = strings.labelVolumePageTurn,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = strings.descVolumePageTurn,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = state.volumePageTurnEnabled,
                    onCheckedChange = { viewModel.setVolumePageTurnEnabled(it) },
                )
            }
        }
    }
}
