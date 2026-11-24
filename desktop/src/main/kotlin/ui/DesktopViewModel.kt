package com.comics8.desktop.ui

import com.comics8.core.app.PlatformHost
import com.comics8.core.app.SharedAppController
import com.comics8.core.i18n.AppStrings
import com.comics8.core.image.ImageCacheRole
import com.comics8.core.model.AppUpdateState
import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.ToonItem
import com.comics8.core.model.ViewMode
import com.comics8.core.source.LocalImageUri
import com.comics8.core.source.SourceSettings
import com.comics8.core.source.js.JsPackStore
import com.comics8.core.source.local.ComicArchive
import com.comics8.core.source.local.LibraryScanner
import com.comics8.core.source.local.RarArchive
import com.comics8.core.source.local.ZipImageUri
import com.comics8.core.source.network.NetworkSourceStore
import com.comics8.core.storage.KeyValuePrefs
import com.comics8.core.sync.AppUpdateChecker
import com.comics8.desktop.DesktopVersion
import com.comics8.desktop.ExternalTargetResolver
import com.comics8.desktop.data.DesktopSourcePrefs
import com.comics8.desktop.data.DesktopToonRepository
import com.comics8.desktop.data.DesktopUpdateManager
import com.comics8.desktop.ui.util.DesktopFolderPicker
import com.comics8.desktop.ui.util.DesktopCoil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.prefs.Preferences

class DesktopViewModel(
    val repository: DesktopToonRepository,
    jsPackStore: JsPackStore,
    networkSourceStore: NetworkSourceStore,
    private val pickLibraryFolder: () -> File? = { DesktopFolderPicker.pickDirectory() },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs: Preferences = Preferences.userRoot().node("com.comics8.desktop")

    private val platform = object : PlatformHost {
        override val prefs: KeyValuePrefs = JavaPreferencesKeyValuePrefs(this@DesktopViewModel.prefs)
        override val sourceSettings: SourceSettings = DesktopSourcePrefs
        override val localLibraryIndexFile: File =
            File(System.getProperty("user.home"), ".comics8/cache/library-index/local.json")

        override fun materializeLocalRoots(): List<File> =
            DesktopSourcePrefs.libraryRoots().map { File(it) }

        override fun clearImageMemory(role: ImageCacheRole) {
            DesktopCoil.clear(role)
        }

        override fun cancelPendingImagePreviews() {
            DesktopCoil.cancelPendingPreviews()
        }

        override fun clearImageFailures() {
            DesktopCoil.clearFailures()
        }

        override fun preloadReaderImages(urls: List<String>) {
            scope.launch(Dispatchers.IO) {
                DesktopCoil.preload(ImageCacheRole.READER, urls)
            }
        }

        override fun evictReaderImage(url: String) {
            DesktopCoil.evict(ImageCacheRole.READER, url)
        }

        override fun pickLibraryFolder(): File? = this@DesktopViewModel.pickLibraryFolder()

        override val appVersionName: String = DesktopVersion.VERSION_NAME
        override val appVersionCode: Int = DesktopVersion.VERSION_CODE

        override suspend fun checkForAppUpdate(serverUrl: String, strings: AppStrings): AppUpdateState =
            withContext(Dispatchers.IO) {
                AppUpdateChecker.checkDesktopUpdate(
                    currentVersionName = appVersionName,
                    currentVersionCode = appVersionCode,
                    serverUrl = serverUrl,
                    strings = strings,
                )
            }

        override suspend fun downloadAndInstallUpdate(
            downloadUrl: String,
            onProgress: (Float) -> Unit,
        ): Result<Unit> = DesktopUpdateManager.downloadAndApplyUpdate(downloadUrl, onProgress)

        override val autoSwitchWebtoonInReader: Boolean = true
        override val markSeenOnFavoriteTab: Boolean = false
        override val initialLoadWithForce: Boolean = false
        override val collapseSearchClearsActiveSearch: Boolean = false
        override val refreshReaderUsesAsRefresh: Boolean = false
    }

    val controller: SharedAppController = SharedAppController(
        scope = scope,
        repository = repository,
        jsPackStore = jsPackStore,
        networkSourceStore = networkSourceStore,
        platform = platform,
    )

    val state: StateFlow<DesktopUiState> = controller.state

    override fun close() {
        controller.close()
        scope.cancel()
    }

    fun goBack(): Boolean = controller.goBack()
    fun goForward(): Boolean = controller.goForward()
    fun onImportedPacksReady() = controller.onImportedPacksReady()

    fun startUpdate() = controller.startUpdate()

    fun closeAddSourceSheet() = controller.closeAddSourceSheet()

    fun importJsFile(file: File) {
        scope.launch {
            try {
                val script = withContext(Dispatchers.IO) {
                    if (file.length() > JsPackStore.MAX_SCRIPT_BYTES) {
                        throw IllegalArgumentException("파일이 너무 큽니다")
                    }
                    file.inputStream().use { JsPackStore.readCapped(it) }
                }
                controller.importJsScript(script, file.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                controller.setSourceError(e.message?.ifBlank { null } ?: "파일을 읽을 수 없습니다")
            }
        }
    }

    fun addLibraryRoot() = controller.addLibraryRootFromPicker()
    fun removeLibraryRoot(path: String) = controller.removeLibraryRoot(path)

    fun refresh() = controller.refresh(force = false)

    fun openSeries(item: ToonItem) = controller.openSeries(item)
    fun nextPage() = controller.nextPage()
    fun prevPage() = controller.prevPage()
    fun nextEpisodePage() = controller.nextEpisodePage()
    fun prevEpisodePage() = controller.prevEpisodePage()
    fun closeSeries() = controller.closeSeries()

    fun exportBackup(file: File, onResult: (Boolean, String) -> Unit) =
        controller.exportBackupWithWriter({ json -> file.writeText(json, Charsets.UTF_8) }, onResult)

    fun importBackup(file: File, onResult: (Boolean, String) -> Unit) =
        controller.importBackupWithReader({ file.readText(Charsets.UTF_8) }, onResult)

    fun openEpisode(episode: EpisodeItem) = controller.openEpisode(episode)

    fun openExternalArchive(file: File) {
        val target = try {
            file.toPath().toAbsolutePath().normalize().toFile()
        } catch (_: Exception) {
            file.absoluteFile
        }
        val resolved = ExternalTargetResolver.resolve(target)
        if (resolved == null) {
            val exists = target.exists() || try {
                Files.exists(target.toPath())
            } catch (_: Exception) {
                false
            }
            controller.setSourceError(
                if (!exists) {
                    "파일 또는 폴더를 찾을 수 없습니다:\n${target.path}"
                } else {
                    val supported = if (RarArchive.isSupported) "ZIP, CBZ, RAR, CBR" else "ZIP, CBZ"
                    "지원하는 만화 파일($supported)이나 이미지를 찾을 수 없습니다:\n${target.name}"
                },
            )
            return
        }

        when (resolved) {
            is ExternalTargetResolver.Target.Zip -> openExternalZip(resolved.file)
            is ExternalTargetResolver.Target.Folder -> openExternalFolder(resolved.dir, resolved.initialFile)
        }
    }

    private fun openExternalZip(archive: File) {
        val title = archive.name.substringBeforeLast('.', archive.name)
        val episode = EpisodeItem(
            wrId = archive.path,
            title = title,
            date = null,
            thumbUrl = null,
            href = archive.toURI().toString(),
        )
        controller.openExternalReaderSession(episode) {
            withContext(Dispatchers.IO) {
                ComicArchive.open(archive).use { comic ->
                    comic.imageEntries().map { entry -> ZipImageUri.encode(archive, entry) }
                }
            }
        }
    }

    private fun openExternalFolder(dir: File, initialFile: File? = null) {
        val title = dir.name
        val episode = EpisodeItem(
            wrId = dir.path,
            title = title,
            date = null,
            thumbUrl = null,
            href = LocalImageUri.fromFile(dir),
        )
        controller.openExternalReaderSession(episode) {
            withContext(Dispatchers.IO) {
                val imageFiles = LibraryScanner().listFolderImages(dir)
                val images = imageFiles.map { LocalImageUri.fromFile(it) }
                if (initialFile != null) {
                    val initialName = initialFile.name
                    val idx = imageFiles.indexOfFirst { it.name.equals(initialName, ignoreCase = true) }
                    if (idx > 0) {
                        controller.updateState { curr ->
                            curr.copy(currentEpisode = curr.currentEpisode?.copy(lastReadPage = idx))
                        }
                    }
                }
                images
            }
        }
    }

    fun setViewMode(mode: ViewMode) = controller.setViewMode(mode)
    fun recordImageAspectRatio(index: Int, width: Int, height: Int) =
        controller.recordImageAspectRatio(index, width, height)
    fun updateViewportSize(widthDp: Int, heightDp: Int) = controller.updateViewportSize(widthDp, heightDp)
    fun toggleFullscreen() = controller.toggleFullscreen()
}

private class JavaPreferencesKeyValuePrefs(
    private val prefs: Preferences,
) : KeyValuePrefs {
    override fun contains(key: String): Boolean = try {
        key in prefs.keys()
    } catch (_: Exception) {
        false
    }

    override fun getString(key: String, defaultValue: String?): String? =
        prefs.get(key, defaultValue)

    override fun putString(key: String, value: String?) {
        if (value == null) prefs.remove(key) else prefs.put(key, value)
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.putBoolean(key, value)
    }

    override fun getInt(key: String, defaultValue: Int): Int =
        prefs.getInt(key, defaultValue)

    override fun putInt(key: String, value: Int) {
        prefs.putInt(key, value)
    }

    override fun getLong(key: String, defaultValue: Long): Long =
        prefs.getLong(key, defaultValue)

    override fun putLong(key: String, value: Long) {
        prefs.putLong(key, value)
    }
}
