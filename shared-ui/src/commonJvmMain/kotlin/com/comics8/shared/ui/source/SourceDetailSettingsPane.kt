package com.comics8.shared.ui.source

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comics8.core.app.AppUiState
import com.comics8.core.app.SharedAppController
import com.comics8.core.i18n.displayDescription
import com.comics8.core.i18n.displayLabel
import com.comics8.core.i18n.displayTitle
import com.comics8.core.model.EpisodeSortOrder
import com.comics8.core.model.ProgressDisplayMode
import com.comics8.core.source.ComicSource
import com.comics8.core.source.SourceType
import com.comics8.core.source.resolveSourceType
import com.comics8.shared.ui.LocalPlatformUiBridge
import com.comics8.shared.ui.settings.SectionTitle
import com.comics8.shared.ui.theme.LocalStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceDetailSettingsPane(
    source: ComicSource,
    state: AppUiState,
    controller: SharedAppController,
    onBack: () -> Unit,
    onPickLocalFolder: () -> Unit,
    onRemoveLocalFolder: (String) -> Unit,
    libraryRootLabel: (String) -> String = { it },
    showNotificationToggle: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val bridge = LocalPlatformUiBridge.current
    val sourceType = remember(source.id) { source.resolveSourceType() }
    val netConfig = remember(source.id) {
        if (sourceType == SourceType.SMB || sourceType == SourceType.WEBDAV) {
            controller.getNetworkSourceConfig(source.id)
        } else {
            null
        }
    }

    var currentProgressMode by remember {
        mutableStateOf(controller.getSourceProgressDisplayMode(source.id))
    }

    var currentSortOrder by remember {
        mutableStateOf(controller.getSourceEpisodeSortOrder(source.id))
    }

    var smbName by remember(netConfig) { mutableStateOf(netConfig?.name.orEmpty()) }
    var smbHost by remember(netConfig) { mutableStateOf(netConfig?.host.orEmpty()) }
    var smbPort by remember(netConfig) { mutableStateOf(netConfig?.port?.toString() ?: "445") }
    var smbShare by remember(netConfig) { mutableStateOf(netConfig?.share.orEmpty()) }
    var smbPath by remember(netConfig) { mutableStateOf(netConfig?.path.orEmpty()) }
    var smbUsername by remember(netConfig) { mutableStateOf(netConfig?.username.orEmpty()) }
    var smbPassword by remember(netConfig) { mutableStateOf(netConfig?.password.orEmpty()) }
    var smbDomain by remember(netConfig) { mutableStateOf(netConfig?.domain.orEmpty()) }
    var smbShowPassword by remember { mutableStateOf(false) }

    var davName by remember(netConfig) { mutableStateOf(netConfig?.name.orEmpty()) }
    var davUrl by remember(netConfig) { mutableStateOf(netConfig?.url.orEmpty()) }
    var davPath by remember(netConfig) { mutableStateOf(netConfig?.path.orEmpty()) }
    var davUsername by remember(netConfig) { mutableStateOf(netConfig?.username.orEmpty()) }
    var davPassword by remember(netConfig) { mutableStateOf(netConfig?.password.orEmpty()) }
    var davShowPassword by remember { mutableStateOf(false) }

    var jsScriptCode by remember(source.id) {
        mutableStateOf(if (sourceType == SourceType.JS) controller.getJsSourceScript(source.id).orEmpty() else "")
    }

    LaunchedEffect(source.id, sourceType) {
        if (sourceType == SourceType.JS) {
            val loaded = controller.getJsSourceScript(source.id)
            if (loaded != null && jsScriptCode.isEmpty()) {
                jsScriptCode = loaded
            }
        }
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var folderToDelete by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    fun handleSaveResult(success: Boolean, msg: String?) {
        isSaving = false
        val feedback = if (success) strings.msgSourceSaved else msg ?: strings.msgErrorOccurred
        saveMessage = feedback
        bridge.showToast(feedback)
    }

    fun saveSmb() {
        if (netConfig == null) return
        isSaving = true
        val newConfig = netConfig.copy(
            name = smbName.trim().ifBlank { strings.defaultSmbName },
            host = smbHost.trim(),
            port = smbPort.toIntOrNull() ?: 445,
            share = smbShare.trim(),
            path = smbPath.trim(),
            username = smbUsername.trim(),
            password = smbPassword,
            domain = smbDomain.trim(),
        )
        controller.updateNetworkSourceConfig(newConfig, ::handleSaveResult)
    }

    fun saveWebDav() {
        if (netConfig == null) return
        isSaving = true
        val newConfig = netConfig.copy(
            name = davName.trim().ifBlank { strings.defaultWebDavName },
            url = davUrl.trim(),
            path = davPath.trim(),
            username = davUsername.trim(),
            password = davPassword,
        )
        controller.updateNetworkSourceConfig(newConfig, ::handleSaveResult)
    }

    fun saveJs() {
        isSaving = true
        controller.updateJsSourceScript(source.id, jsScriptCode, ::handleSaveResult)
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            val currentSaveMsg = saveMessage
            if (currentSaveMsg != null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f))
                            .padding(12.dp),
                    ) {
                        Text(
                            text = currentSaveMsg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(
                        title = strings.sectionSourceInfoAndProgress,
                        icon = when (sourceType) {
                            SourceType.LOCAL -> Icons.Default.Folder
                            SourceType.SMB -> Icons.Default.Dns
                            SourceType.WEBDAV -> Icons.Default.Cloud
                            SourceType.JS -> Icons.Default.Description
                        },
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
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
                                val modeLabel = mode.displayLabel(strings)
                                val modeDesc = mode.displayDescription(strings)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            currentProgressMode = mode
                                            controller.setSourceProgressDisplayMode(source.id, mode)
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
                                            controller.setSourceProgressDisplayMode(source.id, mode)
                                        },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = MaterialTheme.colorScheme.primary,
                                            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = modeLabel,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = modeDesc,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                            Text(
                                text = strings.labelEpisodeSortOrder,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            EpisodeSortOrder.entries.forEach { order ->
                                val isChecked = order == currentSortOrder
                                val orderLabel = order.displayLabel(strings)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            currentSortOrder = order
                                            controller.setSourceEpisodeSortOrder(source.id, order)
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
                                            currentSortOrder = order
                                            controller.setSourceEpisodeSortOrder(source.id, order)
                                        },
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = MaterialTheme.colorScheme.primary,
                                            unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        ),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = orderLabel,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }

                            if (showNotificationToggle) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

                                var notificationEnabled by remember(source.id) {
                                    mutableStateOf(controller.getSourceNotificationEnabled(source.id))
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = strings.labelSourceUpdateNotification,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = strings.descSourceUpdateNotification,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Switch(
                                        checked = notificationEnabled,
                                        onCheckedChange = { checked ->
                                            notificationEnabled = checked
                                            controller.toggleSourceNotification(source.id, checked)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            when (sourceType) {
                SourceType.LOCAL -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SectionTitle(title = strings.sectionConnectedLocalFolders, icon = Icons.Default.FolderOpen)

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(
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
                                            onClick = onPickLocalFolder,
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
                                        state.libraryRoots.forEach { rootPathOrUri ->
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
                                                    text = libraryRootLabel(rootPathOrUri),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f),
                                                )
                                                IconButton(
                                                    onClick = { folderToDelete = rootPathOrUri },
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
                                border = BorderStroke(
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
                                            label = { Text(strings.labelUsernameOptional) },
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
                                        label = { Text(strings.labelPasswordOptional) },
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
                                border = BorderStroke(
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
                                        label = { Text(strings.labelUsernameOptional) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )

                                    OutlinedTextField(
                                        value = davPassword,
                                        onValueChange = { davPassword = it },
                                        label = { Text(strings.labelPasswordOptional) },
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
                                border = BorderStroke(
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
            }

            if (sourceType != SourceType.LOCAL) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle(title = strings.sectionSourceManagementAndDelete, icon = Icons.Default.Warning)

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                            border = BorderStroke(
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
                        controller.requestRemoveSource(source)
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

    val pendingDeleteFolder = folderToDelete
    if (pendingDeleteFolder != null) {
        val folderLabel = libraryRootLabel(pendingDeleteFolder)
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = {
                Text(strings.titleDisconnectFolder, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(strings.confirmDisconnectFolder(folderLabel))
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRemoveLocalFolder(pendingDeleteFolder)
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
