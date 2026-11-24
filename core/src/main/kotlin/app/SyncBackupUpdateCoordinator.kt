package com.comics8.core.app

import com.comics8.core.i18n.AppStrings
import com.comics8.core.i18n.I18n
import com.comics8.core.model.CustomProxyConfig
import com.comics8.core.model.PairConfirmResult
import com.comics8.core.model.PairRequestResult
import com.comics8.core.model.ProxyType
import com.comics8.core.model.SyncResult
import com.comics8.core.network.NetworkSettingsStorage
import com.comics8.core.sync.SyncActionCoordinator
import com.comics8.core.sync.SyncConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SyncBackupUpdateCoordinator(
    private val controller: SharedAppController,
) {
    private val state get() = controller._state
    private val repository get() = controller.repository
    private val sourceSettings get() = controller.sourceSettings
    private val prefs get() = controller.prefs
    private val platform get() = controller.platform
    private val scope get() = controller.scope

    private val syncActions by lazy {
        SyncActionCoordinator(scope, { repository.syncManager }, ::applySyncRefresh)
    }

    fun checkForUpdate(manual: Boolean = false, onFeedback: ((String) -> Unit)? = null) {
        if (state.value.updateState.isChecking) return
        scope.launch {
            state.update { it.copy(updateState = it.updateState.copy(isChecking = true)) }
            val currentVersionName = platform.appVersionName
            val strings = I18n.strings(state.value.appLanguage)
            val serverUrl = state.value.syncState.serverUrl.ifBlank { SyncConstants.DEFAULT_SERVER_URL }

            val updateState = platform.checkForAppUpdate(serverUrl, strings)
            val newServerUrl = updateState.newServerUrl
            if (newServerUrl != null) {
                repository.syncManager?.updateServerUrlIfChanged(newServerUrl)
            }
            state.update {
                it.copy(
                    updateState = updateState.copy(isChecking = false),
                    showUpdateDialog = if (manual && updateState.hasUpdate) true else it.showUpdateDialog,
                )
            }
            if (manual) {
                if (updateState.error != null && !platform.isPlayStoreBuild) {
                    onFeedback?.invoke(updateState.error)
                } else if (!updateState.hasUpdate) {
                    onFeedback?.invoke(strings.msgLatestVersionInUse(currentVersionName))
                }
            }
        }
    }

    fun openUpdateDialog() {
        state.update { it.copy(showUpdateDialog = true) }
    }

    fun dismissUpdateDialog() {
        state.update { it.copy(showUpdateDialog = false) }
    }

    fun startUpdate() {
        val updateUrl = state.value.updateState.downloadUrl
        if (updateUrl.isBlank()) return
        scope.launch {
            state.update {
                it.copy(
                    updateState = it.updateState.copy(
                        isDownloading = true,
                        downloadProgress = 0f,
                        error = null,
                    ),
                )
            }
            val result = platform.downloadAndInstallUpdate(updateUrl) { progress ->
                state.update { it.copy(updateState = it.updateState.copy(downloadProgress = progress)) }
            }
            result.fold(
                onSuccess = {
                    state.update { it.copy(updateState = it.updateState.copy(isDownloading = false)) }
                },
                onFailure = { err ->
                    val strings = I18n.strings(state.value.appLanguage)
                    val detail = err.localizedMessage ?: err.message
                    val errorMsg = if (detail.isNullOrBlank()) {
                        strings.msgUpdateDownloadFailed
                    } else {
                        "${strings.msgUpdateDownloadFailed}: $detail"
                    }
                    state.update {
                        it.copy(
                            updateState = it.updateState.copy(
                                isDownloading = false,
                                error = errorMsg,
                            ),
                        )
                    }
                },
            )
        }
    }

    fun toggleNotificationEnabled(enabled: Boolean) {
        platform.setNotificationEnabled(enabled)
        platform.scheduleBackgroundRefresh(enabled, state.value.notificationIntervalMinutes)
        state.update { it.copy(notificationEnabled = enabled) }
    }

    fun setNotificationIntervalMinutes(minutes: Long) {
        platform.setNotificationIntervalMinutes(minutes)
        if (state.value.notificationEnabled) {
            platform.scheduleBackgroundRefresh(true, minutes)
        }
        state.update { it.copy(notificationIntervalMinutes = minutes) }
    }

    fun toggleSourceNotification(sourceId: String, enabled: Boolean) {
        sourceSettings.setNotificationEnabled(sourceId, enabled)
        state.update {
            it.copy(notificationSources = it.notificationSources + (sourceId to enabled))
        }
    }

    fun getSourceNotificationEnabled(sourceId: String): Boolean =
        sourceSettings.isNotificationEnabled(sourceId)

    fun checkNotificationPermission() {
        val granted = platform.checkNotificationPermission()
        state.update { it.copy(hasNotificationPermission = granted) }
    }

    suspend fun applySyncRefresh() {
        controller.browseSearch.clearCatalogCaches()
        if (state.value.activeSourceId != null) {
            controller.browseSearch.loadPage(state.value.page, replace = true)
        }
        val sourceId = state.value.activeSourceId ?: return
        val updatedHistory = repository.getHistoryRecords(sourceId)
        val counts = controller.countIfNeeded(updatedHistory.map { item -> item.workId() }, sourceId)
        val seriesItem = state.value.series
        val seriesFav = seriesItem?.let { repository.isFavorite(it.workId()) } ?: state.value.seriesFavorited
        state.update {
            it.copy(
                historyItems = updatedHistory,
                readCounts = it.readCounts + counts,
                seriesFavorited = seriesFav,
            )
        }
        if (state.value.screen == AppScreen.History) {
            controller.historyDownloads.loadHistory()
        }
    }

    fun syncNow() = syncActions.syncNow()

    fun restoreWithMasterKey(key: String, onResult: (SyncResult) -> Unit) =
        syncActions.restore(key, onResult)

    fun updateSyncKey(key: String) {
        repository.syncManager?.updateSyncKey(key)
    }

    fun generateNewSyncKey() {
        repository.syncManager?.generateNewKey()
    }

    fun updateSyncServerUrl(url: String) {
        repository.syncManager?.updateServerUrl(url)
    }

    fun toggleAutoSync(enabled: Boolean) {
        repository.syncManager?.setAutoSyncEnabled(enabled)
    }

    fun setProxyType(type: ProxyType) {
        val next = state.value.networkSettings.copy(proxyType = type)
        NetworkSettingsStorage.save(next) { k, v -> prefs.putString(k, v) }
        state.update { it.copy(networkSettings = next, proxyTestResult = null, proxyTestSuccess = null) }
        val serverUrl = repository.syncManager?.syncState?.value?.serverUrl ?: SyncConstants.DEFAULT_SERVER_URL
        repository.client.applyNetworkSettings(next, serverUrl)
    }

    fun setCustomProxy(config: CustomProxyConfig) {
        val next = state.value.networkSettings.copy(customProxy = config)
        NetworkSettingsStorage.save(next) { k, v -> prefs.putString(k, v) }
        state.update { it.copy(networkSettings = next, proxyTestResult = null, proxyTestSuccess = null) }
        val serverUrl = repository.syncManager?.syncState?.value?.serverUrl ?: SyncConstants.DEFAULT_SERVER_URL
        repository.client.applyNetworkSettings(next, serverUrl)
    }

    fun testProxyConnection(strings: AppStrings) {
        val current = state.value.networkSettings
        state.update { it.copy(proxyTesting = true, proxyTestResult = strings.statusTestingProxy, proxyTestSuccess = null) }
        scope.launch {
            val result = repository.client.testProxyConnection(current.proxyType, current.customProxy)
            result.onSuccess { latency ->
                state.update {
                    it.copy(
                        proxyTesting = false,
                        proxyTestResult = strings.statusProxySuccess(latency),
                        proxyTestSuccess = true,
                    )
                }
            }.onFailure { err ->
                state.update {
                    it.copy(
                        proxyTesting = false,
                        proxyTestResult = strings.statusProxyFailed(err.message ?: "Error"),
                        proxyTestSuccess = false,
                    )
                }
            }
        }
    }

    fun toggleServerProxy(enabled: Boolean) {
        repository.syncManager?.setUseServerProxy(enabled)
    }

    fun requestPairingCode(onResult: (PairRequestResult) -> Unit) =
        syncActions.requestPairingCode(onResult)

    fun confirmPairingCode(code: String, onResult: (PairConfirmResult) -> Unit) =
        syncActions.confirmPairingCode(code, onResult)

    fun exportBackupWithWriter(
        writeJson: suspend (String) -> Unit,
        onResult: (Boolean, String) -> Unit,
    ) {
        scope.launch {
            try {
                val json = repository.exportBackupJson()
                withContext(Dispatchers.IO) {
                    writeJson(json)
                }
                val stats = repository.getBackupStats()
                onResult(true, "백업 파일이 저장되었습니다. (즐겨찾기 ${stats.favoriteCount}개, 기록 ${stats.historyCount}개)")
            } catch (e: Exception) {
                onResult(false, "백업 저장 실패: ${e.localizedMessage ?: "알 수 없는 오류"}")
            }
        }
    }

    fun importBackupWithReader(
        readJson: suspend () -> String,
        onResult: (Boolean, String) -> Unit,
    ) {
        scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    readJson()
                }
                val result = repository.importBackupJson(json)
                if (result.success) {
                    if (state.value.activeSourceId != null) {
                        controller.browseSearch.loadPage(page = state.value.page, replace = true)
                    }
                    val sourceId = state.value.activeSourceId
                    if (sourceId != null) {
                        val updatedHistory = repository.getHistoryRecords(sourceId)
                        state.update { it.copy(historyItems = updatedHistory) }
                    }
                    onResult(true, "복원 완료: 즐겨찾기 ${result.favoriteCount}개, 기록 ${result.historyCount}개")
                } else {
                    onResult(false, result.message)
                }
            } catch (e: Exception) {
                onResult(false, "복원 실패: ${e.localizedMessage ?: "파일 읽기 오류"}")
            }
        }
    }
}
