package com.comics8.monitor.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import com.comics8.core.i18n.AppStrings
import com.comics8.core.i18n.displaySourceTitle
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comics8.core.source.ComicSource
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.monitor.ui.theme.LocalStrings

@Composable
fun EmptySourcePane(
    modifier: Modifier = Modifier,
    onAddSource: (() -> Unit)? = null,
    onImportJs: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text(
            text = strings.promptAddFolder,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onAddSource != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAddSource) {
                Text(strings.actionAddSource)
            }
        }
        if (onImportJs != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onImportJs) {
                Text(strings.actionImportJsFile)
            }
        }
    }
}

@Composable
fun SourceRemoveDialog(
    sources: List<ComicSource>,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    val removableSources = sources.filterNot { it.id == WorkId.LOCAL_SOURCE }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.titleDeleteSource) },
        text = {
            if (removableSources.isEmpty()) {
                Text(strings.promptAddFolder)
            } else {
                LazyColumn {
                    items(removableSources, key = { it.id }) { source ->
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
                Text(strings.actionCancel)
            }
        },
    )
}

@Composable
fun SourceErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    val strings = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.titleCannotImportSource) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.actionConfirm)
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
    actionLabel: String? = null,
    onRetry: () -> Unit,
) {
    val strings = LocalStrings.current
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
            Text(actionLabel ?: strings.actionRetry)
        }
    }
}

fun sourceChipLabel(sourceId: String, registry: SourceRegistry, strings: AppStrings): String =
    registry.displaySourceTitle(sourceId, strings)

@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onPrimary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 6.dp, vertical = 2.dp),
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
    val strings = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = onPrev, enabled = enabled && page > 1) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.actionPrev)
        }
        TextButton(onClick = onJump, enabled = enabled) {
            Text("$page / $lastPage", fontWeight = FontWeight.SemiBold)
        }
        IconButton(onClick = onNext, enabled = enabled && page < lastPage) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = strings.actionNext)
        }
    }
}

@Composable
fun Modifier.horizontalPageSwipe(
    enabled: Boolean = true,
    page: Int,
    lastPage: Int,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
): Modifier {
    val isEnabled by rememberUpdatedState(enabled && lastPage > 1)
    val currentPage by rememberUpdatedState(page)
    val currentLastPage by rememberUpdatedState(lastPage)
    val currentOnPrev by rememberUpdatedState(onPrevPage)
    val currentOnNext by rememberUpdatedState(onNextPage)

    return this.pointerInput(Unit) {
        var totalDragX = 0f
        val velocityTracker = VelocityTracker()

        detectHorizontalDragGestures(
            onDragStart = {
                totalDragX = 0f
                velocityTracker.resetTracking()
            },
            onHorizontalDrag = { change, dragAmount ->
                if (!isEnabled) return@detectHorizontalDragGestures
                change.consume()
                totalDragX += dragAmount
                velocityTracker.addPosition(change.uptimeMillis, change.position)
            },
            onDragEnd = {
                if (!isEnabled) {
                    totalDragX = 0f
                    return@detectHorizontalDragGestures
                }
                val velocityX = velocityTracker.calculateVelocity().x
                val thresholdPx = 50.dp.toPx()
                val velocityThresholdPx = 300.dp.toPx()

                if (totalDragX < 0f && (totalDragX < -thresholdPx || velocityX < -velocityThresholdPx)) {
                    if (currentPage < currentLastPage) {
                        currentOnNext()
                    }
                } else if (totalDragX > 0f && (totalDragX > thresholdPx || velocityX > velocityThresholdPx)) {
                    if (currentPage > 1) {
                        currentOnPrev()
                    }
                }
                totalDragX = 0f
            },
            onDragCancel = {
                totalDragX = 0f
            },
        )
    }
}

@Composable
fun PageJumpDialog(
    current: Int,
    last: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val strings = LocalStrings.current
    var text by remember { mutableStateOf(current.toString()) }
    val focus = LocalFocusManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.titlePageJump) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(5) },
                label = { Text("1 ~ $last") },
                shape = RoundedCornerShape(12.dp),
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
