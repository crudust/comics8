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
    @Query("SELECT * FROM favorites ORDER BY COALESCE(updatedAt, '') DESC, savedAt DESC")
    suspend fun all(): List<FavoriteToon>

    @Query("SELECT * FROM favorites WHERE savedAt > :since ORDER BY savedAt DESC")
    suspend fun getSince(since: Long): List<FavoriteToon>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Query("SELECT id FROM favorites")
    suspend fun ids(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FavoriteToon)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FavoriteToon>)

    @Query("UPDATE favorites SET updatedAt = :updatedAt WHERE id = :id AND (:updatedAt != '' AND (updatedAt IS NULL OR updatedAt < :updatedAt))")
    suspend fun updateUpdatedAt(id: String, updatedAt: String)

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

    @Query("SELECT * FROM reader_settings WHERE updatedAt > :since")
    suspend fun getSince(since: Long): List<ReaderSetting>

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

    @Query("SELECT * FROM read_episodes WHERE readAt > :since")
    suspend fun getSince(since: Long): List<ReadEpisode>

    @Query("SELECT wrId FROM read_episodes WHERE toonId = :toonId")
    suspend fun getReadWrIds(toonId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markRead(item: ReadEpisode)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markReadAll(items: List<ReadEpisode>)

    @Query("UPDATE read_episodes SET lastPage = :page, readAt = :readAt WHERE toonId = :toonId AND wrId = :wrId")
    suspend fun updateLastPage(toonId: String, wrId: String, page: Int, readAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM read_episodes WHERE toonId = :toonId")
    suspend fun deleteByToonId(toonId: String)
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

    @Query("SELECT * FROM read_history WHERE lastReadAt > :since ORDER BY lastReadAt DESC")
    suspend fun getSince(since: Long): List<ReadHistory>

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
}

@Entity(tableName = "tombstones", primaryKeys = ["entityType", "entityId"])
data class Tombstone(
    val entityType: String,
    val entityId: String,
    val deletedAt: Long,
)

@Dao
interface TombstoneDao {
    @Query("SELECT * FROM tombstones WHERE deletedAt > :since")
    suspend fun getSince(since: Long): List<Tombstone>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: Tombstone)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Tombstone>)

    @Query("DELETE FROM tombstones WHERE entityType = :type AND entityId = :id")
    suspend fun delete(type: String, id: String)

    @Query("DELETE FROM tombstones WHERE deletedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}

@Entity(
    tableName = "downloaded_episodes",
    primaryKeys = ["toonId", "wrId"],
    indices = [Index("toonId"), Index("downloadedAt")],
)
data class DownloadedEpisode(
    val toonId: String,
    val wrId: String,
    val toonTitle: String,
    val toonThumbUrl: String,
    val toonHref: String,
    val episodeTitle: String,
    val episodeHref: String,
    val imageCount: Int,
    val totalBytes: Long,
    val downloadedAt: Long,
    val localDirPath: String,
)

@Dao
interface DownloadedEpisodeDao {
    @Query("SELECT * FROM downloaded_episodes ORDER BY downloadedAt DESC")
    suspend fun all(): List<DownloadedEpisode>

    @Query("SELECT * FROM downloaded_episodes WHERE toonId = :toonId ORDER BY downloadedAt DESC")
    suspend fun byToon(toonId: String): List<DownloadedEpisode>

    @Query("SELECT * FROM downloaded_episodes WHERE toonId = :toonId AND wrId = :wrId")
    suspend fun get(toonId: String, wrId: String): DownloadedEpisode?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadedEpisode)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DownloadedEpisode>)

    @Query("DELETE FROM downloaded_episodes WHERE toonId = :toonId AND wrId = :wrId")
    suspend fun delete(toonId: String, wrId: String)

    @Query("DELETE FROM downloaded_episodes WHERE toonId = :toonId")
    suspend fun deleteByToon(toonId: String)

    @Query("SELECT DISTINCT toonId FROM downloaded_episodes")
    suspend fun getDownloadedToonIds(): List<String>
}

@Database(
    entities = [
        SeenToon::class,
        FavoriteToon::class,
        ReaderSetting::class,
        ReadEpisode::class,
        ReadHistory::class,
        Tombstone::class,
        DownloadedEpisode::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun seenDao(): SeenDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun readerSettingDao(): ReaderSettingDao
    abstract fun readEpisodeDao(): ReadEpisodeDao
    abstract fun readHistoryDao(): ReadHistoryDao
    abstract fun tombstoneDao(): TombstoneDao
    abstract fun downloadedEpisodeDao(): DownloadedEpisodeDao
}
