package com.comics8.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comics8.core.app.AppUiState
import com.comics8.core.app.SharedAppController
import com.comics8.core.i18n.AppLanguage
import com.comics8.shared.ui.theme.LocalStrings

@Composable
fun GeneralSettingsPane(
    state: AppUiState,
    controller: SharedAppController,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        SectionTitle(title = strings.sectionGeneralSettings, icon = Icons.Default.Language)

        SettingCard {
            SettingDropdownRow(
                title = strings.labelLanguage,
                selectedOption = state.appLanguage,
                options = AppLanguage.entries,
                optionLabel = { lang ->
                    if (lang == AppLanguage.AUTO) strings.langAuto else lang.nativeName
                },
                onSelect = { controller.setAppLanguage(it) },
            )
        }
    }
}
