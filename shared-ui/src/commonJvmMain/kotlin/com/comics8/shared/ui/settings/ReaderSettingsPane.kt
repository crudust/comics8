package com.comics8.shared.ui.settings

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
import com.comics8.core.app.AppUiState
import com.comics8.core.app.SharedAppController
import com.comics8.core.i18n.displayDescription
import com.comics8.core.i18n.displayLabel
import com.comics8.core.model.PageTapZone
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ViewMode
import com.comics8.shared.ui.theme.LocalStrings

@Composable
fun ReaderSettingsPane(
    state: AppUiState,
    controller: SharedAppController,
    modifier: Modifier = Modifier,
    showTapZoneAndVolume: Boolean = false,
) {
    val strings = LocalStrings.current

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        SectionTitle(title = strings.sectionViewerSettings, icon = Icons.AutoMirrored.Filled.MenuBook)

        SettingCard {
            Text(
                text = strings.labelDefaultViewMode,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))

            val viewModes = listOf(ViewMode.AUTO, ViewMode.SCROLL, ViewMode.PAGE, ViewMode.SPLIT, ViewMode.DUAL)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                viewModes.forEachIndexed { index, mode ->
                    val isSelected = state.viewMode == mode
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { controller.setViewMode(mode) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { controller.setViewMode(mode) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode.displayLabel(strings),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = mode.displayDescription(strings),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (index < viewModes.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                }
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

            val readDirections = listOf(ReadDirection.RIGHT_TO_LEFT, ReadDirection.LEFT_TO_RIGHT)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                readDirections.forEachIndexed { index, dir ->
                    val isSelected = state.readDirection == dir
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { controller.setReadDirection(dir) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { controller.setReadDirection(dir) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = dir.displayLabel(strings),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = dir.displayDescription(strings),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (index < readDirections.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                }
            }
        }

        if (showTapZoneAndVolume) {
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
                                .clickable { controller.setPageTapZone(zone) }
                                .padding(vertical = 4.dp),
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { controller.setPageTapZone(zone) },
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
                        onCheckedChange = { controller.setVolumePageTurnEnabled(it) },
                    )
                }
            }
        }
    }
}
