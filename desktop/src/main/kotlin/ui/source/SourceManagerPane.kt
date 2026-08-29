package com.comics8.desktop.ui.source

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import kotlin.math.roundToInt
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
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import com.comics8.core.i18n.displayLabel
import com.comics8.core.i18n.displayTitle
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.comics8.core.source.SourceType
import com.comics8.core.source.WorkId
import com.comics8.core.source.network.NetworkLibrarySource
import com.comics8.core.source.resolveSourceType
import com.comics8.desktop.data.DesktopSourcePrefs
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.theme.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManagerPane(
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    var selectedSourceForDetail by remember { mutableStateOf<ComicSource?>(null) }
    var showAddOptions by remember { mutableStateOf(false) }

    // 소스 상세 설정 페이지가 열려있을 때
    if (selectedSourceForDetail != null) {
        val currentSource = state.installedSources.firstOrNull { it.id == selectedSourceForDetail?.id }
            ?: selectedSourceForDetail!!
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
    val storageSources = sources.filter { it.resolveSourceType().isStorage }
    val localSource = storageSources.firstOrNull { it.id == WorkId.LOCAL_SOURCE || it.resolveSourceType() == SourceType.LOCAL }
    val networkStorageSources = storageSources.filterNot { it == localSource }
    val onlineSources = sources.filterNot { it.resolveSourceType().isStorage }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.navSourceManager,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeSourceManager) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.actionGoBack,
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
                            text = strings.actionAddSource,
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // ==========================================
            // 분류 1: 로컬 / SMB / WebDAV
            // ==========================================
            item {
                SectionHeader(title = strings.sectionStorageSources, icon = Icons.Default.Storage)
            }

            // 최상단 고정 로컬 저장소
            if (localSource != null) {
                item(key = localSource.id) {
                    val subtitle = if (state.libraryRoots.isEmpty()) strings.noRegisteredFolders else strings.connectedFolderCount(state.libraryRoots.size)

                    SourceItemCard(
                        icon = Icons.Default.Folder,
                        title = localSource.displayTitle(strings),
                        subtitle = subtitle,
                        onClick = { selectedSourceForDetail = localSource },
                    )
                }
            }

            // SMB / WebDAV 저장소 목록 (드래그 정렬 가능)
            if (networkStorageSources.isNotEmpty()) {
                item {
                    ReorderableItemColumn(
                        items = networkStorageSources,
                        key = { it.id },
                        onReorder = viewModel::reorderStorageSources,
                    ) { source, dragModifier, isDragging ->
                        val sType = source.resolveSourceType()
                        val icon = if (sType == SourceType.SMB) Icons.Default.Dns else Icons.Default.Cloud
                        val subtitle = when (sType) {
                            SourceType.SMB -> {
                                val netSource = source as? NetworkLibrarySource
                                if (netSource != null) strings.subtitleSmbPath(netSource.config.host, netSource.config.share) else strings.subtitleSmbDefault
                            }
                            SourceType.WEBDAV -> {
                                val netSource = source as? NetworkLibrarySource
                                if (netSource != null) strings.subtitleWebDavUrl(netSource.config.url.ifBlank { netSource.config.host }) else strings.subtitleWebDavDefault
                            }
                            else -> sType.displayLabel(strings)
                        }

                        SourceItemCard(
                            icon = icon,
                            title = source.displayTitle(strings),
                            subtitle = subtitle,
                            onClick = { selectedSourceForDetail = source },
                            dragHandleModifier = dragModifier,
                            isDragging = isDragging,
                        )
                    }
                }
            }

            // ==========================================
            // 분류 2: 웹 / JS 스크립트
            // ==========================================
            item {
                Spacer(Modifier.height(4.dp))
                SectionHeader(title = strings.sectionOnlineSources, icon = Icons.Default.Public)
            }
            if (onlineSources.isEmpty()) {
                item {
                    EmptySectionCard(
                        text = strings.emptyOnlineSources,
                        actionText = strings.actionAddJsSource,
                        onAction = {
                            showAddOptions = false
                            viewModel.openAddSourceSheet()
                        },
                    )
                }
            } else {
                item {
                    ReorderableItemColumn(
                        items = onlineSources,
                        key = { it.id },
                        onReorder = viewModel::reorderOnlineSources,
                    ) { source, dragModifier, isDragging ->
                        val sType = source.resolveSourceType()
                        val icon = if (sType == SourceType.JS) Icons.Default.Description else Icons.Default.Language
                        val subtitle = sType.displayLabel(strings)

                        SourceItemCard(
                            icon = icon,
                            title = source.displayTitle(strings),
                            subtitle = subtitle,
                            onClick = { selectedSourceForDetail = source },
                            dragHandleModifier = dragModifier,
                            isDragging = isDragging,
                        )
                    }
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
private fun <T : Any> ReorderableItemColumn(
    items: List<T>,
    key: (T) -> Any,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 10.dp,
    itemContent: @Composable (item: T, dragHandleModifier: Modifier, isDragging: Boolean) -> Unit,
) {
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var itemHeightPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val spacingPx = with(density) { spacing.toPx() }

    val currentDragged = draggedIndex
    val totalItemHeight = if (itemHeightPx > 0f) itemHeightPx + spacingPx else with(density) { 68.dp.toPx() }

    val targetIndex = if (currentDragged != null && totalItemHeight > 0f) {
        (currentDragged + (dragOffsetY / totalItemHeight).roundToInt()).coerceIn(0, items.lastIndex)
    } else {
        currentDragged
    }

    val latestOnReorder by rememberUpdatedState(onReorder)

    Column(
        verticalArrangement = Arrangement.spacedBy(spacing),
        modifier = modifier.fillMaxWidth(),
    ) {
        items.forEachIndexed { index, item ->
            val isDragging = index == currentDragged

            val visualOffset by animateFloatAsState(
                targetValue = when {
                    currentDragged == null -> 0f
                    isDragging -> dragOffsetY
                    targetIndex != null && currentDragged < targetIndex && index in (currentDragged + 1)..targetIndex -> -totalItemHeight
                    targetIndex != null && currentDragged > targetIndex && index in targetIndex until currentDragged -> totalItemHeight
                    else -> 0f
                },
                animationSpec = if (isDragging) spring(stiffness = Spring.StiffnessHigh) else spring(stiffness = Spring.StiffnessMediumLow),
                label = "dnd_offset_$index",
            )

            val zIndex = if (isDragging) 10f else 1f

            val dragHandleModifier = Modifier.pointerInput(key(item)) {
                detectDragGestures(
                    onDragStart = {
                        draggedIndex = index
                        dragOffsetY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetY += dragAmount.y
                    },
                    onDragEnd = {
                        val from = draggedIndex
                        val offset = dragOffsetY
                        val height = if (itemHeightPx > 0f) itemHeightPx + spacingPx else (68f * density.density)
                        draggedIndex = null
                        dragOffsetY = 0f
                        if (from != null && height > 0f) {
                            val delta = (offset / height).roundToInt()
                            val to = (from + delta).coerceIn(0, items.lastIndex)
                            if (from != to) {
                                latestOnReorder(from, to)
                            }
                        }
                    },
                    onDragCancel = {
                        draggedIndex = null
                        dragOffsetY = 0f
                    },
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(zIndex)
                    .graphicsLayer {
                        translationY = visualOffset
                        if (isDragging) {
                            shadowElevation = 8.dp.toPx()
                            shape = RoundedCornerShape(12.dp)
                            clip = false
                        }
                    }
                    .onGloballyPositioned { coords ->
                        if (!isDragging && coords.size.height > 0) {
                            itemHeightPx = coords.size.height.toFloat()
                        }
                    },
            ) {
                itemContent(item, dragHandleModifier, isDragging)
            }
        }
    }
}

@Composable
private fun SourceItemCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    dragHandleModifier: Modifier? = null,
    isDragging: Boolean = false,
) {
    val strings = LocalStrings.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isDragging) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f) else MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isDragging) 1.5.dp else 1.dp,
            color = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
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
            // 드래그 핸들을 가장 왼쪽에 항상 배치 (드래그 불가 시 비활성화 스타일 적용)
            val handleBoxModifier = if (dragHandleModifier != null) {
                Modifier
                    .then(dragHandleModifier)
                    .clip(RoundedCornerShape(6.dp))
                    .padding(end = 8.dp, top = 4.dp, bottom = 4.dp)
            } else {
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .padding(end = 8.dp, top = 4.dp, bottom = 4.dp)
            }

            Box(
                modifier = handleBoxModifier,
                contentAlignment = Alignment.Center,
            ) {
                val handleTint = when {
                    dragHandleModifier == null -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    isDragging -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = if (dragHandleModifier == null) strings.labelFixed else strings.actionReorder,
                    tint = handleTint,
                    modifier = Modifier.size(20.dp),
                )
            }

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
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = strings.actionGoToSettings,
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
    val strings = LocalStrings.current
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
                    text = strings.titleAddSource,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = strings.promptSelectSourceType,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                // 카테고리 1: 파일 저장소
                Text(
                    text = strings.categoryFileStorage,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                AddOptionItem(
                    icon = Icons.Default.Folder,
                    title = strings.sourceLocalFolder,
                    description = strings.sourceLocalFolderDesc,
                    onClick = onAddLocalFolder,
                )
                AddOptionItem(
                    icon = Icons.Default.Dns,
                    title = strings.sourceSmb,
                    description = strings.sourceSmbDesc,
                    onClick = onAddSmb,
                )
                AddOptionItem(
                    icon = Icons.Default.Cloud,
                    title = strings.sourceWebDav,
                    description = strings.sourceWebDavDesc,
                    onClick = onAddWebDav,
                )

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(Modifier.height(8.dp))

                // 카테고리 2: 온라인 & 확장 스크립트
                Text(
                    text = strings.categoryOnlineExtensions,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                AddOptionItem(
                    icon = Icons.Default.Description,
                    title = strings.sourceJsExtension,
                    description = strings.sourceJsExtensionDesc,
                    onClick = onAddJs,
                )

                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(strings.actionClose, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
