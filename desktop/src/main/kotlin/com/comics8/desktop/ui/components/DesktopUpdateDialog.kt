package com.comics8.desktop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comics8.core.model.AppUpdateState
import com.comics8.desktop.ui.theme.LocalStrings

@Composable
fun DesktopUpdateDialog(
    updateState: AppUpdateState,
    onDismiss: () -> Unit,
    onConfirmUpdate: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = {
            if (!updateState.isDownloading) onDismiss()
        },
        icon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = strings.titleUpdateDialog,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = strings.descNewVersionAvailable(updateState.latestVersion),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = strings.labelVersionComparison(updateState.currentVersion, updateState.latestVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (updateState.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = strings.labelReleaseNotes,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = updateState.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }

                if (updateState.isDownloading) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = strings.labelDownloadAndApplying((updateState.downloadProgress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { updateState.downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                val err = updateState.error
                if (err != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmUpdate,
                enabled = !updateState.isDownloading,
            ) {
                Text(if (updateState.isDownloading) strings.statusUpdatingInProgress else strings.actionUpdateAndRestart)
            }
        },
        dismissButton = {
            if (!updateState.isDownloading) {
                OutlinedButton(onClick = onDismiss) {
                    Text(strings.actionUpdateLater)
                }
            }
        },
    )
}
