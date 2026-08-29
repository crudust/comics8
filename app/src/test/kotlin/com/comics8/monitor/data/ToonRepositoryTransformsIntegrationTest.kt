package com.comics8.monitor.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.comics8.core.model.ReadDirection
import com.comics8.core.model.SplitMode
import com.comics8.core.model.ViewMode
import com.comics8.core.network.ToonClient
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.data.local.ReaderSetting
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ToonRepositoryTransformsIntegrationTest {
    @Test
    fun getReaderSettingUsesSharedLegacyFallbacks() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val registry = SourceRegistry()
            val repository = ToonRepository(
                client = ToonClient(sources = { registry }),
                seenDao = database.seenDao(),
                favoriteDao = database.favoriteDao(),
                readerSettingDao = database.readerSettingDao(),
                readEpisodeDao = database.readEpisodeDao(),
                readHistoryDao = database.readHistoryDao(),
                database = database,
                sources = registry,
            )
            val workId = WorkId("eleven", "reader-setting-test")
            database.readerSettingDao().saveSetting(
                ReaderSetting(
                    sourceId = workId.sourceId,
                    toonId = workId.toonId,
                    viewMode = "SINGLE",
                    readDirection = "invalid",
                    splitMode = "invalid",
                    updatedAt = 1L,
                ),
            )

            val setting = repository.getReaderSetting(workId)

            assertThat(setting?.viewMode).isEqualTo(ViewMode.PAGE)
            assertThat(setting?.readDirection).isEqualTo(ReadDirection.RIGHT_TO_LEFT)
            assertThat(setting?.splitMode).isEqualTo(SplitMode.FIT)
        } finally {
            database.close()
        }
    }
}
