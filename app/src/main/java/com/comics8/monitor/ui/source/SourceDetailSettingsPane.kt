package com.comics8.monitor.ui.source

import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comics8.core.model.ProgressDisplayMode
import com.comics8.core.source.ComicSource
import com.comics8.core.source.WorkId
import com.comics8.core.source.network.NetworkLibrarySource
import com.comics8.core.source.network.NetworkProtocol
import com.comics8.core.source.network.NetworkSourceConfig
import com.comics8.monitor.ui.ListingUiState
import com.comics8.monitor.ui.ListingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceDetailSettingsPane(
    source: ComicSource,
    state: ListingUiState,
    viewModel: ListingViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isLocal = source.id == WorkId.LOCAL_SOURCE
    val netSource = source as? NetworkLibrarySource
    val netConfig = remember(source.id) { viewModel.getNetworkSourceConfig(source.id) }
    val isSmb = netConfig?.protocol == NetworkProtocol.SMB
    val isWebDav = netConfig?.protocol == NetworkProtocol.WEBDAV
    val isJs = source.id.startsWith("js:")

    var currentProgressMode by remember {
        mutableStateOf(viewModel.getSourceProgressDisplayMode(source.id))
    }

    // SMB 설정 상태
    var smbName by remember(netConfig) { mutableStateOf(netConfig?.name.orEmpty()) }
    var smbHost by remember(netConfig) { mutableStateOf(netConfig?.host.orEmpty()) }
    var smbPort by remember(netConfig) { mutableStateOf(netConfig?.port?.toString() ?: "445") }
    var smbShare by remember(netConfig) { mutableStateOf(netConfig?.share.orEmpty()) }
    var smbPath by remember(netConfig) { mutableStateOf(netConfig?.path.orEmpty()) }
    var smbUsername by remember(netConfig) { mutableStateOf(netConfig?.username.orEmpty()) }
    var smbPassword by remember(netConfig) { mutableStateOf(netConfig?.password.orEmpty()) }
    var smbDomain by remember(netConfig) { mutableStateOf(netConfig?.domain.orEmpty()) }
    var smbShowPassword by remember { mutableStateOf(false) }

    // WebDAV 설정 상태
    var davName by remember(netConfig) { mutableStateOf(netConfig?.name.orEmpty()) }
    var davUrl by remember(netConfig) { mutableStateOf(netConfig?.url.orEmpty()) }
    var davPath by remember(netConfig) { mutableStateOf(netConfig?.path.orEmpty()) }
    var davUsername by remember(netConfig) { mutableStateOf(netConfig?.username.orEmpty()) }
    var davPassword by remember(netConfig) { mutableStateOf(netConfig?.password.orEmpty()) }
    var davShowPassword by remember { mutableStateOf(false) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var folderToDelete by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "${source.displayName} 설정",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로가기",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                actions = {
                    if (netConfig != null && (isSmb || isWebDav)) {
                        Button(
                            onClick = {
                                isSaving = true
                                val newConfig = if (isSmb) {
                                    netConfig.copy(
                                        name = smbName.trim().ifBlank { "SMB" },
                                        host = smbHost.trim(),
                                        port = smbPort.toIntOrNull() ?: 445,
                                        share = smbShare.trim(),
                                        path = smbPath.trim(),
                                        username = smbUsername.trim(),
                                        password = smbPassword,
                                        domain = smbDomain.trim(),
                                    )
                                } else {
                                    netConfig.copy(
                                        name = davName.trim().ifBlank { "WebDAV" },
                                        url = davUrl.trim(),
                                        path = davPath.trim(),
                                        username = davUsername.trim(),
                                        password = davPassword,
                                    )
                                }
                                viewModel.updateNetworkSourceConfig(newConfig) { success, msg ->
                                    isSaving = false
                                    if (success) {
                                        Toast.makeText(context, "연결 설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, msg ?: "저장 실패", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("저장", fontWeight = FontWeight.Bold)
                        }
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
            // ==========================================
            // 1. 소스 기본 정보 및 진행도 표시 방식
            // ==========================================
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(
                        title = "기본 정보 & 진행도 표시",
                        icon = when {
                            isLocal -> Icons.Default.Folder
                            isSmb -> Icons.Default.Dns
                            isWebDav -> Icons.Default.Cloud
                            isJs -> Icons.Default.Description
                            else -> Icons.Default.Language
                        },
                    )

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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = source.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "고유 ID: ${source.id}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = when {
                                            isLocal -> "로컬 저장소"
                                            isSmb -> "SMB 네트워크 드라이브"
                                            isWebDav -> "WebDAV 클라우드"
                                            isJs -> "JS 확장 스크립트"
                                            else -> "내장 웹 소스"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                            Text(
                                text = "작품 목록 읽음 진행도 표시 방식",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            ProgressDisplayMode.entries.forEach { mode ->
                                val isChecked = mode == currentProgressMode
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            currentProgressMode = mode
                                            viewModel.setSourceProgressDisplayMode(source.id, mode)
                                        }
                                        .background(
                                            if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                            else Color.Transparent,
                                        )
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                ) {
                                    RadioButton(
                                        selected = isChecked,
                                        onClick = {
                                            currentProgressMode = mode
                                            viewModel.setSourceProgressDisplayMode(source.id, mode)
                                        },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = MaterialTheme.colorScheme.primary,
                                            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = mode.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = mode.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // 2. 소스별 세부 설정 (로컬 / SMB / WebDAV)
            // ==========================================
            if (isLocal) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle(title = "연결된 로컬 만화 폴더", icon = Icons.Default.FolderOpen)

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
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = "등록된 폴더 (${state.libraryRoots.size}개)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Button(
                                        onClick = { viewModel.requestAddLocalFolder() },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("폴더 추가", style = MaterialTheme.typography.labelSmall)
                                    }
                                }

                                if (state.libraryRoots.isEmpty()) {
                                    Text(
                                        text = "추가된 만화 폴더가 없습니다. 위 버튼을 눌러 만화가 있는 폴더를 선택하세요.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    state.libraryRoots.forEach { uriString ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f))
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.FolderOpen,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = viewModel.libraryRootLabel(uriString),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            IconButton(
                                                onClick = { folderToDelete = uriString },
                                                modifier = Modifier.size(28.dp),
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Delete,
                                                    contentDescription = "폴더 연결 해제",
                                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isSmb) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle(title = "SMB 연결 상세 설정", icon = Icons.Default.Dns)

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
                                OutlinedTextField(
                                    value = smbName,
                                    onValueChange = { smbName = it },
                                    label = { Text("표시 이름") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = smbHost,
                                        onValueChange = { smbHost = it },
                                        label = { Text("서버 IP / 호스트 주소") },
                                        placeholder = { Text("192.168.0.10 또는 nas.local") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = smbPort,
                                        onValueChange = { smbPort = it },
                                        label = { Text("포트") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.width(90.dp),
                                    )
                                }

                                OutlinedTextField(
                                    value = smbShare,
                                    onValueChange = { smbShare = it },
                                    label = { Text("공유 이름 (Share)") },
                                    placeholder = { Text("comics 또는 manga") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = smbPath,
                                    onValueChange = { smbPath = it },
                                    label = { Text("시작 경로 (선택)") },
                                    placeholder = { Text("선택 사항 (예: books/japanese)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = smbUsername,
                                        onValueChange = { smbUsername = it },
                                        label = { Text("사용자명 (선택)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedTextField(
                                        value = smbDomain,
                                        onValueChange = { smbDomain = it },
                                        label = { Text("도메인/워크그룹 (선택)") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }

                                OutlinedTextField(
                                    value = smbPassword,
                                    onValueChange = { smbPassword = it },
                                    label = { Text("비밀번호 (선택)") },
                                    singleLine = true,
                                    visualTransformation = if (smbShowPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { smbShowPassword = !smbShowPassword }) {
                                            Icon(
                                                imageVector = if (smbShowPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            if (isWebDav) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle(title = "WebDAV 연결 상세 설정", icon = Icons.Default.Cloud)

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
                                OutlinedTextField(
                                    value = davName,
                                    onValueChange = { davName = it },
                                    label = { Text("표시 이름") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = davUrl,
                                    onValueChange = { davUrl = it },
                                    label = { Text("WebDAV 서버 URL") },
                                    placeholder = { Text("https://nas.local:5006/dav") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = davPath,
                                    onValueChange = { davPath = it },
                                    label = { Text("시작 경로 (선택)") },
                                    placeholder = { Text("선택 사항 (예: comics)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = davUsername,
                                    onValueChange = { davUsername = it },
                                    label = { Text("사용자명 (선택)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = davPassword,
                                    onValueChange = { davPassword = it },
                                    label = { Text("비밀번호 (선택)") },
                                    singleLine = true,
                                    visualTransformation = if (davShowPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        IconButton(onClick = { davShowPassword = !davShowPassword }) {
                                            Icon(
                                                imageVector = if (davShowPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = null,
                                            )
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // 3. 위험 구역 (소스 삭제)
            // ==========================================
            if (!isLocal) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle(title = "소스 관리 및 삭제", icon = Icons.Default.Warning)

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                            border = androidx.compose.foundation.BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "소스 삭제",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        text = "목록에서 이 소스를 삭제하고 캐시 데이터를 정리합니다.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Button(
                                    onClick = { showDeleteConfirm = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError,
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("삭제", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 소스 삭제 확인 다이얼로그
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text("소스 삭제", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("'${source.displayName}' 소스를 목록에서 삭제하시겠습니까?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.requestRemoveSource(source)
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("삭제", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("취소")
                }
            },
        )
    }

    // 로컬 폴더 삭제 확인 다이얼로그
    if (folderToDelete != null) {
        val folderUri = folderToDelete!!
        val folderLabel = viewModel.libraryRootLabel(folderUri)
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = {
                Text("폴더 연결 해제", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("'$folderLabel' 폴더 연결을 해제하시겠습니까?\n(기기의 실제 파일은 삭제되지 않습니다)")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeLibraryRoot(folderUri)
                        folderToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("해제", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text("취소")
                }
            },
        )
    }
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
