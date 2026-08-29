package com.comics8.monitor.data.local

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AppDatabaseRoomMigrationTest {
    @Test
    fun roomAcceptsMigratedV9Database() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "comics8-v9-mig.db"
        context.deleteDatabase(name)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        V9Schema.statements.forEach { db.execSQL(it) }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase.close()
        helper.close()

        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
            )
            .allowMainThreadQueries()
            .build()
        try {
            val favorites = room.favoriteDao().all()
            assertThat(favorites).hasSize(1)
            assertThat(favorites[0].sourceId).isEqualTo("eleven")
            assertThat(favorites[0].id).isEqualTo("123")

            val seen = room.seenDao().all()
            assertThat(seen).hasSize(1)
            assertThat(seen[0].notifiedKey).isEqualTo("eleven:123|999")

            val history = room.readHistoryDao().all()
            assertThat(history).hasSize(1)
            assertThat(history[0].sourceId).isEqualTo("eleven")

            val episodes = room.readEpisodeDao().all()
            assertThat(episodes).hasSize(1)
            val settings = room.readerSettingDao().all()
            assertThat(settings).hasSize(1)
            assertThat(settings[0].splitMode).isEqualTo("FIT")
            val downloads = room.downloadedEpisodeDao().all()
            assertThat(downloads).hasSize(1)
            val tombs = room.tombstoneDao().getSince(0)
            assertThat(tombs).hasSize(1)
            assertThat(tombs[0].entityId).isEqualTo("eleven:456")
        } finally {
            room.close()
        }
    }
}
