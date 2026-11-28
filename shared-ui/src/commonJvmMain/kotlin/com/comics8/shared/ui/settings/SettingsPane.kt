package com.comics8.shared.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comics8.core.app.AppUiState
import com.comics8.core.app.SettingsCategory
import com.comics8.core.app.SharedAppController
import com.comics8.core.i18n.AppLanguage
import com.comics8.core.i18n.displayLabel
import com.comics8.core.i18n.displayShortLabel
import com.comics8.shared.ui.PlatformBackHandler
import com.comics8.shared.ui.theme.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPane(
    state: AppUiState,
    controller: SharedAppController,
    appVersion: String,
    onExportBackup: (onResult: (Boolean, String) -> Unit) -> Unit,
    onImportBackup: (onResult: (Boolean, String) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    showTapZoneAndVolume: Boolean = false,
    notificationSummary: String? = null,
    notificationSettingsContent: (@Composable () -> Unit)? = null,
) {
    val strings = LocalStrings.current

    PlatformBackHandler(enabled = state.selectedSettingsCategory != null) {
        controller.selectSettingsCategory(null)
    }

    val titleText = when (state.selectedSettingsCategory) {
        null -> strings.navSettings
        SettingsCategory.GENERAL -> strings.sectionGeneralSettings
        SettingsCategory.READER -> strings.sectionViewerSettings
        SettingsCategory.NOTIFICATION -> strings.sectionNotificationSettings
        SettingsCategory.SYNC_BACKUP -> strings.sectionSyncAndBackup
        SettingsCategory.NETWORK_DOWNLOAD -> strings.sectionNetworkAndDownload
        SettingsCategory.ABOUT -> strings.sectionAppInfoAndAbout
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = controller::closeSettings) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.actionGoBack,
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize(),
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.selectedSettingsCategory == null) {
                item {
                    val langSummary = if (state.appLanguage == AppLanguage.AUTO) strings.langAuto else state.appLanguage.nativeName
                    SettingsCategoryCard(
                        title = strings.sectionGeneralSettings,
                        summary = "${strings.labelLanguage}: $langSummary",
                        icon = Icons.Default.Language,
                        onClick = { controller.selectSettingsCategory(SettingsCategory.GENERAL) },
                    )
                }

                item {
                    val viewModeText = state.viewMode.displayLabel(strings)
                    val dirText = state.readDirection.displayShortLabel(strings)
                    val readerSummary = if (showTapZoneAndVolume) {
                        val tapZoneText = state.pageTapZone.displayLabel(strings)
                        "$viewModeText · $dirText · $tapZoneText"
                    } else {
                        "$viewModeText · $dirText"
                    }
                    SettingsCategoryCard(
                        title = strings.sectionViewerSettings,
                        summary = readerSummary,
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        onClick = { controller.selectSettingsCategory(SettingsCategory.READER) },
                    )
                }

                if (notificationSettingsContent != null) {
                    item {
                        SettingsCategoryCard(
                            title = strings.sectionNotificationSettings,
                            summary = notificationSummary ?: strings.descNotificationSettings,
                            icon = Icons.Default.Notifications,
                            onClick = { controller.selectSettingsCategory(SettingsCategory.NOTIFICATION) },
                        )
                    }
                }

                item {
                    val syncSummary = if (state.syncState.lastSyncedAt > 0) {
                        strings.statusCloudConnected
                    } else {
                        strings.descSyncAndBackup
                    }
                    SettingsCategoryCard(
                        title = strings.sectionSyncAndBackup,
                        summary = syncSummary,
                        icon = Icons.Default.CloudSync,
                        onClick = { controller.selectSettingsCategory(SettingsCategory.SYNC_BACKUP) },
                    )
                }

                item {
                    SettingsCategoryCard(
                        title = strings.sectionNetworkAndDownload,
                        summary = strings.descNetworkAndDownload,
                        icon = Icons.Default.Public,
                        onClick = { controller.selectSettingsCategory(SettingsCategory.NETWORK_DOWNLOAD) },
                    )
                }

                item {
                    SettingsCategoryCard(
                        title = strings.sectionAppInfoAndAbout,
                        summary = strings.labelAppVersion(appVersion),
                        icon = Icons.Default.Info,
                        onClick = { controller.selectSettingsCategory(SettingsCategory.ABOUT) },
                    )
                }
            } else {
                item {
                    when (state.selectedSettingsCategory) {
                        SettingsCategory.GENERAL -> GeneralSettingsPane(state, controller)
                        SettingsCategory.READER -> ReaderSettingsPane(
                            state = state,
                            controller = controller,
                            showTapZoneAndVolume = showTapZoneAndVolume,
                        )
                        SettingsCategory.NOTIFICATION -> notificationSettingsContent?.invoke()
                        SettingsCategory.SYNC_BACKUP -> SyncBackupSettingsPane(
                            state = state,
                            controller = controller,
                            onExportBackup = onExportBackup,
                            onImportBackup = onImportBackup,
                        )
                        SettingsCategory.NETWORK_DOWNLOAD -> NetworkSettingsPane(state, controller)
                        SettingsCategory.ABOUT -> AboutSettingsPane(state, controller, appVersion)
                        null -> Unit
                    }
                }
            }
        }
    }
}
