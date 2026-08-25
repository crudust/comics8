package com.comics8.monitor.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comics8.monitor.ui.ListingUiState
import com.comics8.monitor.ui.ListingViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SyncDialog(
    state: ListingUiState,
    viewModel: ListingViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val syncState = state.syncState
    var editServerUrl by remember(syncState.serverUrl) { mutableStateOf(syncState.serverUrl) }
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
                    text = "클라우드 자동 동기화",
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
                    text = "개인정보 없이 6자리 페어링 코드로 다른 스마트폰, Mac, PC와 즐겨찾기 및 읽음 진행도를 실시간 동기화합니다.",
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
                        text = "새 기기 연결하기 (6자리 코드 발급)",
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
                        text = "다른 기기의 데이터 가져오기",
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
                            text = "백그라운드 자동 동기화",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "만화 열람/즐겨찾기 시 서버에 자동 저장",
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
                            text = "서버 IP 우회 요청",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "사이트 메타데이터 요청을 서버 IP로 중계",
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
                        text = if (showAdvancedSettings) "고급 설정 접기" else "고급 설정 (마스터 복구 키, 서버 주소)",
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
                                        text = "내 마스터 복구 키",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("Comics8 Master Key", syncState.syncKey)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, "복구 키가 복사되었습니다.", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(24.dp),
                                    ) {
                                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = "복사", modifier = Modifier.size(14.dp))
                                    }
                                }
                                Text(
                                    text = syncState.syncKey.ifBlank { "키 생성 중..." },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                    TextButton(onClick = { viewModel.generateNewSyncKey() }) {
                                        Text("새 마스터 키 재발급", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = editServerUrl,
                            onValueChange = {
                                editServerUrl = it
                                viewModel.updateSyncServerUrl(it)
                            },
                            label = { Text("서버 URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // 5. 마지막 동기화 시간
                val timeText = if (syncState.lastSyncedAt > 0) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    "마지막 동기화: ${sdf.format(Date(syncState.lastSyncedAt))}"
                } else {
                    "동기화 이력 없음"
                }
                Text(
                    text = timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 6. 상태 메시지 배너
                if (syncState.syncMessage != null) {
                    val isSuccess = syncState.isSuccess == true
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSuccess) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            )
                            .padding(10.dp),
                    ) {
                        Text(
                            text = syncState.syncMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSuccess) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
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
                    Text("동기화 중...")
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("지금 즉시 동기화")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("닫기")
            }
        },
    )

    if (showPairingDialog) {
        SyncDialogPairingCode(
            viewModel = viewModel,
            onDismiss = { showPairingDialog = false },
        )
    }

    if (showConnectDialog) {
        SyncDialogConnectDevice(
            viewModel = viewModel,
            onDismiss = { showConnectDialog = false },
        )
    }
}

@Composable
private fun SyncDialogPairingCode(
    viewModel: ListingViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
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
                errorMessage = result.message ?: "코드 발급 실패"
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
                    text = "새 기기 연결 (페어링)",
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
                    text = "새로 연결할 기기의 [데이터 가져오기] 화면에서 아래 6자리 코드를 입력하세요.",
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
                                String.format(Locale.getDefault(), "⏱️ 남은 시간: %02d:%02d", minutes, seconds)
                            } else {
                                "⚠️ 코드가 만료되었습니다."
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
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Pairing Code", code)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "6자리 코드가 복사되었습니다.", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("코드 복사", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { loadCode() }, enabled = !isLoading) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("새 코드 발급")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("닫기")
            }
        },
    )
}

@Composable
private fun SyncDialogConnectDevice(
    viewModel: ListingViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
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
                    text = if (useRecoveryKeyMode) "마스터 복구 키로 연결" else "기존 데이터 가져오기",
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
                        text = "기존에 사용하던 기기의 [새 기기 연결하기]에 표시된 6자리 번호를 입력하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = pairingCodeInput,
                        onValueChange = {
                            val filtered = it.filter { ch -> ch.isDigit() }.take(6)
                            pairingCodeInput = filtered
                        },
                        placeholder = { Text("예: 849201", style = MaterialTheme.typography.bodyMedium) },
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
                            Text("기존 기기가 없나요? 마스터 복구 키로 복원", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Text(
                        text = "이전에 저장해 둔 마스터 복구 키(C8-XXXX-XXXX-XXXX)를 입력하여 데이터를 직접 복원합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = recoveryKeyInput,
                        onValueChange = { recoveryKeyInput = it.trim().uppercase() },
                        placeholder = { Text("예: C8-7X9K-2M4Q-99PL", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(onClick = { useRecoveryKeyMode = false }) {
                            Text("6자리 페어링 코드로 입력하기", style = MaterialTheme.typography.labelSmall)
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
                                Toast.makeText(context, "기기 연결 및 데이터 가져오기 완료!", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } else {
                                isError = true
                                statusMessage = result.message ?: "인증 실패"
                            }
                        }
                    } else {
                        val key = recoveryKeyInput.trim()
                        if (key.isNotBlank()) {
                            viewModel.updateSyncKey(key)
                            viewModel.syncNow()
                            Toast.makeText(context, "마스터 복구 키가 설정되었습니다.", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } else {
                            isConnecting = false
                            isError = true
                            statusMessage = "마스터 복구 키를 입력해주세요."
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
                    Text("연결 중...")
                } else {
                    Text("데이터 가져오기")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("취소")
            }
        },
    )
}
