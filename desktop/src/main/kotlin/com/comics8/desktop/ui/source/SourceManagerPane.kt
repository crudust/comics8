package com.comics8.desktop.ui.source

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.comics8.core.model.ProgressDisplayMode
import com.comics8.core.source.ComicSource
import com.comics8.core.source.WorkId
import com.comics8.desktop.data.DesktopSourcePrefs
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManagerPane(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
) {
    var configSourceId by remember { mutableStateOf<String?>(null) }
    var configSourceName by remember { mutableStateOf("") }
    var showAddOptions by remember { mutableStateOf(false) }
    var sourceToDelete by remember { mutableStateOf<ComicSource?>(null) }
    var folderToDelete by remember { mutableStateOf<String?>(null) }

    val sources = state.installedSources
    val networkSources = sources.filter {
        it.id.startsWith("smb:") || it.id.startsWith("webdav:")
    }
    val webAndJsSources = sources.filter {
        it.id != WorkId.LOCAL_SOURCE && it !in networkSources
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
            // 섹션 1: 로컬 저장소 및 등록된 폴더 관리
            item {
                SectionHeader(title = "로컬 저장소 (드라이브 / 폴더)")
            }
            item {
                val localMode = DesktopSourcePrefs.progressDisplayMode(WorkId.LOCAL_SOURCE)
                LocalSourceCard(
                    libraryRoots = state.libraryRoots,
                    modeLabel = localMode.label,
                    onOpenSettings = {
                        configSourceId = WorkId.LOCAL_SOURCE
                        configSourceName = "로컬 만화"
                    },
                    onAddFolder = { viewModel.addLibraryRoot() },
                    onDeleteFolder = { rootUri -> folderToDelete = rootUri },
                )
            }

            // 섹션 2: 네트워크 드라이브 (SMB / WebDAV)
            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader(title = "네트워크 드라이브 (SMB / WebDAV)")
            }
            if (networkSources.isEmpty()) {
                item {
                    EmptySectionCard(
                        text = "등록된 네트워크 드라이브가 없습니다.",
                        actionText = "SMB / WebDAV 추가",
                        onAction = { showAddOptions = true },
                    )
                }
            } else {
                items(networkSources, key = { it.id }) { source ->
                    val currentMode = DesktopSourcePrefs.progressDisplayMode(source.id)
                    SourceCard(
                        icon = Icons.Default.Cloud,
                        title = source.displayName,
                        subtitle = source.id,
                        modeLabel = currentMode.label,
                        canDelete = true,
                        onOpenSettings = {
                            configSourceId = source.id
                            configSourceName = source.displayName
                        },
                        onDelete = { sourceToDelete = source },
                    )
                }
            }

            // 섹션 3: 웹 및 확장 스크립트 소스
            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader(title = "웹 및 확장 스크립트 소스")
            }
            if (webAndJsSources.isEmpty()) {
                item {
                    EmptySectionCard(
                        text = "등록된 웹/확장 소스가 없습니다.",
                        actionText = "JS 확장 스크립트 추가",
                        onAction = { showAddOptions = true },
                    )
                }
            } else {
                items(webAndJsSources, key = { it.id }) { source ->
                    val isJs = source.id.startsWith("js:")
                    val icon = if (isJs) Icons.Default.Description else Icons.Default.Language
                    val subtitle = if (isJs) "JS 확장 스크립트" else "내장 웹 소스"
                    val currentMode = DesktopSourcePrefs.progressDisplayMode(source.id)

                    SourceCard(
                        icon = icon,
                        title = source.displayName,
                        subtitle = subtitle,
                        modeLabel = currentMode.label,
                        canDelete = isJs,
                        onOpenSettings = {
                            configSourceId = source.id
                            configSourceName = source.displayName
                        },
                        onDelete = { sourceToDelete = source },
                    )
                }
            }
        }
    }

    // 소스 세부 설정 다이얼로그 (진행도 표시 모드 설정)
    if (configSourceId != null) {
        val targetId = configSourceId!!
        val currentMode = DesktopSourcePrefs.progressDisplayMode(targetId)

        SourceConfigDialog(
            sourceName = configSourceName,
            currentMode = currentMode,
            onDismiss = { configSourceId = null },
            onSelectMode = { newMode ->
                viewModel.setSourceProgressDisplayMode(targetId, newMode)
                configSourceId = null
            },
        )
    }

    // 소스 삭제 확인 다이얼로그
    if (sourceToDelete != null) {
        val source = sourceToDelete!!
        AlertDialog(
            onDismissRequest = { sourceToDelete = null },
            title = {
                Text("소스 삭제", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("'${source.displayName}' 소스를 목록에서 삭제하시겠습니까?")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.requestRemoveSource(source)
                        sourceToDelete = null
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
                TextButton(onClick = { sourceToDelete = null }) {
                    Text("취소")
                }
            },
        )
    }

    // 로컬 폴더 삭제 확인 다이얼로그
    if (folderToDelete != null) {
        val folderPath = folderToDelete!!
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = {
                Text("폴더 연결 해제", fontWeight = FontWeight.Bold)
            },
            text = {
                Text("'$folderPath' 폴더 연결을 해제하시겠습니까?\n(로컬 실제 파일은 삭제되지 않습니다)")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeLibraryRoot(folderPath)
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

    // 새 소스 추가 선택 다이얼로그
    if (showAddOptions) {
        SourceAddOptionsDialog(
            onDismiss = { showAddOptions = false },
            onAddLocalFolder = {
                showAddOptions = false
                viewModel.addLibraryRoot()
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
                viewModel.importJs()
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
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
private fun LocalSourceCard(
    libraryRoots: List<String>,
    modeLabel: String,
    onOpenSettings: () -> Unit,
    onAddFolder: () -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 상단 헤더
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
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
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "로컬 만화 보관함",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (libraryRoots.isEmpty()) "등록된 폴더 없음" else "${libraryRoots.size}개 폴더 연결됨",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                IconButton(onClick = onAddFolder) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "폴더 추가",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "설정",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // 하위 등록된 폴더 목록
            if (libraryRoots.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                libraryRoots.forEach { path ->
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
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onDeleteFolder(path) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "폴더 삭제",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modeLabel: String,
    canDelete: Boolean,
    onOpenSettings: () -> Unit,
    onDelete: () -> Unit,
) {
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

            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "설정",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "삭제",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceConfigDialog(
    sourceName: String,
    currentMode: ProgressDisplayMode,
    onDismiss: () -> Unit,
    onSelectMode: (ProgressDisplayMode) -> Unit,
) {
    var selected by remember { mutableStateOf(currentMode) }

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
                    text = "소스 설정: $sourceName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "작품 목록에서 읽음 진행도를 표시할 방식을 선택하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                ProgressDisplayMode.entries.forEach { mode ->
                    val isChecked = mode == selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { selected = mode }
                            .background(
                                if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else Color.Transparent,
                            )
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        RadioButton(
                            selected = isChecked,
                            onClick = { selected = mode },
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

                Spacer(Modifier.height(20.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("취소", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSelectMode(selected) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("저장", fontWeight = FontWeight.Bold)
                    }
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

                AddOptionItem(
                    icon = Icons.Default.Folder,
                    title = "로컬 폴더",
                    description = "로컬 드라이브의 만화/ZIP 폴더 추가",
                    onClick = onAddLocalFolder,
                )
                AddOptionItem(
                    icon = Icons.Default.Cloud,
                    title = "SMB (네트워크 공유)",
                    description = "로컬 네트워크의 Windows 공유 / NAS 드라이브",
                    onClick = onAddSmb,
                )
                AddOptionItem(
                    icon = Icons.Default.Cloud,
                    title = "WebDAV (클라우드/NAS)",
                    description = "Synology, Nextcloud 등 WebDAV 스토리지",
                    onClick = onAddWebDav,
                )
                AddOptionItem(
                    icon = Icons.Default.Description,
                    title = "JS 파일 (확장 스크립트)",
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
            .padding(horizontal = 8.dp, vertical = 10.dp),
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
