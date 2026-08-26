package com.comics8.desktop.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.comics8.desktop.ui.theme.LocalStrings

@Composable
fun PageJumpDialog(
    current: Int,
    last: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val strings = LocalStrings.current
    var text by remember { mutableStateOf(current.toString()) }
    val maxPage = last.coerceAtLeast(1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = strings.titlePageJumpRange(maxPage),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        if (input.isEmpty() || input.all { it.isDigit() }) {
                            text = input
                        }
                    },
                    label = { Text(strings.labelJumpTargetPage) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val target = text.toIntOrNull()?.coerceIn(1, maxPage) ?: current
                    onConfirm(target)
                    onDismiss()
                },
            ) {
                Text(strings.actionJump)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.actionCancel)
            }
        },
    )
}
