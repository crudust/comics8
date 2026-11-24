package com.comics8.shared.db

import android.content.Context
import androidx.room.Room

fun createAndroidAppDatabase(context: Context, name: String = "comics8.db"): AppDatabase =
    Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
        .addMigrations(*AppDatabase.ALL_MIGRATIONS)
        .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3, 4, 5, 6, 7, 8)
        .build()
