package com.comics8.desktop.data

import com.comics8.core.data.SharedBackupManager
import kotlinx.coroutines.runBlocking

object DesktopBackupManager {
    fun createBackupJson(database: DesktopDatabase): String = runBlocking {
        SharedBackupManager.createBackupJson(DesktopComicLocalStore(database))
    }

    fun restoreBackupJson(database: DesktopDatabase, jsonString: String): DesktopBackupResult = runBlocking {
        SharedBackupManager.restoreBackupJson(DesktopComicLocalStore(database), jsonString)
    }

    fun getStats(database: DesktopDatabase): DesktopBackupStats = database.getBackupStats()
}
