package com.comics8.monitor.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comics8.core.i18n.AppLanguage
import com.comics8.monitor.ui.ListingUiState
import com.comics8.monitor.ui.ListingViewModel
import com.comics8.monitor.ui.theme.LocalStrings

@Composable
fun GeneralSettingsPane(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        SectionTitle(title = strings.sectionLanguage, icon = Icons.Default.Language)

        SettingCard {
            Text(
                text = strings.labelLanguage,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            val languages = AppLanguage.entries
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                languages.chunked(3).forEach { rowLangs ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rowLangs.forEach { lang ->
                            val selected = state.appLanguage == lang
                            ViewModeOption(
                                label = if (lang == AppLanguage.AUTO) strings.langAuto else lang.nativeName,
                                selected = selected,
                                onClick = { viewModel.setAppLanguage(lang) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(3 - rowLangs.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
