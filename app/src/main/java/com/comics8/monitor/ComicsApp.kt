package com.comics8.monitor

import android.app.Application
import androidx.room.Room
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
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
        repository = ToonRepository(
            ToonClient(),
            db.seenDao(),
            db.favoriteDao(),
            db.readerSettingDao(),
            db.readEpisodeDao(),
        )
        RefreshWorker.enqueue(this)
    }

    override fun newImageLoader(): ImageLoader {
        val http = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", ToonClient.USER_AGENT)
                    .header("Referer", "http://103.204.13.68:8904/")
                    .build()
                chain.proceed(request)
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
