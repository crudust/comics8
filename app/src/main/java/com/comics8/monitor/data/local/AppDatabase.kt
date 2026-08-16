package com.comics8.monitor.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Index
import androidx.room.RoomDatabase

@Entity(
    tableName = "seen_toons",
)
data class SeenToon(
    @PrimaryKey val id: String,
    val title: String,
    val updatedAt: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val notifiedKey: String,
)

@Entity(
    tableName = "favorites",
    indices = [Index("savedAt")],
)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FavoriteToon>)

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

    @Query("SELECT * FROM reader_settings")
    suspend fun all(): List<ReaderSetting>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSetting(setting: ReaderSetting)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAll(settings: List<ReaderSetting>)
}

@Entity(tableName = "read_episodes", primaryKeys = ["toonId", "wrId"])
data class ReadEpisode(
    val toonId: String,
    val wrId: String,
    val readAt: Long,
    val lastPage: Int = 0,
)

@Dao
interface ReadEpisodeDao {
    @Query("SELECT * FROM read_episodes WHERE toonId = :toonId")
    suspend fun getReadEpisodes(toonId: String): List<ReadEpisode>

    @Query("SELECT * FROM read_episodes WHERE toonId = :toonId AND wrId = :wrId")
    suspend fun getReadEpisode(toonId: String, wrId: String): ReadEpisode?

    @Query("SELECT * FROM read_episodes")
    suspend fun all(): List<ReadEpisode>

    @Query("SELECT wrId FROM read_episodes WHERE toonId = :toonId")
    suspend fun getReadWrIds(toonId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markRead(item: ReadEpisode)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markReadAll(items: List<ReadEpisode>)

    @Query("UPDATE read_episodes SET lastPage = :page WHERE toonId = :toonId AND wrId = :wrId")
    suspend fun updateLastPage(toonId: String, wrId: String, page: Int)
}

@Entity(
    tableName = "read_history",
    indices = [Index("lastReadAt")],
)
data class ReadHistory(
    @PrimaryKey val toonId: String,
    val toonTitle: String,
    val toonThumbUrl: String,
    val toonHref: String,
    val lastWrId: String,
    val lastEpisodeTitle: String,
    val lastEpisodeHref: String,
    val lastReadOrder: Int,
    val totalEpisodes: Int,
    val lastReadAt: Long,
    val nextWrId: String? = null,
    val nextEpisodeTitle: String? = null,
    val nextEpisodeHref: String? = null,
    val hasNew: Boolean = false,
)

@Dao
interface ReadHistoryDao {
    @Query("SELECT * FROM read_history ORDER BY lastReadAt DESC")
    suspend fun all(): List<ReadHistory>

    @Query("SELECT * FROM read_history WHERE toonId = :toonId")
    suspend fun get(toonId: String): ReadHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ReadHistory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ReadHistory>)

    @Query("DELETE FROM read_history WHERE toonId = :toonId")
    suspend fun delete(toonId: String)

    @Query("DELETE FROM read_history")
    suspend fun clearAll()

    @Query("UPDATE read_history SET totalEpisodes = totalEpisodes + :amount, hasNew = 1 WHERE toonId = :toonId")
    suspend fun incrementTotal(toonId: String, amount: Int = 1)
}

@Database(
    entities = [
        SeenToon::class,
        FavoriteToon::class,
        ReaderSetting::class,
        ReadEpisode::class,
        ReadHistory::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun seenDao(): SeenDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun readerSettingDao(): ReaderSettingDao
    abstract fun readEpisodeDao(): ReadEpisodeDao
    abstract fun readHistoryDao(): ReadHistoryDao
}
