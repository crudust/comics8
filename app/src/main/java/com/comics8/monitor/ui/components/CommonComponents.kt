package com.comics8.monitor.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comics8.core.source.ComicSource
import com.comics8.core.source.SourceRegistry

const val EMPTY_SOURCE_MESSAGE = "소스를 추가하세요"
const val IMPORT_JS_LABEL = "JS 파일 가져오기"
const val ADD_SOURCE_LABEL = "소스 추가…"
const val REMOVE_SOURCE_LABEL = "소스 제거"

@Composable
fun EmptySourcePane(
    modifier: Modifier = Modifier,
    onAddSource: (() -> Unit)? = null,
    onImportJs: (() -> Unit)? = null,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text(
            text = EMPTY_SOURCE_MESSAGE,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onAddSource != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAddSource) {
                Text("소스 추가")
            }
        }
        if (onImportJs != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onImportJs) {
                Text(IMPORT_JS_LABEL)
            }
        }
    }
}

@Composable
fun SourceAddDialog(
    onImportJs: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("소스 추가") },
        text = {
            TextButton(
                onClick = onImportJs,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(IMPORT_JS_LABEL)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        },
    )
}

@Composable
fun SourceRemoveDialog(
    sources: List<ComicSource>,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("소스 제거") },
        text = {
            if (sources.isEmpty()) {
                Text(EMPTY_SOURCE_MESSAGE)
            } else {
                LazyColumn {
                    items(sources, key = { it.id }) { source ->
                        Text(
                            text = source.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onRemove(source.id) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        },
    )
}

@Composable
fun SourceErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("소스를 가져올 수 없습니다") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("확인")
            }
        },
    )
}

@Composable
fun LoadingPane(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorPane(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String = "다시 시도",
    onRetry: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(actionLabel)
        }
    }
}

fun sourceChipLabel(sourceId: String, registry: SourceRegistry): String = registry.chipLabel(sourceId)

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onPrimary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
fun PageBar(
    page: Int,
    lastPage: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = onPrev, enabled = enabled && page > 1) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "이전 페이지")
        }
        TextButton(onClick = onJump, enabled = enabled) {
            Text("$page / $lastPage", fontWeight = FontWeight.SemiBold)
        }
        IconButton(onClick = onNext, enabled = enabled && page < lastPage) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "다음 페이지")
        }
    }
}

@Composable
fun PageJumpDialog(
    current: Int,
    last: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(current.toString()) }
    val focus = LocalFocusManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("페이지 이동") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(5) },
                label = { Text("1 ~ $last") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focus.clearFocus()
                        text.toIntOrNull()?.let(onConfirm)
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { text.toIntOrNull()?.let(onConfirm) },
                enabled = text.toIntOrNull() != null,
            ) {
                Text("이동")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        },
    )
}

@Composable
fun BoxScope.EpisodeBoundaryPrompt(
    visible: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.9f),
        exit = fadeOut() + scaleOut(targetScale = 0.9f),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 72.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.92f),
            shadowElevation = 6.dp,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .clickable(onClick = onClick),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inversePrimary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
