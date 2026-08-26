package com.comics8.desktop.ui.source

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Code
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comics8.core.i18n.AppStrings
import com.comics8.core.i18n.displayLabel
import com.comics8.core.i18n.displayTitle
import com.comics8.core.model.ProgressDisplayMode
import com.comics8.core.source.ComicSource
import com.comics8.core.source.SourceType
import com.comics8.core.source.resolveSourceType
import com.comics8.desktop.data.DesktopSourcePrefs
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.DesktopViewModel
import com.comics8.desktop.ui.theme.LocalStrings

private fun ProgressDisplayMode.label(strings: AppStrings): String = when (this) {
    ProgressDisplayMode.LATEST_EPISODE -> strings.progressModeLatestEpisode
    ProgressDisplayMode.READ_COUNT -> strings.progressModeReadCount
    ProgressDisplayMode.PERCENTAGE -> strings.progressModePercentage
    ProgressDisplayMode.HIDDEN -> strings.progressModeHidden
}

private fun ProgressDisplayMode.description(strings: AppStrings): String = when (this) {
    ProgressDisplayMode.LATEST_EPISODE -> strings.progressModeLatestEpisodeDesc
    ProgressDisplayMode.READ_COUNT -> strings.progressModeReadCountDesc
    ProgressDisplayMode.PERCENTAGE -> strings.progressModePercentageDesc
    ProgressDisplayMode.HIDDEN -> strings.progressModeHiddenDesc
}

private fun SourceType.label(strings: AppStrings): String = when (this) {
    SourceType.LOCAL -> strings.labelSourceLocal
    SourceType.SMB -> strings.labelSourceSmb
    SourceType.WEBDAV -> strings.labelSourceWebDav
    SourceType.JS -> strings.labelSourceJs
    SourceType.WEB -> strings.labelSourceWeb
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceDetailSettingsPane(
    source: ComicSource,
    state: DesktopUiState,
    viewModel: DesktopViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val sourceType = remember(source.id) { source.resolveSourceType() }
    val netConfig = remember(source.id) {
        if (sourceType == SourceType.SMB || sourceType == SourceType.WEBDAV) {
            viewModel.getNetworkSourceConfig(source.id)
        } else null
    }

    var currentProgressMode by remember {
        mutableStateOf(DesktopSourcePrefs.progressDisplayMode(source.id))
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

    // JS 스크립트 편집 상태
    var jsScriptCode by remember(source.id) {
        mutableStateOf(if (sourceType == SourceType.JS) viewModel.getJsSourceScript(source.id).orEmpty() else "")
    }

    LaunchedEffect(source.id, sourceType) {
        if (sourceType == SourceType.JS) {
            val loaded = viewModel.getJsSourceScript(source.id)
            if (loaded != null && jsScriptCode.isEmpty()) {
                jsScriptCode = loaded
            }
        }
    }

    var folderToDelete by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    fun saveSmb() {
        if (netConfig == null) return
        isSaving = true
        val newConfig = netConfig.copy(
            name = smbName.trim().ifBlank { "SMB" },
            host = smbHost.trim(),
            port = smbPort.toIntOrNull() ?: 445,
            share = smbShare.trim(),
            path = smbPath.trim(),
            username = smbUsername.trim(),
            password = smbPassword,
            domain = smbDomain.trim(),
        )
        viewModel.updateNetworkSourceConfig(newConfig) { success, msg ->
            isSaving = false
            saveMessage = if (success) strings.msgSourceSaved else msg ?: strings.msgSaveFailed
        }
    }

    fun saveWebDav() {
        if (netConfig == null) return
        isSaving = true
        val newConfig = netConfig.copy(
            name = davName.trim().ifBlank { "WebDAV" },
            url = davUrl.trim(),
            path = davPath.trim(),
            username = davUsername.trim(),
            password = davPassword,
        )
        viewModel.updateNetworkSourceConfig(newConfig) { success, msg ->
            isSaving = false
            saveMessage = if (success) strings.msgSourceSaved else msg ?: strings.msgSaveFailed
        }
    }

    fun saveJs() {
        isSaving = true
        viewModel.updateJsSourceScript(source.id, jsScriptCode) { success, msg ->
            isSaving = false
            saveMessage = if (success) strings.msgSourceSaved else msg ?: strings.msgSaveFailed
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = strings.titleSourceSettings(source.displayTitle(strings)),
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (saveMessage != null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = saveMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            // ==========================================
            // 1. 소스 기본 정보 및 진행도 표시 방식
            // ==========================================
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(
                        title = strings.sectionSourceInfoAndProgress,
                        icon = when (sourceType) {
                            SourceType.LOCAL -> Icons.Default.Folder
                            SourceType.SMB -> Icons.Default.Dns
                            SourceType.WEBDAV -> Icons.Default.Cloud
                            SourceType.JS -> Icons.Default.Description
                            SourceType.WEB -> Icons.Default.Language
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
                                Text(
                                    text = source.displayTitle(strings),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = sourceType.displayLabel(strings),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                            Text(
                                text = strings.labelProgressDisplayMode,
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
                                            text = mode.label(strings),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = mode.description(strings),
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
            // 2. 소스별 세부 설정 (로컬 / SMB / WebDAV / JS)
            // ==========================================
            when (sourceType) {
                SourceType.LOCAL -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle(title = strings.sectionConnectedLocalFolders, icon = Icons.Default.FolderOpen)

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
                                            text = strings.registeredFolderCount(state.libraryRoots.size),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Button(
                                            onClick = { viewModel.addLibraryRoot() },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(strings.actionAddFolder, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }

                                    if (state.libraryRoots.isEmpty()) {
                                        Text(
                                            text = strings.hintNoLocalFolders,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        state.libraryRoots.forEach { path ->
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
                                                    onClick = { folderToDelete = path },
                                                    modifier = Modifier.size(28.dp),
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Delete,
                                                        contentDescription = strings.titleDisconnectFolder,
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

                SourceType.SMB -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle(title = strings.titleSmbDetailSettings, icon = Icons.Default.Dns)

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
                                        label = { Text(strings.labelDisplayName) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedTextField(
                                            value = smbHost,
                                            onValueChange = { smbHost = it },
                                            label = { Text(strings.labelServerIpHost) },
                                            placeholder = { Text(strings.placeholderServerAddressExample) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                        OutlinedTextField(
                                            value = smbPort,
                                            onValueChange = { smbPort = it },
                                            label = { Text(strings.labelPort) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier.width(90.dp),
                                        )
                                    }

                                    OutlinedTextField(
                                        value = smbShare,
                                        onValueChange = { smbShare = it },
                                        label = { Text(strings.labelShareNameWithAlias) },
                                        placeholder = { Text(strings.placeholderShareExample) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    OutlinedTextField(
                                        value = smbPath,
                                        onValueChange = { smbPath = it },
                                        label = { Text(strings.labelStartPathOptional) },
                                        placeholder = { Text(strings.placeholderBooksExample) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedTextField(
                                            value = smbUsername,
                                            onValueChange = { smbUsername = it },
                                            label = { Text(strings.labelUsernameShortOptional) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                        OutlinedTextField(
                                            value = smbDomain,
                                            onValueChange = { smbDomain = it },
                                            label = { Text(strings.labelDomainWorkgroupOptional) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }

                                    OutlinedTextField(
                                        value = smbPassword,
                                        onValueChange = { smbPassword = it },
                                        label = { Text(strings.labelPasswordFullOptional) },
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

                                    Spacer(Modifier.height(4.dp))
                                    Button(
                                        onClick = ::saveSmb,
                                        enabled = !isSaving,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(strings.actionSaveSmbConfig, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                SourceType.WEBDAV -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle(title = strings.titleWebDavDetailSettings, icon = Icons.Default.Cloud)

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
                                        label = { Text(strings.labelDisplayName) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    OutlinedTextField(
                                        value = davUrl,
                                        onValueChange = { davUrl = it },
                                        label = { Text(strings.labelWebDavServerUrl) },
                                        placeholder = { Text("https://nas.local:5006/dav") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    OutlinedTextField(
                                        value = davPath,
                                        onValueChange = { davPath = it },
                                        label = { Text(strings.labelStartPathOptional) },
                                        placeholder = { Text(strings.placeholderComicsExample) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    OutlinedTextField(
                                        value = davUsername,
                                        onValueChange = { davUsername = it },
                                        label = { Text(strings.labelUsernameShortOptional) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    OutlinedTextField(
                                        value = davPassword,
                                        onValueChange = { davPassword = it },
                                        label = { Text(strings.labelPasswordFullOptional) },
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

                                    Spacer(Modifier.height(4.dp))
                                    Button(
                                        onClick = ::saveWebDav,
                                        enabled = !isSaving,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(strings.actionSaveWebDavConfig, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                SourceType.JS -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle(title = strings.titleEditJsScript, icon = Icons.Default.Code)

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
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    OutlinedTextField(
                                        value = jsScriptCode,
                                        onValueChange = { jsScriptCode = it },
                                        label = { Text("JavaScript Source Code") },
                                        textStyle = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                        ),
                                        minLines = 15,
                                        maxLines = 35,
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    Button(
                                        onClick = ::saveJs,
                                        enabled = !isSaving,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(strings.actionSaveJsScript, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                SourceType.WEB -> {
                    // 내장 웹 크롤러 소스는 읽음 진행도 표시 설정만 제공
                }
            }

            // ==========================================
            // 3. 위험 구역 (소스 삭제)
            // ==========================================
            if (sourceType != SourceType.LOCAL) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle(title = strings.sectionSourceManagementAndDelete, icon = Icons.Default.Warning)

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
                                        text = strings.titleDeleteSource,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        text = strings.descDeleteSource,
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
                                    Text(strings.actionDelete, fontWeight = FontWeight.Bold)
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
                Text(strings.titleDeleteSource, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(strings.confirmDeleteSource(source.displayName))
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
                    Text(strings.actionDelete, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(strings.actionCancel)
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
                Text(strings.titleDisconnectFolder, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(strings.confirmDisconnectFolder(folderPath))
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
                    Text(strings.actionDisconnect, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { folderToDelete = null }) {
                    Text(strings.actionCancel)
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
