package com.comics8.desktop.ui.reader

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import com.comics8.desktop.ui.DesktopUiState
import com.comics8.desktop.ui.Screen

internal val LocalReaderKeyDispatcher = staticCompositionLocalOf<ReaderKeyDispatcher?> { null }

internal enum class ReaderKeyCommand {
    ADVANCE,
    RETREAT,
    TOGGLE_FULLSCREEN,
}

/** Resolves a desktop keyboard event into a viewer shortcut command, or `null` if unbound. */
internal fun resolveReaderKeyCommand(
    key: Key,
    isKeyDown: Boolean,
    isModifierPressed: Boolean,
    isR2L: Boolean,
): ReaderKeyCommand? {
    if (!isKeyDown || isModifierPressed) return null
    return when (key) {
        Key.F -> ReaderKeyCommand.TOGGLE_FULLSCREEN
        Key.DirectionRight -> if (isR2L) ReaderKeyCommand.RETREAT else ReaderKeyCommand.ADVANCE
        Key.DirectionLeft -> if (isR2L) ReaderKeyCommand.ADVANCE else ReaderKeyCommand.RETREAT
        Key.Spacebar, Key.PageDown -> ReaderKeyCommand.ADVANCE
        Key.PageUp -> ReaderKeyCommand.RETREAT
        else -> null
    }
}

/** Returns `true` when the viewer is active and no modal dialog is intercepting keyboard input. */
internal fun DesktopUiState.isReaderKeyDispatchActive(): Boolean =
    screen == Screen.Reader &&
        !(showDownloadOptions && series != null) &&
        !showPageJump &&
        !showUpdateDialog &&
        networkDraft == null &&
        artistPick == null &&
        !showRemoveSourceSheet &&
        sourceError == null

/**
 * Stack-ordered event dispatcher that routes events to the most recently registered active handler.
 */
internal open class StackedEventDispatcher<T> {
    private val handlers = LinkedHashSet<(T) -> Boolean>()

    fun register(handler: (T) -> Boolean) {
        handlers.remove(handler)
        handlers.add(handler)
    }

    fun unregister(handler: (T) -> Boolean) {
        handlers.remove(handler)
    }

    fun dispatch(event: T): Boolean {
        val active = handlers.lastOrNull() ?: return false
        return active(event)
    }
}

/**
 * Routes window-level viewer key events to the active [ReaderPane] handler without depending on
 * Compose focus retention across mode transitions or overlay button clicks.
 */
internal class ReaderKeyDispatcher : StackedEventDispatcher<KeyEvent>()
