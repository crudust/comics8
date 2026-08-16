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

@Entity(tableName = "favorites")
data class FavoriteToon(
    @PrimaryKey val id: String,
    val title: String,
    val thumbUrl: String,
    val href: String,
    val genre: String,
    val updatedAt: String?,
    val savedAt: Long,
)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY savedAt DESC")
    suspend fun all(): List<FavoriteToon>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("SELECT id FROM favorites")
    suspend fun ids(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FavoriteToon)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun delete(id: String)
}

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

@Entity(tableName = "reader_settings")
data class ReaderSetting(
    @PrimaryKey val toonId: String,
    val viewMode: String,
    val readDirection: String,
    val updatedAt: Long,
)

@Dao
interface ReaderSettingDao {
    @Query("SELECT * FROM reader_settings WHERE toonId = :toonId")
    suspend fun getSetting(toonId: String): ReaderSetting?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSetting(setting: ReaderSetting)
}

@Entity(tableName = "read_episodes", primaryKeys = ["toonId", "wrId"])
data class ReadEpisode(
    val toonId: String,
    val wrId: String,
    val readAt: Long,
)

@Dao
interface ReadEpisodeDao {
    @Query("SELECT * FROM read_episodes WHERE toonId = :toonId")
    suspend fun getReadEpisodes(toonId: String): List<ReadEpisode>

    @Query("SELECT wrId FROM read_episodes WHERE toonId = :toonId")
    suspend fun getReadWrIds(toonId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markRead(item: ReadEpisode)
}

@Database(
    entities = [SeenToon::class, FavoriteToon::class, ReaderSetting::class, ReadEpisode::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun seenDao(): SeenDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun readerSettingDao(): ReaderSettingDao
    abstract fun readEpisodeDao(): ReadEpisodeDao
}
