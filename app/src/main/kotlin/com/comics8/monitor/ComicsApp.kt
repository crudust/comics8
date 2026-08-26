package com.comics8.monitor

import android.app.Application
import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.comics8.core.source.SourceLocator
import com.comics8.core.source.SourcePrefs
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.js.JsPackStore
import com.comics8.core.source.local.CoverThumbCache
import com.comics8.core.source.local.LibraryScanIndex
import com.comics8.core.source.local.LocalSource
import com.comics8.core.source.network.NetworkSourceStore
import com.comics8.core.source.network.NetworkSourceConfig
import com.comics8.core.source.network.loadSources
import com.comics8.core.network.ToonClient
import com.comics8.monitor.data.AndroidSourcePrefs
import com.comics8.monitor.data.DownloadManager
import com.comics8.monitor.data.SyncManager
import com.comics8.monitor.data.ToonRepository
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.data.local.BitmapThumbEncoder
import com.comics8.monitor.data.local.SafLibraryMirror
import com.comics8.monitor.data.local.ZipImageFetcher
import com.comics8.monitor.data.local.NetworkImageFetcher
import com.comics8.monitor.work.RefreshWorker
import java.io.File

class ComicsApp : Application(), ImageLoaderFactory {
    lateinit var repository: ToonRepository
        private set

    lateinit var sourceSettings: AndroidSourcePrefs
        private set

    lateinit var jsPackStore: JsPackStore
        private set

    lateinit var networkSourceStore: NetworkSourceStore
        private set

    lateinit var libraryMirror: SafLibraryMirror
        private set

    lateinit var coverThumbs: CoverThumbCache
        private set

    private lateinit var toonClient: ToonClient
    private lateinit var sourceRegistry: SourceRegistry

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "comics8.db")
            .addMigrations(AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11)
            .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8)
            .build()
        val registry = SourceRegistry()
        sourceRegistry = registry
        val locator = SourceLocator { registry }
        toonClient = ToonClient(ToonClient.defaultClient(File(cacheDir, "http")), sources = locator)
        val client = toonClient
        val prefs = getSharedPreferences("comics_reader_prefs", Context.MODE_PRIVATE)
        val sourcePrefs = AndroidSourcePrefs(prefs).also { it.migrateIfNeeded() }
        sourceSettings = sourcePrefs
        val store = JsPackStore.androidFiles(filesDir)
        jsPackStore = store
        val loaded = store.loadInto(registry)
        val networkStore = NetworkSourceStore(
            File(filesDir, "sources/network-connections.json"),
            File(cacheDir, "library-index/network"),
        )
        networkSourceStore = networkStore
        val networkSources = networkStore.loadSources()
        networkSources.forEach(registry::add)
        val migratedInstalled = sourcePrefs.installedIds().map(NetworkSourceConfig::normalizeId).toSet()
        sourcePrefs.storedActiveRaw()?.let { active ->
            val migrated = NetworkSourceConfig.normalizeId(active)
            if (migrated != active) sourcePrefs.setActiveSourceId(migrated)
        }
        val loadedIds = loaded.map { it.id } + networkSources.map { it.id }
        if (loadedIds.isNotEmpty()) {
            sourcePrefs.setInstalledIds(migratedInstalled + loadedIds)
        }
        libraryMirror = SafLibraryMirror(this)
        coverThumbs = CoverThumbCache(File(cacheDir, "thumbs"), BitmapThumbEncoder)
        registry.add(
            LocalSource(
                roots = { libraryMirror.materialize(sourcePrefs.libraryRoots()) },
                thumbs = coverThumbs,
                index = LibraryScanIndex(File(cacheDir, "library-index/local.json")),
            ),
        )
        val isSourceEnabled: (String) -> Boolean = { id -> sourcePrefs.isEnabled(id) }
        val installedIds: () -> Set<String> = { sourcePrefs.installedIds() }
        val syncManager = SyncManager(this, db, client)
        val downloadManager = DownloadManager(
            this,
            db,
            client,
            isSourceEnabled = isSourceEnabled,
            sources = registry,
            installedIds = installedIds,
        )
        repository = ToonRepository(
            client = client,
            seenDao = db.seenDao(),
            favoriteDao = db.favoriteDao(),
            readerSettingDao = db.readerSettingDao(),
            readEpisodeDao = db.readEpisodeDao(),
            readHistoryDao = db.readHistoryDao(),
            database = db,
            sourceSettings = sourcePrefs,
            syncManager = syncManager,
            downloadManager = downloadManager,
            sources = registry,
            isSourceEnabled = isSourceEnabled,
            installedIds = installedIds,
        )
        repository.applyPreferences { id ->
            prefs.getString(SourcePrefs.languageKey(id), null)
        }
        RefreshWorker.updateSchedule(
            this,
            enabled = sourcePrefs.globalNotificationEnabled(),
            intervalMinutes = sourcePrefs.notificationIntervalMinutes(),
        )
    }

    override fun newImageLoader(): ImageLoader {
        val http = ToonClient.newImageHttpClient(toonClient.httpClient, sourceRegistry)
        return ImageLoader.Builder(this)
            .okHttpClient(http)
            .components {
                add(ZipImageFetcher.Factory())
                add(NetworkImageFetcher.Factory(coverThumbs))
            }
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder(this).maxSizePercent(0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(200L * 1024L * 1024L)
                    .build()
            }
            .build()
    }
}
