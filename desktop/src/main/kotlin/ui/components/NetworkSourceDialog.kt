package com.comics8.desktop.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.comics8.core.source.network.NetworkProtocol
import com.comics8.core.source.network.NetworkSourceConfig
import com.comics8.desktop.ui.theme.LocalStrings

@Composable
fun NetworkSourceDialog(
    draft: NetworkSourceConfig,
    testing: Boolean,
    testMessage: String?,
    testSucceeded: Boolean,
    onChange: (NetworkSourceConfig) -> Unit,
    onTest: () -> Unit,
    onRegister: () -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.protocol == NetworkProtocol.SMB) strings.titleAddSmb else strings.titleAddWebDav) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Field(strings.labelName, draft.name) { onChange(draft.copy(name = it)) }
                if (draft.protocol == NetworkProtocol.SMB) {
                    Field(strings.labelServerAddress, draft.host, strings.placeholderServerAddressExample) {
                        onChange(draft.copy(host = it))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = if (draft.port == 0) "" else draft.port.toString(),
                            onValueChange = { value ->
                                if (value.all(Char::isDigit)) onChange(draft.copy(port = value.toIntOrNull() ?: 0))
                            },
                            label = { Text(strings.labelPort) },
                            singleLine = true,
                            modifier = Modifier.weight(0.35f),
                        )
                        OutlinedTextField(
                            value = draft.share,
                            onValueChange = { onChange(draft.copy(share = it)) },
                            label = { Text(strings.labelShareName) },
                            singleLine = true,
                            modifier = Modifier.weight(0.65f),
                        )
                    }
                    Field(strings.labelFolderPathOptional, draft.path, "Comics/Manga") { onChange(draft.copy(path = it)) }
                    Field(strings.labelDomainOptional, draft.domain) { onChange(draft.copy(domain = it)) }
                } else {
                    Field(strings.labelWebDavUrl, draft.url, "https://example.com/dav/comics/") {
                        onChange(draft.copy(url = it))
                    }
                }
                Field(strings.labelUsernameOptional, draft.username) { onChange(draft.copy(username = it)) }
                OutlinedTextField(
                    value = draft.password,
                    onValueChange = { onChange(draft.copy(password = it)) },
                    label = { Text(strings.labelPasswordOptional) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (testing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator()
                        Text(strings.statusCheckingConnection)
                    }
                } else if (testMessage != null) {
                    Text(
                        text = testMessage,
                        color = if (testSucceeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onRegister, enabled = testSucceeded && !testing) { Text(strings.actionRegister) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onTest, enabled = !testing) { Text(strings.actionTestConnection) }
                TextButton(onClick = onDismiss) { Text(strings.actionCancel) }
            }
        },
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String = "",
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder.isBlank()) null else ({ Text(placeholder) }),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
