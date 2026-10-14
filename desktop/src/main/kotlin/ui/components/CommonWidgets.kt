package com.comics8.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import com.comics8.core.model.ReaderDomain.BoundaryDirection
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.comics8.core.i18n.AppStrings
import com.comics8.core.i18n.displaySourceTitle
import com.comics8.core.source.ComicSource
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.desktop.ui.theme.LocalStrings
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun sourceChipLabel(sourceId: String, registry: SourceRegistry, strings: AppStrings): String =
    registry.displaySourceTitle(sourceId, strings)

@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.error)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onError,
        )
    }
}

const val EMPTY_SOURCE_MESSAGE = "Add Folder"
const val IMPORT_JS_LABEL = "Import JS File"
const val ADD_SOURCE_LABEL = "Add"
const val REMOVE_SOURCE_LABEL = "Remove"

fun pickJsFile(dialogTitle: String = IMPORT_JS_LABEL): File? {
    val dialog = FileDialog(null as Frame?, dialogTitle, FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.lowercase().endsWith(".js") }
    dialog.file = "*.js"
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    val file = File(dir, name)
    return file.takeIf { it.isFile }
}

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
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorPane(
    message: String,
    actionLabel: String? = null,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
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
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(actionLabel ?: strings.actionRetry)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.desktopHorizontalPageSwipe(
    enabled: Boolean = true,
    page: Int,
    lastPage: Int,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onBoundaryPrev: (() -> Unit)? = null,
    onBoundaryNext: (() -> Unit)? = null,
): Modifier {
    val isEnabled by rememberUpdatedState(enabled && (lastPage > 1 || onBoundaryPrev != null || onBoundaryNext != null))
    val currentPage by rememberUpdatedState(page)
    val currentLastPage by rememberUpdatedState(lastPage)
    val currentOnPrev by rememberUpdatedState(onPrevPage)
    val currentOnNext by rememberUpdatedState(onNextPage)
    val currentOnBoundaryPrev by rememberUpdatedState(onBoundaryPrev)
    val currentOnBoundaryNext by rememberUpdatedState(onBoundaryNext)
    val scope = rememberCoroutineScope()
    var gestureResetJob by remember { mutableStateOf<Job?>(null) }
    var activeGestureDirection by remember { mutableStateOf<BoundaryDirection?>(null) }

    return this.onPointerEvent(PointerEventType.Scroll) { event ->
        if (!isEnabled) return@onPointerEvent
        val deltaX = event.changes.sumOf { it.scrollDelta.x.toDouble() }.toFloat()
        val deltaY = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()

        val absX = abs(deltaX)
        val absY = abs(deltaY)
        if (absX < 0.8f || absX < absY) {
            if (absX < 0.2f && absY < 0.2f) {
                gestureResetJob?.cancel()
                activeGestureDirection = null
            }
            return@onPointerEvent
        }

        val direction = if (deltaX > 0f) BoundaryDirection.ADVANCE else BoundaryDirection.RETREAT

        gestureResetJob?.cancel()
        gestureResetJob = scope.launch {
            delay(250)
            activeGestureDirection = null
        }

        if (direction == activeGestureDirection) return@onPointerEvent
        activeGestureDirection = direction
        event.changes.forEach { it.consume() }

        if (direction == BoundaryDirection.ADVANCE) {
            if (currentPage < currentLastPage) {
                currentOnNext()
            } else {
                currentOnBoundaryNext?.invoke()
            }
        } else {
            if (currentPage > 1) {
                currentOnPrev()
            } else {
                currentOnBoundaryPrev?.invoke()
            }
        }
    }
}

