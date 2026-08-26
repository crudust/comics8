package com.comics8.desktop.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comics8.core.i18n.AppLanguage
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.ViewMode
import com.comics8.desktop.DesktopVersion
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.theme.LocalStrings
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPane(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val syncState = state.syncState
    var showAdvancedSyncSettings by remember { mutableStateOf(false) }
    var showPairingCodeDialog by remember { mutableStateOf(false) }
    var showConnectDialog by remember { mutableStateOf(false) }
    var backupStatusMessage by remember { mutableStateOf<String?>(null) }
    var isBackupSuccess by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.navSettings,
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // 섹션 0: 언어 설정 (Language Settings)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(title = strings.sectionLanguage, icon = Icons.Default.Language)

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
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
            }

            // 섹션 1: 클라우드 동기화 및 기기 연결
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(title = strings.sectionCloudSyncAndPairing, icon = Icons.Default.CloudSync)

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            // 1. 동기화 상태 및 즉시 동기화 버튼
                            val timeText = if (syncState.lastSyncedAt > 0) {
                                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                                strings.labelLastSyncedAt(sdf.format(Date(syncState.lastSyncedAt)))
                            } else {
                                strings.labelNoSyncHistory
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(
                                                    if (syncState.isSyncing) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.tertiary,
                                                ),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            text = if (syncState.isSyncing) strings.statusSyncInProgress else strings.statusCloudConnected,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = timeText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                Button(
                                    onClick = { viewModel.syncNow() },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                    ),
                                    enabled = !syncState.isSyncing,
                                ) {
                                    if (syncState.isSyncing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(strings.statusSyncing)
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(strings.actionSyncNow, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            // 2. 기기 페어링 액션 버튼들
                            FilledTonalButton(
                                onClick = { showPairingCodeDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Devices,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = strings.actionOpenPairingDialog,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            OutlinedButton(
                                onClick = { showConnectDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = strings.actionOpenConnectDialog,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            )

                            // 3. 백그라운드 자동 동기화 & 프록시 스위치
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.labelAutoSync,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = strings.descAutoSync,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = syncState.autoSyncEnabled,
                                    onCheckedChange = { viewModel.toggleAutoSync(it) },
                                )
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = strings.labelServerProxy,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = strings.descServerProxy,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(
                                    checked = syncState.useServerProxy,
                                    onCheckedChange = { viewModel.toggleServerProxy(it) },
                                )
                            }

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            )

                            // 4. 고급 동기화 설정 (마스터 복구 키)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAdvancedSyncSettings = !showAdvancedSyncSettings }
                                    .padding(vertical = 4.dp),
                            ) {
                                Icon(
                                    imageVector = if (showAdvancedSyncSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (showAdvancedSyncSettings) strings.labelFoldAdvancedSync else strings.labelUnfoldAdvancedSync,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            if (showAdvancedSyncSettings) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    // 마스터 복구 키 표시 카드
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                                            .border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                                shape = RoundedCornerShape(8.dp),
                                            )
                                            .padding(10.dp),
                                    ) {
                                        Column {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Text(
                                                    text = strings.labelMyMasterKey,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Spacer(Modifier.weight(1f))
                                                IconButton(
                                                    onClick = {
                                                        try {
                                                            val sel = StringSelection(syncState.syncKey)
                                                            Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                                                        } catch (_: Exception) {}
                                                    },
                                                    modifier = Modifier.size(24.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.ContentCopy,
                                                        contentDescription = strings.actionCopy,
                                                        modifier = Modifier.size(14.dp),
                                                    )
                                                }
                                            }
                                            Text(
                                                text = syncState.syncKey.ifBlank { strings.statusIssuingKey },
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(vertical = 2.dp),
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.End,
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                TextButton(onClick = { viewModel.generateNewSyncKey() }) {
                                                    Text(strings.actionReissueMasterKey, style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 섹션 2: 로컬 파일 백업 및 복원 (오프라인/영구 보관)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(title = strings.sectionBackupAndRestore, icon = Icons.Default.Save)

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = strings.descBackupAndRestore,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        try {
                                            val timeTag = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                                            val dialog = FileDialog(null as Frame?, strings.titleSaveBackupFile, FileDialog.SAVE)
                                            dialog.file = "comics8_backup_$timeTag.json"
                                            dialog.isVisible = true
                                            val dir = dialog.directory
                                            val file = dialog.file
                                            if (dir != null && file != null) {
                                                viewModel.exportBackup(File(dir, file)) { success, msg ->
                                                    isBackupSuccess = success
                                                    backupStatusMessage = msg
                                                }
                                            }
                                        } catch (e: Exception) {
                                            isBackupSuccess = false
                                            backupStatusMessage = "${strings.msgBackupExportFailed}: ${e.message}"
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FileDownload,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(strings.actionExportBackup)
                                }

                                OutlinedButton(
                                    onClick = {
                                        try {
                                            val dialog = FileDialog(null as Frame?, strings.titleOpenBackupFile, FileDialog.LOAD)
                                            dialog.setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
                                            dialog.isVisible = true
                                            val dir = dialog.directory
                                            val file = dialog.file
                                            if (dir != null && file != null) {
                                                viewModel.importBackup(File(dir, file)) { success, msg ->
                                                    isBackupSuccess = success
                                                    backupStatusMessage = msg
                                                }
                                            }
                                        } catch (e: Exception) {
                                            isBackupSuccess = false
                                            backupStatusMessage = "${strings.msgBackupRestoreFailed}: ${e.message}"
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FileUpload,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(strings.actionImportBackup)
                                }
                            }

                            if (backupStatusMessage != null) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isBackupSuccess) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                        )
                                        .padding(10.dp),
                                ) {
                                    Text(
                                        text = backupStatusMessage.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isBackupSuccess) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 섹션 3: 뷰어 기본 설정
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(title = strings.sectionViewerSettings, icon = Icons.AutoMirrored.Filled.MenuBook)

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
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
                    }
                }
            }

            // 섹션 4: 앱 정보
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(title = strings.sectionAppInfo, icon = Icons.Default.Info)

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Column {
                                Text(
                                    text = strings.labelAppName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = strings.labelAppVersion(DesktopVersion.VERSION_NAME, DesktopVersion.VERSION_CODE),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            var updateFeedbackMessage by remember { mutableStateOf<String?>(null) }
                            val isChecking = state.updateState.isChecking

                            Column(horizontalAlignment = Alignment.End) {
                                Button(
                                    onClick = {
                                        updateFeedbackMessage = null
                                        viewModel.checkForUpdate(manual = true) { msg ->
                                            updateFeedbackMessage = msg
                                        }
                                    },
                                    enabled = !isChecking,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                ) {
                                    if (isChecking) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(strings.statusCheckingUpdate)
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.SystemUpdate,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(strings.actionCheckUpdate)
                                    }
                                }
                                if (updateFeedbackMessage != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = updateFeedbackMessage.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPairingCodeDialog) {
        PairingCodeDialog(
            viewModel = viewModel,
            onDismiss = { showPairingCodeDialog = false },
        )
    }

    if (showConnectDialog) {
        ConnectDeviceDialog(
            viewModel = viewModel,
            onDismiss = { showConnectDialog = false },
        )
    }
}

@Composable
private fun PairingCodeDialog(
    viewModel: DesktopViewModel,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var code by remember { mutableStateOf("") }
    var timeLeftSeconds by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun loadCode() {
        isLoading = true
        errorMessage = null
        viewModel.requestPairingCode { result ->
            isLoading = false
            if (result.success && result.code.isNotEmpty()) {
                code = result.code
                timeLeftSeconds = result.expiresInSeconds
            } else {
                errorMessage = result.message ?: strings.msgPairingCodeIssueFailed
            }
        }
    }

    LaunchedEffect(Unit) {
        loadCode()
    }

    LaunchedEffect(timeLeftSeconds) {
        if (timeLeftSeconds > 0) {
            delay(1000L)
            timeLeftSeconds--
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Devices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = strings.titlePairNewDevice,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = strings.hintPairingCodeGuide,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (errorMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                } else {
                    val formattedCode = if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp),
                        ) {
                            Text(
                                text = formattedCode,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 4.sp,
                            )
                            Spacer(Modifier.height(6.dp))
                            val minutes = timeLeftSeconds / 60
                            val seconds = timeLeftSeconds % 60
                            val timerText = if (timeLeftSeconds > 0) {
                                strings.labelPairingTimeLeft(minutes, seconds)
                            } else {
                                strings.errorPairingCodeExpired
                            }
                            Text(
                                text = timerText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (timeLeftSeconds > 0) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(
                            onClick = {
                                try {
                                    val sel = StringSelection(code)
                                    Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                                } catch (_: Exception) {}
                            },
                        ) {
                            Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(strings.actionCopyCode, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { loadCode() }, enabled = !isLoading) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.actionIssueNewCode)
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(strings.actionClose)
            }
        },
    )
}

@Composable
private fun ConnectDeviceDialog(
    viewModel: DesktopViewModel,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var pairingCodeInput by remember { mutableStateOf("") }
    var recoveryKeyInput by remember { mutableStateOf("") }
    var useRecoveryKeyMode by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (useRecoveryKeyMode) strings.titleConnectWithMasterKey else strings.titleImportExistingData,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!useRecoveryKeyMode) {
                    Text(
                        text = strings.hintInput6DigitCode,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = pairingCodeInput,
                        onValueChange = {
                            val filtered = it.filter { ch -> ch.isDigit() }.take(6)
                            pairingCodeInput = filtered
                        },
                        placeholder = { Text(strings.placeholder6DigitCode, style = MaterialTheme.typography.bodyMedium) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { useRecoveryKeyMode = true }) {
                            Text(strings.actionSwitchToMasterKeyMode, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Text(
                        text = strings.hintMasterKeyGuide,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = recoveryKeyInput,
                        onValueChange = { recoveryKeyInput = it.trim().uppercase() },
                        placeholder = { Text(strings.placeholderMasterKey, style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { useRecoveryKeyMode = false }) {
                            Text(strings.actionSwitchToPairingCodeMode, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                if (statusMessage != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            )
                            .padding(10.dp),
                    ) {
                        Text(
                            text = statusMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isConnecting = true
                    statusMessage = null
                    isError = false
                    if (!useRecoveryKeyMode) {
                        viewModel.confirmPairingCode(pairingCodeInput) { result ->
                            isConnecting = false
                            if (result.success) {
                                onDismiss()
                            } else {
                                isError = true
                                statusMessage = result.message ?: strings.msgDataImportFailed
                            }
                        }
                    } else {
                        val key = recoveryKeyInput.trim()
                        if (key.isNotBlank()) {
                            viewModel.updateSyncKey(key)
                            viewModel.syncNow()
                            onDismiss()
                        } else {
                            isConnecting = false
                            isError = true
                            statusMessage = strings.msgInvalidMasterKey
                        }
                    }
                },
                enabled = !isConnecting && (if (!useRecoveryKeyMode) pairingCodeInput.length == 6 else recoveryKeyInput.isNotBlank()),
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(strings.statusConnecting)
                } else {
                    Text(strings.actionImportData)
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(strings.actionCancel)
            }
        },
    )
}

@Composable
private fun SectionTitle(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ViewModeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
