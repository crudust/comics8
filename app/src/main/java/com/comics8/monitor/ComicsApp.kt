package com.comics8.monitor

import android.app.Application
import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.comics8.core.network.ImageFallbacks
import com.comics8.core.network.ImageReferer
import com.comics8.core.source.BuiltInSources
import com.comics8.core.source.SourceLocator
import com.comics8.core.source.SourcePrefs
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.core.network.ToonClient
import com.comics8.monitor.data.DownloadManager
import com.comics8.monitor.data.SyncManager
import com.comics8.monitor.data.ToonRepository
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.work.RefreshWorker
import java.io.File

class ComicsApp : Application(), ImageLoaderFactory {
    lateinit var repository: ToonRepository
        private set

    private lateinit var toonClient: ToonClient
    private lateinit var sourceRegistry: SourceRegistry

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "comics8.db")
            .addMigrations(AppDatabase.MIGRATION_9_10)
            .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5, 6, 7, 8)
            .build()
        val registry = SourceRegistry(BuiltInSources.all())
        sourceRegistry = registry
        val locator = SourceLocator { registry }
        toonClient = ToonClient(ToonClient.defaultClient(File(cacheDir, "http")), sources = locator)
        val client = toonClient
        val prefs = getSharedPreferences("comics_reader_prefs", Context.MODE_PRIVATE)
        val isSourceEnabled: (String) -> Boolean = { id ->
            if (id == WorkId.DEFAULT_SOURCE || id.isBlank()) {
                true
            } else {
                val defaultOn = registry.getOrNull(id)?.enabledByDefault ?: false
                prefs.getBoolean(SourcePrefs.enabledKey(id), defaultOn)
            }
        }
        val syncManager = SyncManager(this, db, client)
        val downloadManager = DownloadManager(
            this,
            db,
            client,
            isSourceEnabled = isSourceEnabled,
            sources = registry,
        )
        repository = ToonRepository(
            client = client,
            seenDao = db.seenDao(),
            favoriteDao = db.favoriteDao(),
            readerSettingDao = db.readerSettingDao(),
            readEpisodeDao = db.readEpisodeDao(),
            readHistoryDao = db.readHistoryDao(),
            database = db,
            syncManager = syncManager,
            downloadManager = downloadManager,
            sources = registry,
            isSourceEnabled = isSourceEnabled,
        )
        repository.applyPreferences { id ->
            prefs.getString(SourcePrefs.languageKey(id), null)
        }
        RefreshWorker.enqueue(this)
    }

    override fun newImageLoader(): ImageLoader {
        val registry = sourceRegistry
        val http = toonClient.httpClient.newBuilder()
            .addInterceptor { chain ->
                val originalUrl = chain.request().url.toString()
                val request = chain.request().newBuilder()
                    .header("User-Agent", ToonClient.USER_AGENT)
                    .header("Referer", ImageReferer.forUrl(originalUrl, registry))
                    .build()
                var response = chain.proceed(request)
                if (!response.isSuccessful && (response.code == 404 || response.code >= 500)) {
                    val urlStr = request.url.toString()
                    val fallbacks = ImageFallbacks.forUrl(urlStr, registry)
                    for (fallback in fallbacks) {
                        response.close()
                        val retryReq = request.newBuilder()
                            .url(fallback)
                            .header("Referer", ImageReferer.forUrl(fallback, registry))
                            .build()
                        val retryResp = try {
                            chain.proceed(retryReq)
                        } catch (_: Exception) {
                            null
                        }
                        if (retryResp != null && retryResp.isSuccessful) {
                            return@addInterceptor retryResp
                        }
                        if (retryResp != null) {
                            response = retryResp
                        }
                    }
                }
                response
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(http)
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
