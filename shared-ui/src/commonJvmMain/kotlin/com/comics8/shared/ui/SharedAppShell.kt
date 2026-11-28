package com.comics8.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import com.comics8.core.app.AppScreen
import com.comics8.core.app.AppUiState
import com.comics8.core.app.SharedAppController
import com.comics8.shared.ui.browse.BrowsePane
import com.comics8.shared.ui.components.ArtistPickDialog
import com.comics8.shared.ui.components.DownloadOptionsDialog
import com.comics8.shared.ui.components.NetworkSourceDialog
import com.comics8.shared.ui.components.PageJumpDialog
import com.comics8.shared.ui.components.SourceErrorDialog
import com.comics8.shared.ui.components.SourceRemoveDialog
import com.comics8.shared.ui.components.TopBar
import com.comics8.shared.ui.components.UpdateDialog
import com.comics8.shared.ui.downloads.DownloadsPane
import com.comics8.shared.ui.history.HistoryPane
import com.comics8.shared.ui.series.SeriesPane
import com.comics8.shared.ui.settings.SettingsPane
import com.comics8.shared.ui.source.SourceManagerPane

@Composable
fun SharedAppShell(
    state: AppUiState,
    controller: SharedAppController,
    appVersion: String,
    compactTopBar: Boolean,
    showTapZoneAndVolumeInSettings: Boolean = false,
    showNotificationToggleInSourceManager: Boolean = false,
    notificationSummary: String? = null,
    notificationSettingsContent: (@Composable () -> Unit)? = null,
    updateConfirmActionLabel: String? = null,
    onConfirmUpdate: () -> Unit,
    onImportJs: () -> Unit,
    onAddSource: (() -> Unit)? = null,
    onPickLocalFolder: () -> Unit,
    onRemoveLocalFolder: (String) -> Unit,
    onAddLibraryFolder: () -> Unit = onPickLocalFolder,
    libraryRootLabel: (String) -> String = { it },
    onExportBackup: (onResult: (Boolean, String) -> Unit) -> Unit,
    onImportBackup: (onResult: (Boolean, String) -> Unit) -> Unit,
    readerContent: @Composable () -> Unit,
    browseContainer: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
    browsePaneWrapper: @Composable (
        gridState: LazyGridState,
        content: @Composable (Modifier) -> Unit,
    ) -> Unit = { _, content -> content(Modifier.fillMaxSize()) },
    seriesPaneWrapper: @Composable (
        listState: LazyListState,
        content: @Composable (Modifier) -> Unit,
    ) -> Unit = { _, content -> content(Modifier.fillMaxSize()) },
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state.screen) {
            AppScreen.Reader -> readerContent()
            AppScreen.Settings -> SettingsPane(
                state = state,
                controller = controller,
                appVersion = appVersion,
                showTapZoneAndVolume = showTapZoneAndVolumeInSettings,
                notificationSummary = notificationSummary,
                notificationSettingsContent = notificationSettingsContent,
                onExportBackup = onExportBackup,
                onImportBackup = onImportBackup,
                modifier = Modifier.fillMaxSize(),
            )
            AppScreen.SourceManager -> SourceManagerPane(
                state = state,
                controller = controller,
                onPickLocalFolder = onPickLocalFolder,
                onRemoveLocalFolder = onRemoveLocalFolder,
                libraryRootLabel = libraryRootLabel,
                showNotificationToggle = showNotificationToggleInSourceManager,
                modifier = Modifier.fillMaxSize(),
            )
            else -> browseContainer {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopBar(
                        state = state,
                        controller = controller,
                        compactMode = compactTopBar,
                    )
                    when (state.screen) {
                        AppScreen.Series -> {
                            val listState = rememberLazyListState()
                            Box(modifier = Modifier.weight(1f)) {
                                seriesPaneWrapper(listState) { paneModifier ->
                                    SeriesPane(
                                        state = state,
                                        controller = controller,
                                        listState = listState,
                                        modifier = paneModifier,
                                    )
                                }
                            }
                        }
                        AppScreen.History -> HistoryPane(
                            state = state,
                            controller = controller,
                            onAddSource = onAddSource,
                            onImportJs = onImportJs,
                            modifier = Modifier.weight(1f),
                        )
                        AppScreen.Downloads -> DownloadsPane(
                            state = state,
                            controller = controller,
                            onAddSource = onAddSource,
                            onImportJs = onImportJs,
                            modifier = Modifier.weight(1f),
                        )
                        else -> key(state.activeSourceId, state.tab) {
                            val gridState = rememberLazyGridState()
                            Box(modifier = Modifier.weight(1f)) {
                                browsePaneWrapper(gridState) { paneModifier ->
                                    BrowsePane(
                                        state = state,
                                        controller = controller,
                                        gridState = gridState,
                                        onAddSource = onAddSource,
                                        onImportJs = onImportJs,
                                        onAddLibraryFolder = onAddLibraryFolder,
                                        modifier = paneModifier,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val currentSeries = state.series
        if (state.showDownloadOptions && currentSeries != null) {
            DownloadOptionsDialog(
                series = currentSeries,
                episodes = state.downloadCatalog,
                catalogLoading = state.downloadCatalogLoading,
                onDismiss = controller::closeDownloadOptions,
                onConfirm = controller::startDownloadEpisodes,
            )
        }
        if (state.showPageJump) {
            PageJumpDialog(
                current = if (state.screen == AppScreen.Series) state.episodePage else state.page,
                last = if (state.screen == AppScreen.Series) state.episodeLastPage else state.lastPage,
                onDismiss = { controller.togglePageJump(false) },
                onConfirm = { page ->
                    if (state.screen == AppScreen.Series) {
                        controller.goToEpisodePage(page)
                    } else {
                        controller.goToPage(page)
                    }
                },
            )
        }
        if (state.showUpdateDialog) {
            UpdateDialog(
                updateState = state.updateState,
                onDismiss = controller::dismissUpdateDialog,
                onConfirmUpdate = onConfirmUpdate,
                confirmActionLabel = updateConfirmActionLabel,
            )
        }
        state.networkDraft?.let { draft ->
            NetworkSourceDialog(
                draft = draft,
                testing = state.networkTesting,
                testMessage = state.networkTestMessage,
                testSucceeded = state.networkTestSucceeded,
                onChange = controller::updateNetworkDraft,
                onTest = controller::testNetworkConnection,
                onRegister = controller::registerNetworkConnection,
                onDismiss = controller::closeNetworkSourceDialog,
            )
        }
        val artistPick = state.artistPick
        if (artistPick != null) {
            ArtistPickDialog(
                artists = artistPick.item.artistChoices,
                onPick = controller::pickArtist,
                onDismiss = controller::dismissArtistPick,
            )
        }
        if (state.showRemoveSourceSheet) {
            SourceRemoveDialog(
                sources = state.installedSources,
                onRemove = controller::uninstallSource,
                onDismiss = controller::closeRemoveSourceSheet,
            )
        }
        val sourceError = state.sourceError
        if (sourceError != null) {
            SourceErrorDialog(
                message = sourceError,
                onDismiss = controller::dismissSourceError,
            )
        }
    }
}
