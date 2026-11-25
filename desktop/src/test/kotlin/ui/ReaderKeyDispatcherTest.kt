package com.comics8.desktop.ui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.comics8.core.model.ToonItem
import com.comics8.core.source.network.NetworkProtocol
import com.comics8.core.source.network.NetworkSourceConfig
import com.comics8.desktop.ui.reader.ReaderKeyCommand
import com.comics8.desktop.ui.reader.ReaderKeyDispatcher
import com.comics8.desktop.ui.reader.ReaderKeyInput
import com.comics8.desktop.ui.reader.isReaderKeyDispatchActive
import com.comics8.desktop.ui.reader.resolveReaderKeyCommand
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReaderKeyDispatcherTest {

    @Test
    fun resolveReaderKeyCommandMapsLtrAndRtlNavigation() {
        assertThat(resolveReaderKeyCommand(Key.DirectionRight, isKeyDown = true, isModifierPressed = false, isR2L = false))
            .isEqualTo(ReaderKeyCommand.ADVANCE)
        assertThat(resolveReaderKeyCommand(Key.DirectionLeft, isKeyDown = true, isModifierPressed = false, isR2L = false))
            .isEqualTo(ReaderKeyCommand.RETREAT)

        assertThat(resolveReaderKeyCommand(Key.DirectionRight, isKeyDown = true, isModifierPressed = false, isR2L = true))
            .isEqualTo(ReaderKeyCommand.RETREAT)
        assertThat(resolveReaderKeyCommand(Key.DirectionLeft, isKeyDown = true, isModifierPressed = false, isR2L = true))
            .isEqualTo(ReaderKeyCommand.ADVANCE)

        assertThat(resolveReaderKeyCommand(Key.Spacebar, isKeyDown = true, isModifierPressed = false, isR2L = true))
            .isEqualTo(ReaderKeyCommand.ADVANCE)
        assertThat(resolveReaderKeyCommand(Key.PageDown, isKeyDown = true, isModifierPressed = false, isR2L = false))
            .isEqualTo(ReaderKeyCommand.ADVANCE)
        assertThat(resolveReaderKeyCommand(Key.PageUp, isKeyDown = true, isModifierPressed = false, isR2L = true))
            .isEqualTo(ReaderKeyCommand.RETREAT)
        assertThat(resolveReaderKeyCommand(Key.F, isKeyDown = true, isModifierPressed = false, isR2L = false))
            .isEqualTo(ReaderKeyCommand.TOGGLE_FULLSCREEN)
    }

    @Test
    fun resolveReaderKeyCommandIgnoresKeyUpModifiedAndUnboundKeys() {
        assertThat(resolveReaderKeyCommand(Key.DirectionRight, isKeyDown = false, isModifierPressed = false, isR2L = false))
            .isNull()
        assertThat(resolveReaderKeyCommand(Key.DirectionRight, isKeyDown = true, isModifierPressed = true, isR2L = false))
            .isNull()
        assertThat(resolveReaderKeyCommand(Key.F, isKeyDown = true, isModifierPressed = true, isR2L = false))
            .isNull()
        assertThat(resolveReaderKeyCommand(Key.A, isKeyDown = true, isModifierPressed = false, isR2L = false))
            .isNull()
    }

    @OptIn(InternalComposeUiApi::class)
    @Test
    fun dispatcherRoutesComposeKeyEventsToMostRecentlyRegisteredHandler() {
        val dispatcher = ReaderKeyDispatcher()
        // Exercise the exact Window(onKeyEvent = { event -> dispatcher.dispatch(event) }) lambda shape
        // that previously triggered InternalKeyEvent -> KeyEvent ClassCastException.
        val windowKeyCallback: (KeyEvent) -> Boolean = { event -> dispatcher.dispatch(event) }

        val spaceDown = KeyEvent(key = Key.Spacebar, type = KeyEventType.KeyDown)
        val rightDown = KeyEvent(key = Key.DirectionRight, type = KeyEventType.KeyDown)
        val leftCtrlDown = KeyEvent(key = Key.DirectionLeft, type = KeyEventType.KeyDown, isCtrlPressed = true)
        val spaceUpShift = KeyEvent(key = Key.Spacebar, type = KeyEventType.KeyUp, isShiftPressed = true)

        assertThat(windowKeyCallback(spaceDown)).isFalse()

        val firstInputs = mutableListOf<ReaderKeyInput>()
        val secondInputs = mutableListOf<ReaderKeyInput>()
        val first: (ReaderKeyInput) -> Boolean = { firstInputs += it; true }
        val second: (ReaderKeyInput) -> Boolean = { secondInputs += it; true }

        dispatcher.register(first)
        assertThat(windowKeyCallback(rightDown)).isTrue()
        assertThat(firstInputs).containsExactly(
            ReaderKeyInput(key = Key.DirectionRight, isKeyDown = true, isModifierPressed = false),
        )

        dispatcher.register(second)
        assertThat(windowKeyCallback(leftCtrlDown)).isTrue()
        assertThat(firstInputs).hasSize(1)
        assertThat(secondInputs).containsExactly(
            ReaderKeyInput(key = Key.DirectionLeft, isKeyDown = true, isModifierPressed = true),
        )

        dispatcher.unregister(second)
        assertThat(windowKeyCallback(spaceDown)).isTrue()
        assertThat(windowKeyCallback(spaceUpShift)).isTrue()
        assertThat(firstInputs).containsExactly(
            ReaderKeyInput(key = Key.DirectionRight, isKeyDown = true, isModifierPressed = false),
            ReaderKeyInput(key = Key.Spacebar, isKeyDown = true, isModifierPressed = false),
            ReaderKeyInput(key = Key.Spacebar, isKeyDown = false, isModifierPressed = false),
        ).inOrder()

        dispatcher.unregister(first)
        assertThat(windowKeyCallback(spaceDown)).isFalse()
    }

    @Test
    fun isReaderKeyDispatchActiveRespectsScreenAndModalDialogs() {
        val sampleSeries = ToonItem(id = "1", title = "Sample", thumbUrl = "", href = "")
        val baseReader = DesktopUiState(screen = Screen.Reader)

        assertThat(baseReader.isReaderKeyDispatchActive()).isTrue()
        assertThat(DesktopUiState(screen = Screen.Browse).isReaderKeyDispatchActive()).isFalse()
        assertThat(DesktopUiState(screen = Screen.Series).isReaderKeyDispatchActive()).isFalse()

        assertThat(baseReader.copy(showUpdateDialog = true).isReaderKeyDispatchActive()).isFalse()
        assertThat(baseReader.copy(showPageJump = true).isReaderKeyDispatchActive()).isFalse()
        assertThat(baseReader.copy(showRemoveSourceSheet = true).isReaderKeyDispatchActive()).isFalse()
        assertThat(baseReader.copy(sourceError = "err").isReaderKeyDispatchActive()).isFalse()
        assertThat(
            baseReader.copy(
                networkDraft = NetworkSourceConfig(protocol = NetworkProtocol.WEBDAV, name = "test"),
            ).isReaderKeyDispatchActive(),
        ).isFalse()
        assertThat(
            baseReader.copy(
                artistPick = ArtistPickRequest(item = sampleSeries),
            ).isReaderKeyDispatchActive(),
        ).isFalse()

        // DownloadOptionsDialog only renders when both showDownloadOptions and series are present.
        assertThat(baseReader.copy(showDownloadOptions = true, series = null).isReaderKeyDispatchActive()).isTrue()
        assertThat(baseReader.copy(showDownloadOptions = true, series = sampleSeries).isReaderKeyDispatchActive()).isFalse()
    }
}
