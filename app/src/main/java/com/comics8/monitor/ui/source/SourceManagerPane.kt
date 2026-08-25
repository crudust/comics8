package com.comics8.monitor.ui.source

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.comics8.core.source.ComicSource
import com.comics8.core.source.WorkId
import com.comics8.core.source.network.NetworkLibrarySource
import com.comics8.core.source.network.NetworkProtocol
import com.comics8.monitor.ui.ListingUiState
import com.comics8.monitor.ui.ListingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManagerPane(
    state: ListingUiState,
    viewModel: ListingViewModel,
    modifier: Modifier = Modifier,
) {
    var selectedSourceForDetail by remember { mutableStateOf<ComicSource?>(null) }
    var showAddOptions by remember { mutableStateOf(false) }

    // 소스 상세 설정 페이지가 열려있을 때의 뒤로가기 처리
    if (selectedSourceForDetail != null) {
        val currentSource = state.installedSources.firstOrNull { it.id == selectedSourceForDetail?.id }
            ?: selectedSourceForDetail!!
        BackHandler {
            selectedSourceForDetail = null
        }
        SourceDetailSettingsPane(
            source = currentSource,
            state = state,
            viewModel = viewModel,
            onBack = { selectedSourceForDetail = null },
            modifier = modifier,
        )
        return
    }

    val sources = state.installedSources
    val networkStorageSources = sources.filter {
        it is NetworkLibrarySource || it.id.startsWith("network-")
    }
    val onlineAndExtensionSources = sources.filter {
        it.id != WorkId.LOCAL_SOURCE && it !in networkStorageSources
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "소스 관리",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeSourceManager) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로가기",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { showAddOptions = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "소스 추가",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // ==========================================
            // 분류 1: 파일 저장소 (로컬 & 네트워크 SMB/WebDAV)
            // ==========================================
            item {
                SectionHeader(title = "파일 저장소 (로컬 / SMB / WebDAV)", icon = Icons.Default.Storage)
            }
            // 1-1. 로컬 저장소 카드
            val localSource = sources.firstOrNull { it.id == WorkId.LOCAL_SOURCE }
            if (localSource != null) {
                item {
                    val localMode = viewModel.getSourceProgressDisplayMode(WorkId.LOCAL_SOURCE)
                    SourceItemCard(
                        icon = Icons.Default.Folder,
                        title = "로컬 만화 보관함",
                        subtitle = if (state.libraryRoots.isEmpty()) "등록된 만화 폴더 없음" else "${state.libraryRoots.size}개 폴더 연결됨",
                        modeLabel = localMode.label,
                        onClick = { selectedSourceForDetail = localSource },
                    )
                }
            }
            // 1-2. 네트워크 드라이브 목록
            if (networkStorageSources.isNotEmpty()) {
                items(networkStorageSources, key = { it.id }) { source ->
                    val netSource = source as? NetworkLibrarySource
                    val isSmb = netSource?.config?.protocol == NetworkProtocol.SMB
                    val subtitle = if (netSource != null) {
                        if (isSmb) "SMB: ${netSource.config.host}/${netSource.config.share}"
                        else "WebDAV: ${netSource.config.url.ifBlank { netSource.config.host }}"
                    } else {
                        "네트워크 드라이브"
                    }
                    val currentMode = viewModel.getSourceProgressDisplayMode(source.id)

                    SourceItemCard(
                        icon = if (isSmb) Icons.Default.Dns else Icons.Default.Cloud,
                        title = source.displayName,
                        subtitle = subtitle,
                        modeLabel = currentMode.label,
                        onClick = { selectedSourceForDetail = source },
                    )
                }
            }

            // ==========================================
            // 분류 2: 온라인 & 확장 스크립트 (웹 / JS 확장)
            // ==========================================
            item {
                Spacer(Modifier.height(8.dp))
                SectionHeader(title = "온라인 & 확장 소스 (웹 / JS 스크립트)", icon = Icons.Default.Public)
            }
            if (onlineAndExtensionSources.isEmpty()) {
                item {
                    EmptySectionCard(
                        text = "등록된 온라인/확장 소스가 없습니다.",
                        actionText = "JS 확장 스크립트 추가",
                        onAction = {
                            showAddOptions = false
                            viewModel.openAddSourceSheet()
                        },
                    )
                }
            } else {
                items(onlineAndExtensionSources, key = { it.id }) { source ->
                    val isJs = source is com.comics8.core.source.js.JsComicSource || viewModel.getJsSourceScript(source.id) != null
                    val icon = if (isJs) Icons.Default.Description else Icons.Default.Language
                    val subtitle = if (isJs) "JS 확장 스크립트 소스" else "내장 웹 크롤러 소스"
                    val currentMode = viewModel.getSourceProgressDisplayMode(source.id)

                    SourceItemCard(
                        icon = icon,
                        title = source.displayName,
                        subtitle = subtitle,
                        modeLabel = currentMode.label,
                        onClick = { selectedSourceForDetail = source },
                    )
                }
            }
        }
    }

    // 새 소스 추가 선택 다이얼로그
    if (showAddOptions) {
        SourceAddOptionsDialog(
            onDismiss = { showAddOptions = false },
            onAddLocalFolder = {
                showAddOptions = false
                viewModel.requestAddLocalFolder()
            },
            onAddSmb = {
                showAddOptions = false
                viewModel.openAddSmb()
            },
            onAddWebDav = {
                showAddOptions = false
                viewModel.openAddWebDav()
            },
            onAddJs = {
                showAddOptions = false
                viewModel.openAddSourceSheet()
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
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
private fun SourceItemCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modeLabel: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f))
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = modeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = "설정 이동",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun EmptySectionCard(
    text: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionText != null && onAction != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onAction,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(actionText, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SourceAddOptionsDialog(
    onDismiss: () -> Unit,
    onAddLocalFolder: () -> Unit,
    onAddSmb: () -> Unit,
    onAddWebDav: () -> Unit,
    onAddJs: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "새 소스 추가",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "추가할 저장소 또는 확장 소스 유형을 선택하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                // 카테고리 1: 파일 저장소
                Text(
                    text = "파일 저장소 (스토리지)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                AddOptionItem(
                    icon = Icons.Default.Folder,
                    title = "로컬 폴더",
                    description = "기기 내부/SD카드의 만화 및 ZIP 폴더 추가",
                    onClick = onAddLocalFolder,
                )
                AddOptionItem(
                    icon = Icons.Default.Dns,
                    title = "SMB (네트워크 드라이브)",
                    description = "로컬 네트워크의 Windows 공유 / NAS 폴더 연결",
                    onClick = onAddSmb,
                )
                AddOptionItem(
                    icon = Icons.Default.Cloud,
                    title = "WebDAV (클라우드 드라이브)",
                    description = "Synology, Nextcloud 등 WebDAV 스토리지 연결",
                    onClick = onAddWebDav,
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))

                // 카테고리 2: 온라인 & 확장 스크립트
                Text(
                    text = "온라인 & 확장 스크립트",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                AddOptionItem(
                    icon = Icons.Default.Description,
                    title = "JS 확장 스크립트 (.js)",
                    description = "외부 사이트 파싱 확장 소스 파일 가져오기",
                    onClick = onAddJs,
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("닫기", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddOptionItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
