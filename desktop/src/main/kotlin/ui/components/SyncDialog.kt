package com.comics8.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.theme.LocalStrings
import kotlinx.coroutines.delay
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SyncDialog(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val syncState = state.syncState
    var showAdvancedSettings by remember { mutableStateOf(false) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var showConnectDialog by remember { mutableStateOf(false) }

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
                    text = strings.titleCloudSync,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = strings.descCloudSync,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 1. 기기 페어링 액션 버튼들
                FilledTonalButton(
                    onClick = { showPairingDialog = true },
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
                        text = strings.actionOpenConnectDialogShort,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                // 2. 백그라운드 동기화 스위치
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.labelAutoSync,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
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

                // 3. 서버 우회 요청 스위치
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = strings.labelServerProxy,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
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

                // 4. 고급 동기화 설정 (접이식)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAdvancedSettings = !showAdvancedSettings }
                        .padding(vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = if (showAdvancedSettings) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (showAdvancedSettings) strings.labelFoldAdvancedSync else strings.labelUnfoldAdvancedSync,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                if (showAdvancedSettings) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
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
                                                Toolkit.getDefaultToolkit().systemClipboard.setContents(
                                                    StringSelection(syncState.syncKey),
                                                    null,
                                                )
                                            } catch (_: Exception) {}
                                        },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = strings.actionCopy, modifier = Modifier.size(14.dp))
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
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = { viewModel.generateNewSyncKey() }) {
                                        Text(strings.actionReissueMasterKey, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. 마지막 동기화 시간
                val timeText = if (syncState.lastSyncedAt > 0) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    strings.labelLastSyncedAt(sdf.format(Date(syncState.lastSyncedAt)))
                } else {
                    strings.labelNoSyncHistory
                }
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { viewModel.syncNow() },
                enabled = !syncState.isSyncing,
            ) {
                if (syncState.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(strings.statusSyncing)
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(strings.actionSyncNow)
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(strings.actionClose)
            }
        },
    )

    if (showPairingDialog) {
        DesktopSyncDialogPairingCode(
            viewModel = viewModel,
            onDismiss = { showPairingDialog = false },
        )
    }

    if (showConnectDialog) {
        DesktopSyncDialogConnectDevice(
            viewModel = viewModel,
            onDismiss = { showConnectDialog = false },
        )
    }
}

@Composable
private fun DesktopSyncDialogPairingCode(
    viewModel: DesktopViewModel,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    var isLoading by remember { mutableStateOf(true) }
    var code by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var timeLeftSeconds by remember { mutableStateOf(300) }

    fun loadCode() {
        isLoading = true
        errorMessage = null
        viewModel.requestPairingCode { result ->
            isLoading = false
            if (result.success) {
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

    LaunchedEffect(code, timeLeftSeconds) {
        if (code.isNotBlank() && timeLeftSeconds > 0) {
            delay(1000)
            timeLeftSeconds -= 1
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
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
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
                        Text(text = errorMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                } else {
                    val formattedCode = if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
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
                                color = if (timeLeftSeconds > 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            try {
                                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(code), null)
                            } catch (_: Exception) {}
                        },
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(strings.actionCopy, style = MaterialTheme.typography.labelSmall)
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
private fun DesktopSyncDialogConnectDevice(
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
                                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
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
                            viewModel.restoreWithMasterKey(key) { result ->
                                isConnecting = false
                                if (result.success) {
                                    onDismiss()
                                } else {
                                    isError = true
                                    statusMessage = result.message.ifBlank { strings.msgDataImportFailed }
                                }
                            }
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
