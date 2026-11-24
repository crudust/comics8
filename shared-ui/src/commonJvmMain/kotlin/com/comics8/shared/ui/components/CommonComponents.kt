package com.comics8.shared.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comics8.core.i18n.AppStrings
import com.comics8.core.i18n.displaySourceTitle
import com.comics8.core.source.ComicSource
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.shared.ui.theme.LocalStrings

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
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) {
            Text(actionLabel ?: strings.actionRetry)
        }
    }
}

fun sourceChipLabel(sourceId: String, registry: SourceRegistry, strings: AppStrings): String =
    registry.displaySourceTitle(sourceId, strings)

@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
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
