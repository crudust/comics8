package com.comics8.shared.db

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import java.io.File

fun defaultDesktopDbFile(): File = File(System.getProperty("user.home"), ".comics8/comics8.db")

fun openDesktopDatabase(dbFile: File = defaultDesktopDbFile()): AppDatabase {
    dbFile.parentFile?.mkdirs()
    DesktopLegacyDbUpgrader.upgradeIfNeeded(dbFile)
    return Room.databaseBuilder<AppDatabase>(name = dbFile.absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addMigrations(*AppDatabase.ALL_MIGRATIONS)
        .addCallback(
            object : RoomDatabase.Callback() {
                override fun onOpen(connection: SQLiteConnection) {
                    connection.execSQL("PRAGMA busy_timeout = 5000")
                }
            },
        )
        .build()
}

fun openInMemoryDesktopDatabase(): AppDatabase =
    Room.inMemoryDatabaseBuilder<AppDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
