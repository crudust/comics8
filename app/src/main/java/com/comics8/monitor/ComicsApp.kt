package com.comics8.monitor

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.comics8.core.parser.ComicSite
import com.comics8.monitor.data.SyncManager
import com.comics8.monitor.data.ToonClient
import com.comics8.monitor.data.ToonRepository
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.work.RefreshWorker
import okhttp3.OkHttpClient

class ComicsApp : Application(), ImageLoaderFactory {
    lateinit var repository: ToonRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, AppDatabase::class.java, "comics8.db")
            .fallbackToDestructiveMigration()
            .build()
        val syncManager = SyncManager(this, db)
        repository = ToonRepository(
            ToonClient(),
            db.seenDao(),
            db.favoriteDao(),
            db.readerSettingDao(),
            db.readEpisodeDao(),
            db.readHistoryDao(),
            database = db,
            syncManager = syncManager,
        )
        RefreshWorker.enqueue(this)
    }

    override fun newImageLoader(): ImageLoader {
        val http = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", ToonClient.USER_AGENT)
                    .header("Referer", ComicSite.ORIGIN)
                    .build()
                var response = chain.proceed(request)
                if (!response.isSuccessful && (response.code == 404 || response.code >= 500)) {
                    val urlStr = request.url.toString()
                    val fallbacks = ComicSite.getFallbackImageUrls(urlStr)
                    for (fallback in fallbacks) {
                        response.close()
                        val retryReq = request.newBuilder().url(fallback).build()
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
                    .directory(cacheDir.resolve("covers"))
                    .maxSizeBytes(80L * 1024L * 1024L)
                    .build()
            }
            .build()
    }
}
