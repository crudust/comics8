package com.comics8.monitor.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "seen_toons")
data class SeenToon(
    @PrimaryKey val id: String,
    val title: String,
    val updatedAt: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val notifiedKey: String,
)

@Dao
interface SeenDao {
    @Query("SELECT COUNT(*) FROM seen_toons")
    suspend fun count(): Int

    @Query("SELECT * FROM seen_toons WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<SeenToon>

    @Query("SELECT * FROM seen_toons")
    suspend fun all(): List<SeenToon>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SeenToon>)
}

@Database(entities = [SeenToon::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun seenDao(): SeenDao
}
