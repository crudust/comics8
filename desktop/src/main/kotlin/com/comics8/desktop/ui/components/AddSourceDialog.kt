package com.comics8.desktop.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.comics8.core.source.SourceOffer

@Composable
fun AddSourceDialog(
    offers: List<SourceOffer>,
    onPick: (SourceOffer) -> Unit,
    onImportJs: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("소스 추가") },
        text = {
            LazyColumn {
                items(offers, key = { it.id }) { offer ->
                    Text(
                        text = offer.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(offer) }
                            .padding(vertical = 10.dp),
                    )
                }
                item {
                    Text(
                        text = IMPORT_JS_LABEL,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onImportJs() }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
            }
        },
    )
}
