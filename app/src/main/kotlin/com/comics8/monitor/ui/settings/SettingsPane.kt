package com.comics8.monitor.ui.settings

import android.Manifest
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comics8.core.i18n.AppLanguage
import com.comics8.core.i18n.displayLabel
import com.comics8.core.model.ReadDirection
import com.comics8.monitor.BuildConfig
import com.comics8.monitor.ui.ListingUiState
import com.comics8.monitor.ui.ListingViewModel
import com.comics8.monitor.ui.theme.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPane(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val strings = LocalStrings.current

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        viewModel.checkNotificationPermission()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.exportBackup(uri) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importBackup(uri) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = state.selectedSettingsCategory != null) {
        viewModel.selectSettingsCategory(null)
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
                    IconButton(onClick = viewModel::closeSettings) {
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
                // 메인 설정 허브 (Main Category Hub)
                item {
                    val langSummary = if (state.appLanguage == AppLanguage.AUTO) strings.langAuto else state.appLanguage.nativeName
                    SettingsCategoryCard(
                        title = strings.sectionGeneralSettings,
                        summary = "${strings.labelLanguage}: $langSummary",
                        icon = Icons.Default.Language,
                        onClick = { viewModel.selectSettingsCategory(SettingsCategory.GENERAL) },
                    )
                }

                item {
                    val dirText = if (state.readDirection == ReadDirection.RIGHT_TO_LEFT) strings.readDirectionRTLShort else strings.readDirectionLTRShort
                    val tapZoneText = state.pageTapZone.displayLabel(strings)
                    SettingsCategoryCard(
                        title = strings.sectionViewerSettings,
                        summary = "${state.viewMode.label} · $dirText · $tapZoneText",
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        onClick = { viewModel.selectSettingsCategory(SettingsCategory.READER) },
                    )
                }

                item {
                    val notifSummary = if (state.notificationEnabled) {
                        "${strings.labelPeriodicUpdateNotification} (${state.notificationIntervalMinutes}분)"
                    } else {
                        strings.descNotificationSettings
                    }
                    SettingsCategoryCard(
                        title = strings.sectionNotificationSettings,
                        summary = notifSummary,
                        icon = Icons.Default.Notifications,
                        onClick = { viewModel.selectSettingsCategory(SettingsCategory.NOTIFICATION) },
                    )
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
                        onClick = { viewModel.selectSettingsCategory(SettingsCategory.SYNC_BACKUP) },
                    )
                }

                item {
                    SettingsCategoryCard(
                        title = strings.sectionNetworkAndDownload,
                        summary = strings.descNetworkAndDownload,
                        icon = Icons.Default.Public,
                        onClick = { viewModel.selectSettingsCategory(SettingsCategory.NETWORK_DOWNLOAD) },
                    )
                }

                item {
                    SettingsCategoryCard(
                        title = strings.sectionAppInfoAndAbout,
                        summary = strings.labelAppVersion(BuildConfig.VERSION_NAME),
                        icon = Icons.Default.Info,
                        onClick = { viewModel.selectSettingsCategory(SettingsCategory.ABOUT) },
                    )
                }
            } else {
                // 서브 페이지 렌더링 (Sub-Screen Routing)
                item {
                    when (state.selectedSettingsCategory) {
                        SettingsCategory.GENERAL -> GeneralSettingsPane(state, viewModel)
                        SettingsCategory.READER -> ReaderSettingsPane(state, viewModel)
                        SettingsCategory.NOTIFICATION -> NotificationSettingsPane(state, viewModel, permissionLauncher)
                        SettingsCategory.SYNC_BACKUP -> SyncBackupSettingsPane(state, viewModel, exportLauncher, importLauncher)
                        SettingsCategory.NETWORK_DOWNLOAD -> NetworkSettingsPane(state, viewModel)
                        SettingsCategory.ABOUT -> AboutSettingsPane(state, viewModel)
                    }
                }
            }
        }
    }
}
