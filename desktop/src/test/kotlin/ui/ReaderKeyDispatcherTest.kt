package com.comics8.desktop.ui

import androidx.compose.ui.input.key.Key
import com.comics8.core.model.ToonItem
import com.comics8.core.source.network.NetworkProtocol
import com.comics8.core.source.network.NetworkSourceConfig
import com.comics8.desktop.ui.reader.ReaderKeyCommand
import com.comics8.desktop.ui.reader.StackedEventDispatcher
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

    @Test
    fun dispatcherRoutesToMostRecentlyRegisteredHandler() {
        val dispatcher = StackedEventDispatcher<Key>()

        assertThat(dispatcher.dispatch(Key.DirectionRight)).isFalse()

        val firstEvents = mutableListOf<Key>()
        val secondEvents = mutableListOf<Key>()
        val first: (Key) -> Boolean = { firstEvents += it; true }
        val second: (Key) -> Boolean = { secondEvents += it; true }

        dispatcher.register(first)
        assertThat(dispatcher.dispatch(Key.DirectionRight)).isTrue()
        assertThat(firstEvents).containsExactly(Key.DirectionRight)

        dispatcher.register(second)
        assertThat(dispatcher.dispatch(Key.DirectionLeft)).isTrue()
        assertThat(firstEvents).containsExactly(Key.DirectionRight)
        assertThat(secondEvents).containsExactly(Key.DirectionLeft)

        dispatcher.unregister(second)
        assertThat(dispatcher.dispatch(Key.Spacebar)).isTrue()
        assertThat(firstEvents).containsExactly(Key.DirectionRight, Key.Spacebar)

        dispatcher.unregister(first)
        assertThat(dispatcher.dispatch(Key.Spacebar)).isFalse()
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
