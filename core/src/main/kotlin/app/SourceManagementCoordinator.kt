package com.comics8.core.app

import com.comics8.core.model.BrowseTab
import com.comics8.core.model.EpisodeSortOrder
import com.comics8.core.model.ProgressDisplayMode
import com.comics8.core.model.sortedWithOrder
import com.comics8.core.source.ComicSource
import com.comics8.core.source.WorkId
import com.comics8.core.source.js.JsComicSource
import com.comics8.core.source.js.JsPackStore
import com.comics8.core.source.local.LibraryScanIndex
import com.comics8.core.source.local.LocalSource
import com.comics8.core.source.network.NetworkLibrarySource
import com.comics8.core.source.network.NetworkProtocol
import com.comics8.core.source.network.NetworkSourceConfig
import com.comics8.core.source.network.NetworkSourceRuntime
import com.comics8.core.source.network.createNetworkFileSystem
import com.comics8.core.source.resolveSourceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal class SourceManagementCoordinator(
    private val controller: SharedAppController,
) {
    private val state get() = controller._state
    private val repository get() = controller.repository
    private val sourceSettings get() = controller.sourceSettings
    private val platform get() = controller.platform
    private val scope get() = controller.scope

    fun applyActiveSource(sourceId: String?, loadListing: Boolean) {
        val source = sourceId?.let { repository.activeSource(it) }
        if (source == null) {
            controller.readerSession.cancelReaderJob()
            controller.browseSearch.cancelEpisodeJob()
            controller.browseSearch.lastViewedSeries = null
            controller.readerSession.lastViewedEpisode = null
        }
        val tabs = source?.let { BrowseTab.forSource(it) }.orEmpty()
        val nextTab = BrowseTab.afterSourceChange(state.value.tab, tabs)
        state.update {
            it.copy(
                activeSourceId = source?.id,
                installedSources = repository.installedSources(),
                browseTabs = tabs,
                tab = nextTab,
                items = if (source == null) emptyList() else it.items,
                loading = source != null && loadListing,
                error = if (source == null) null else it.error,
                searchSuggestions = emptyList(),
                sourceRegistry = repository.sourceRegistry(),
                libraryRoots = sourceSettings.libraryRoots(),
                screen = if (source == null) AppScreen.Browse else it.screen,
                series = if (source == null) null else it.series,
                episodeSortOrder = if (source != null) sourceSettings.episodeSortOrder(source.id) else it.episodeSortOrder,
            )
        }
        if (source == null || nextTab == null) return
        if (loadListing) {
            controller.browseSearch.selectTab(nextTab, force = true)
        }
    }

    fun setActiveSource(sourceId: String) {
        val installed = sourceSettings.installedIds()
        if (sourceId !in installed) return
        sourceSettings.setActiveSourceId(sourceId)
        applyActiveSource(sourceId, loadListing = true)
        if (state.value.screen == AppScreen.History) {
            controller.historyDownloads.loadHistory()
        }
        if (state.value.screen == AppScreen.Downloads) {
            controller.historyDownloads.loadDownloads()
        }
    }

    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        val id = sourceId.trim()
        if (id.isEmpty()) return
        if (!enabled) {
            uninstallSource(id)
            return
        }
        if (id == WorkId.LOCAL_SOURCE) {
            ensureLocalSourceLoaded()
        }
        sourceSettings.setEnabled(id, true)
        state.update {
            it.copy(
                installedSources = repository.installedSources(),
                libraryRoots = sourceSettings.libraryRoots(),
            )
        }
    }

    fun openAddSourceSheet() {
        state.update {
            it.copy(
                showAddSourceSheet = true,
                showRemoveSourceSheet = false,
            )
        }
    }

    fun dismissAddSourceSheet() {
        state.update { it.copy(showAddSourceSheet = false) }
    }

    fun openNetworkSourceDialog(protocol: NetworkProtocol) {
        val defaultName = if (protocol == NetworkProtocol.SMB) "SMB 저장소" else "WebDAV 저장소"
        state.update {
            it.copy(
                networkDraft = NetworkSourceConfig(protocol = protocol, name = defaultName),
                networkTesting = false,
                networkTestMessage = null,
                networkTestSucceeded = false,
                showAddSourceSheet = false,
            )
        }
    }

    fun updateNetworkDraft(draft: NetworkSourceConfig) {
        state.update {
            it.copy(
                networkDraft = draft,
                networkTestMessage = null,
                networkTestSucceeded = false,
            )
        }
    }

    fun closeNetworkSourceDialog() {
        state.update {
            it.copy(
                networkDraft = null,
                networkTesting = false,
                networkTestMessage = null,
                networkTestSucceeded = false,
            )
        }
    }

    fun testNetworkConnection() {
        val draft = state.value.networkDraft ?: return
        scope.launch {
            state.update { it.copy(networkTesting = true, networkTestMessage = null, networkTestSucceeded = false) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val valid = draft.validated()
                    createNetworkFileSystem(valid).use { it.test() }
                    valid
                }
            }
            state.update {
                if (it.networkDraft?.id != draft.id) it else it.copy(
                    networkTesting = false,
                    networkTestMessage = result.fold(
                        onSuccess = { "연결에 성공했습니다" },
                        onFailure = { error -> error.message?.ifBlank { null } ?: "연결에 실패했습니다" },
                    ),
                    networkTestSucceeded = result.isSuccess,
                    networkDraft = result.getOrNull() ?: it.networkDraft,
                )
            }
        }
    }

    fun registerNetworkConnection() {
        val current = state.value
        val draft = current.networkDraft ?: return
        if (!current.networkTestSucceeded) return
        scope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    val valid = draft.validated()
                    controller.networkSourceStore.save(valid)
                    controller.networkSourceStore.createSource(valid)
                }
                val registry = repository.sourceRegistry()
                registry.remove(source.id)
                check(registry.add(source)) { "연결을 등록할 수 없습니다" }
                sourceSettings.setInstalledIds(sourceSettings.installedIds() + source.id)
                controller.browseSearch.clearCatalogCache(source.id)
                closeNetworkSourceDialog()
                setActiveSource(source.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update {
                    it.copy(networkTesting = false, networkTestMessage = e.message ?: "등록에 실패했습니다")
                }
            }
        }
    }

    fun openRemoveSourceSheet() {
        if (state.value.installedSources.none { it.id != WorkId.LOCAL_SOURCE }) return
        state.update { it.copy(showRemoveSourceSheet = true, showAddSourceSheet = false) }
    }

    fun closeRemoveSourceSheet() {
        state.update { it.copy(showRemoveSourceSheet = false) }
    }

    fun dismissSourceError() {
        state.update { it.copy(sourceError = null) }
    }

    fun setSourceError(message: String) {
        state.update { it.copy(sourceError = message) }
    }

    fun importJsScript(script: String, fileName: String = "source.js") {
        scope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    controller.jsPackStore.ingest(script, fileName)
                }
                val registry = repository.sourceRegistry()
                registry.remove(source.id)
                check(registry.add(source)) { "failed to register ${source.id}" }
                sourceSettings.setInstalledIds(sourceSettings.installedIds() + source.id)
                controller.browseSearch.clearCatalogCache(source.id)
                dismissAddSourceSheet()
                setActiveSource(source.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update {
                    it.copy(sourceError = e.message?.ifBlank { null } ?: "가져오기에 실패했습니다")
                }
            }
        }
    }

    fun uninstallSource(sourceId: String) {
        val id = sourceId.trim()
        if (id.isEmpty() || id == WorkId.LOCAL_SOURCE) return
        controller.jsPackStore.delete(id)
        controller.networkSourceStore.delete(id)
        repository.sourceRegistry().remove(id)
        sourceSettings.setEnabled(id, false)
        controller.browseSearch.clearCatalogCache(id)
        closeRemoveSourceSheet()
        if (state.value.activeSourceId == id) {
            applyActiveSource(WorkId.LOCAL_SOURCE, loadListing = true)
        } else {
            state.update {
                it.copy(
                    installedSources = repository.installedSources(),
                    sourceRegistry = repository.sourceRegistry(),
                    libraryRoots = sourceSettings.libraryRoots(),
                )
            }
        }
    }

    fun removeActiveSource() {
        val id = state.value.activeSourceId ?: return
        if (id == WorkId.LOCAL_SOURCE) return
        uninstallSource(id)
    }

    fun requestRemoveSource(sourceId: String) {
        if (sourceId == WorkId.LOCAL_SOURCE) return
        uninstallSource(sourceId)
    }

    fun requestRemoveSource(source: ComicSource) {
        uninstallSource(source.id)
    }

    fun getNetworkSourceConfig(sourceId: String): NetworkSourceConfig? {
        val direct = (repository.sourceRegistry().getOrNull(sourceId) as? NetworkLibrarySource)?.config
        if (direct != null) return direct
        return controller.networkSourceStore.all().firstOrNull { it.id == sourceId }
    }

    fun updateNetworkSourceConfig(
        config: NetworkSourceConfig,
        onResult: (Boolean, String?) -> Unit,
    ) {
        scope.launch {
            try {
                val valid = withContext(Dispatchers.IO) {
                    val v = config.validated()
                    NetworkSourceRuntime.remove(v.id)
                    controller.networkSourceStore.save(v)
                    v
                }
                val newSource = controller.networkSourceStore.createSource(valid)
                val registry = repository.sourceRegistry()
                registry.remove(valid.id)
                registry.add(newSource)
                controller.browseSearch.clearCatalogCache(valid.id)
                state.update {
                    it.copy(
                        installedSources = repository.installedSources(),
                        sourceRegistry = repository.sourceRegistry(),
                    )
                }
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message ?: "네트워크 설정 저장에 실패했습니다")
            }
        }
    }

    fun getJsSourceScript(sourceId: String): String? {
        if (sourceId == WorkId.LOCAL_SOURCE || sourceId.startsWith("network-")) return null
        val file = runCatching { controller.jsPackStore.fileFor(sourceId) }.getOrNull() ?: return null
        return if (file.isFile) {
            runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        } else null
    }

    fun updateJsSourceScript(
        sourceId: String,
        newScript: String,
        onResult: (Boolean, String?) -> Unit,
    ) {
        scope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    val bytes = newScript.toByteArray(Charsets.UTF_8)
                    JsPackStore.checkSize(bytes.size)
                    val s = JsComicSource.fromScript(newScript, "$sourceId.js")
                    controller.jsPackStore.copy(sourceId, s.hostApiLevel, bytes)
                    s
                }
                val registry = repository.sourceRegistry()
                registry.remove(sourceId)
                registry.add(source)
                controller.browseSearch.clearCatalogCache(sourceId)
                state.update {
                    it.copy(
                        installedSources = repository.installedSources(),
                        sourceRegistry = repository.sourceRegistry(),
                    )
                }
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message ?: "JS 스크립트 저장에 실패했습니다")
            }
        }
    }

    fun openAddSmb() = openNetworkSourceDialog(NetworkProtocol.SMB)

    fun openAddWebDav() = openNetworkSourceDialog(NetworkProtocol.WEBDAV)

    fun requestAddLocalFolder() {
        state.update { it.copy(pendingLibraryTreePick = true) }
    }

    fun consumeLibraryTreePick() {
        state.update { it.copy(pendingLibraryTreePick = false) }
    }

    fun addLibraryRootPath(key: String, refreshForce: Boolean = false) {
        if (state.value.activeSourceId != WorkId.LOCAL_SOURCE) return
        platform.onLocalFolderAdded(key)
        val current = sourceSettings.libraryRoots()
        if (key !in current) {
            sourceSettings.setLibraryRoots(current + key)
        }
        state.update {
            it.copy(
                libraryRoots = sourceSettings.libraryRoots(),
                pendingLibraryTreePick = false,
            )
        }
        controller.browseSearch.clearCatalogCaches()
        controller.browseSearch.refresh(force = refreshForce)
    }

    fun addLibraryRootFromPicker() {
        if (state.value.activeSourceId != WorkId.LOCAL_SOURCE) return
        val dir = platform.pickLibraryFolder() ?: return
        val path = try {
            dir.canonicalPath
        } catch (_: Exception) {
            dir.absolutePath
        }
        if (!File(path).isDirectory) return
        addLibraryRootPath(path, refreshForce = false)
    }

    fun removeLibraryRoot(key: String, refreshForce: Boolean = false) {
        if (state.value.activeSourceId != WorkId.LOCAL_SOURCE) return
        platform.onLocalFolderRemoved(key)
        sourceSettings.setLibraryRoots(sourceSettings.libraryRoots().filterNot { it == key })
        state.update { it.copy(libraryRoots = sourceSettings.libraryRoots()) }
        controller.browseSearch.clearCatalogCaches()
        controller.browseSearch.refresh(force = refreshForce)
    }

    fun ensureLocalSourceLoaded() {
        val registry = repository.sourceRegistry()
        if (registry.contains(WorkId.LOCAL_SOURCE)) return
        registry.add(
            LocalSource(
                roots = { platform.materializeLocalRoots() },
                index = LibraryScanIndex(platform.localLibraryIndexFile),
            ),
        )
    }

    fun reorderStorageSources(fromIndex: Int, toIndex: Int) {
        val current = state.value.installedSources
        val networkStorage = current.filter { it.resolveSourceType().isStorage && it.id != WorkId.LOCAL_SOURCE }
        if (fromIndex !in networkStorage.indices || toIndex !in networkStorage.indices) return
        val mutable = networkStorage.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)

        val online = current.filterNot { it.resolveSourceType().isStorage }
        val newIds = linkedSetOf<String>().apply {
            add(WorkId.LOCAL_SOURCE)
            mutable.forEach { add(it.id) }
            online.forEach { add(it.id) }
        }
        sourceSettings.setInstalledIds(newIds)
        state.update { it.copy(installedSources = repository.installedSources()) }
    }

    fun reorderOnlineSources(fromIndex: Int, toIndex: Int) {
        val current = state.value.installedSources
        val online = current.filterNot { it.resolveSourceType().isStorage }
        if (fromIndex !in online.indices || toIndex !in online.indices) return
        val mutable = online.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)

        val storage = current.filter { it.resolveSourceType().isStorage && it.id != WorkId.LOCAL_SOURCE }
        val newIds = linkedSetOf<String>().apply {
            add(WorkId.LOCAL_SOURCE)
            storage.forEach { add(it.id) }
            mutable.forEach { add(it.id) }
        }
        sourceSettings.setInstalledIds(newIds)
        state.update { it.copy(installedSources = repository.installedSources()) }
    }

    fun getSourceEpisodeSortOrder(sourceId: String): EpisodeSortOrder =
        sourceSettings.episodeSortOrder(sourceId)

    fun setEpisodeSortOrder(order: EpisodeSortOrder) {
        val targetSourceId = state.value.series?.sourceId
            ?: state.value.activeSourceId
            ?: sourceSettings.activeSourceId()
            ?: WorkId.LOCAL_SOURCE
        setSourceEpisodeSortOrder(targetSourceId, order)
    }

    fun setSourceEpisodeSortOrder(sourceId: String, order: EpisodeSortOrder) {
        sourceSettings.setEpisodeSortOrder(sourceId, order)
        state.update { current ->
            val isCurrentSeriesSource = current.series?.sourceId == sourceId
            val isCurrentActiveSource = current.series == null &&
                (current.activeSourceId ?: sourceSettings.activeSourceId() ?: WorkId.LOCAL_SOURCE) == sourceId
            if (isCurrentSeriesSource || isCurrentActiveSource) {
                current.copy(
                    episodeSortOrder = order,
                    episodes = if (isCurrentSeriesSource) {
                        (current.rawEpisodes.ifEmpty { current.episodes }).sortedWithOrder(order)
                    } else {
                        current.episodes
                    },
                )
            } else {
                current
            }
        }
    }

    fun getSourceProgressDisplayMode(sourceId: String): ProgressDisplayMode =
        sourceSettings.progressDisplayMode(sourceId)

    fun setSourceProgressDisplayMode(sourceId: String, mode: ProgressDisplayMode) {
        sourceSettings.setProgressDisplayMode(sourceId, mode)
        val current = state.value
        scope.launch {
            val updated = repository.refreshProgress(current.items)
            current.tab?.let { tab ->
                controller.browseSearch.updateCachedItems(tab, updated)
            }
            val historyCounts = if (current.historyItems.isNotEmpty()) {
                controller.countIfNeeded(current.historyItems.map { it.workId() }, sourceId)
            } else {
                emptyMap()
            }
            val targetSeries = current.series?.takeIf { it.sourceId == sourceId }
            val seriesCount = if (targetSeries != null) controller.countIfNeeded(targetSeries.workId()) else null
            state.update { curr ->
                val nextCounts = if (targetSeries != null && seriesCount != null) {
                    (curr.readCounts + historyCounts).withCount(targetSeries.workId(), seriesCount)
                } else {
                    curr.readCounts + historyCounts
                }
                curr.copy(items = updated, readCounts = nextCounts)
            }
        }
    }

    fun refreshCurrentListingProgress() {
        val current = state.value
        if (current.activeSourceId == null) return
        scope.launch {
            val updated = repository.refreshProgress(current.items)
            current.tab?.let { tab ->
                controller.browseSearch.updateCachedItems(tab, updated)
            }
            state.update { it.copy(items = updated) }
        }
    }
}
