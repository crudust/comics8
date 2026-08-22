package com.comics8.monitor.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.comics8.core.source.WorkId

@Entity(
    tableName = "seen_toons",
    primaryKeys = ["sourceId", "toonId"],
)
data class SeenToon(
    val sourceId: String = WorkId.DEFAULT_SOURCE,
    @ColumnInfo(name = "toonId") val id: String,
    val title: String,
    val updatedAt: String?,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val notifiedKey: String,
) {
    fun workId(): WorkId = WorkId(sourceId, id)
}

@Entity(
    tableName = "favorites",
    primaryKeys = ["sourceId", "toonId"],
    indices = [Index("savedAt")],
)
data class FavoriteToon(
    val sourceId: String = WorkId.DEFAULT_SOURCE,
    @ColumnInfo(name = "toonId") val id: String,
    val title: String,
    val thumbUrl: String,
    val href: String,
    val genre: String,
    val updatedAt: String?,
    val savedAt: Long,
) {
    fun workId(): WorkId = WorkId(sourceId, id)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY COALESCE(updatedAt, '') DESC, savedAt DESC")
    suspend fun all(): List<FavoriteToon>

    @Query("SELECT * FROM favorites WHERE toonId IN (:toonIds)")
    suspend fun byToonIds(toonIds: List<String>): List<FavoriteToon>

    @Query("SELECT * FROM favorites WHERE savedAt > :since ORDER BY savedAt DESC")
    suspend fun getSince(since: Long): List<FavoriteToon>

    @Query("SELECT * FROM favorites WHERE sourceId = :sourceId AND toonId = :toonId")
    suspend fun get(sourceId: String, toonId: String): FavoriteToon?

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE sourceId = :sourceId AND toonId = :toonId)")
    suspend fun exists(sourceId: String, toonId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FavoriteToon)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FavoriteToon>)

    @Query("UPDATE favorites SET updatedAt = :updatedAt WHERE sourceId = :sourceId AND toonId = :toonId")
    suspend fun updateUpdatedAt(sourceId: String, toonId: String, updatedAt: String)

    @Query("DELETE FROM favorites WHERE sourceId = :sourceId AND toonId = :toonId")
    suspend fun delete(sourceId: String, toonId: String)
}

suspend fun FavoriteDao.get(workId: WorkId): FavoriteToon? = get(workId.sourceId, workId.toonId)

suspend fun FavoriteDao.exists(workId: WorkId): Boolean = exists(workId.sourceId, workId.toonId)

suspend fun FavoriteDao.updateUpdatedAt(workId: WorkId, updatedAt: String) =
    updateUpdatedAt(workId.sourceId, workId.toonId, updatedAt)

suspend fun FavoriteDao.delete(workId: WorkId) = delete(workId.sourceId, workId.toonId)

@Dao
interface SeenDao {
    @Query("SELECT COUNT(*) FROM seen_toons")
    suspend fun count(): Int

    @Query("SELECT * FROM seen_toons WHERE toonId IN (:toonIds)")
    suspend fun byToonIds(toonIds: List<String>): List<SeenToon>

    @Query("SELECT * FROM seen_toons")
    suspend fun all(): List<SeenToon>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SeenToon>)
}

@Entity(
    tableName = "reader_settings",
    primaryKeys = ["sourceId", "toonId"],
)
data class ReaderSetting(
    val sourceId: String = WorkId.DEFAULT_SOURCE,
    val toonId: String,
    val viewMode: String,
    val readDirection: String,
    val splitMode: String = "FIT",
    val updatedAt: Long,
) {
    fun workId(): WorkId = WorkId(sourceId, toonId)
}

@Dao
interface ReaderSettingDao {
    @Query("SELECT * FROM reader_settings WHERE sourceId = :sourceId AND toonId = :toonId")
    suspend fun getSetting(sourceId: String, toonId: String): ReaderSetting?

    @Query("SELECT * FROM reader_settings")
    suspend fun all(): List<ReaderSetting>

    @Query("SELECT * FROM reader_settings WHERE updatedAt > :since")
    suspend fun getSince(since: Long): List<ReaderSetting>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSetting(setting: ReaderSetting)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAll(settings: List<ReaderSetting>)
}

suspend fun ReaderSettingDao.getSetting(workId: WorkId): ReaderSetting? =
    getSetting(workId.sourceId, workId.toonId)

@Entity(
    tableName = "read_episodes",
    primaryKeys = ["sourceId", "toonId", "wrId"],
)
data class ReadEpisode(
    val sourceId: String = WorkId.DEFAULT_SOURCE,
    val toonId: String,
    val wrId: String,
    val readAt: Long,
    val lastPage: Int = 0,
) {
    fun workId(): WorkId = WorkId(sourceId, toonId)
}

@Dao
interface ReadEpisodeDao {
    @Query("SELECT * FROM read_episodes WHERE sourceId = :sourceId AND toonId = :toonId")
    suspend fun getReadEpisodes(sourceId: String, toonId: String): List<ReadEpisode>

    @Query("SELECT * FROM read_episodes WHERE sourceId = :sourceId AND toonId = :toonId AND wrId = :wrId")
    suspend fun getReadEpisode(sourceId: String, toonId: String, wrId: String): ReadEpisode?

    @Query("SELECT * FROM read_episodes")
    suspend fun all(): List<ReadEpisode>

    @Query("SELECT * FROM read_episodes WHERE readAt > :since")
    suspend fun getSince(since: Long): List<ReadEpisode>

    @Query("SELECT wrId FROM read_episodes WHERE sourceId = :sourceId AND toonId = :toonId")
    suspend fun getReadWrIds(sourceId: String, toonId: String): List<String>

    @Query("SELECT COUNT(*) FROM read_episodes WHERE sourceId = :sourceId AND toonId = :toonId")
    suspend fun countByToon(sourceId: String, toonId: String): Int

    @Query(
        """
        SELECT sourceId AS sourceId, toonId AS toonId, COUNT(*) AS readCount
        FROM read_episodes
        WHERE toonId IN (:toonIds)
        GROUP BY sourceId, toonId
        """,
    )
    suspend fun countsByToonIds(toonIds: List<String>): List<WorkReadCount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markRead(item: ReadEpisode)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markReadAll(items: List<ReadEpisode>)

    @Query("UPDATE read_episodes SET lastPage = :page, readAt = :readAt WHERE sourceId = :sourceId AND toonId = :toonId AND wrId = :wrId")
    suspend fun updateLastPage(
        sourceId: String,
        toonId: String,
        wrId: String,
        page: Int,
        readAt: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM read_episodes WHERE sourceId = :sourceId AND toonId = :toonId")
    suspend fun deleteByToonId(sourceId: String, toonId: String)
}

data class WorkReadCount(
    val sourceId: String,
    val toonId: String,
    val readCount: Int,
) {
    fun storageKey(): String = WorkId(sourceId, toonId).storageKey()
}

suspend fun ReadEpisodeDao.getReadEpisodes(workId: WorkId): List<ReadEpisode> =
    getReadEpisodes(workId.sourceId, workId.toonId)

suspend fun ReadEpisodeDao.countByToon(workId: WorkId): Int =
    countByToon(workId.sourceId, workId.toonId)

suspend fun ReadEpisodeDao.getReadEpisode(workId: WorkId, wrId: String): ReadEpisode? =
    getReadEpisode(workId.sourceId, workId.toonId, wrId)

suspend fun ReadEpisodeDao.updateLastPage(workId: WorkId, wrId: String, page: Int, readAt: Long = System.currentTimeMillis()) =
    updateLastPage(workId.sourceId, workId.toonId, wrId, page, readAt)

suspend fun ReadEpisodeDao.deleteByToonId(workId: WorkId) =
    deleteByToonId(workId.sourceId, workId.toonId)

@Entity(
    tableName = "read_history",
    primaryKeys = ["sourceId", "toonId"],
    indices = [Index("lastReadAt")],
)
data class ReadHistory(
    val sourceId: String = WorkId.DEFAULT_SOURCE,
    val toonId: String,
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
) {
    fun workId(): WorkId = WorkId(sourceId, toonId)
}

@Dao
interface ReadHistoryDao {
    @Query("SELECT * FROM read_history ORDER BY lastReadAt DESC")
    suspend fun all(): List<ReadHistory>

    @Query("SELECT * FROM read_history WHERE toonId IN (:toonIds)")
    suspend fun byToonIds(toonIds: List<String>): List<ReadHistory>

    @Query("SELECT * FROM read_history WHERE sourceId = :sourceId ORDER BY lastReadAt DESC")
    suspend fun bySource(sourceId: String): List<ReadHistory>

    @Query("SELECT * FROM read_history WHERE lastReadAt > :since ORDER BY lastReadAt DESC")
    suspend fun getSince(since: Long): List<ReadHistory>

    @Query("SELECT * FROM read_history WHERE sourceId = :sourceId AND toonId = :toonId")
    suspend fun get(sourceId: String, toonId: String): ReadHistory?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ReadHistory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ReadHistory>)

    @Query("DELETE FROM read_history WHERE sourceId = :sourceId AND toonId = :toonId")
    suspend fun delete(sourceId: String, toonId: String)

    @Query("DELETE FROM read_history WHERE sourceId = :sourceId")
    suspend fun clearBySource(sourceId: String)

    @Query("DELETE FROM read_history")
    suspend fun clearAll()
}

suspend fun ReadHistoryDao.get(workId: WorkId): ReadHistory? = get(workId.sourceId, workId.toonId)

suspend fun ReadHistoryDao.delete(workId: WorkId) = delete(workId.sourceId, workId.toonId)

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
    primaryKeys = ["sourceId", "toonId", "wrId"],
    indices = [Index("toonId"), Index("downloadedAt")],
)
data class DownloadedEpisode(
    val sourceId: String = WorkId.DEFAULT_SOURCE,
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
) {
    fun workId(): WorkId = WorkId(sourceId, toonId)
}

@Dao
interface DownloadedEpisodeDao {
    @Query("SELECT * FROM downloaded_episodes ORDER BY downloadedAt DESC")
    suspend fun all(): List<DownloadedEpisode>

    @Query("SELECT * FROM downloaded_episodes WHERE sourceId = :sourceId ORDER BY downloadedAt DESC")
    suspend fun bySource(sourceId: String): List<DownloadedEpisode>

    @Query("SELECT * FROM downloaded_episodes WHERE sourceId = :sourceId AND toonId = :toonId ORDER BY downloadedAt DESC")
    suspend fun byToon(sourceId: String, toonId: String): List<DownloadedEpisode>

    @Query("SELECT * FROM downloaded_episodes WHERE sourceId = :sourceId AND toonId = :toonId AND wrId = :wrId")
    suspend fun get(sourceId: String, toonId: String, wrId: String): DownloadedEpisode?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadedEpisode)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<DownloadedEpisode>)

    @Query("DELETE FROM downloaded_episodes WHERE sourceId = :sourceId AND toonId = :toonId AND wrId = :wrId")
    suspend fun delete(sourceId: String, toonId: String, wrId: String)

    @Query("DELETE FROM downloaded_episodes WHERE sourceId = :sourceId AND toonId = :toonId")
    suspend fun deleteByToon(sourceId: String, toonId: String)
}

suspend fun DownloadedEpisodeDao.byToon(workId: WorkId): List<DownloadedEpisode> =
    byToon(workId.sourceId, workId.toonId)

suspend fun DownloadedEpisodeDao.get(workId: WorkId, wrId: String): DownloadedEpisode? =
    get(workId.sourceId, workId.toonId, wrId)

suspend fun DownloadedEpisodeDao.delete(workId: WorkId, wrId: String) =
    delete(workId.sourceId, workId.toonId, wrId)

suspend fun DownloadedEpisodeDao.deleteByToon(workId: WorkId) =
    deleteByToon(workId.sourceId, workId.toonId)

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
    version = 11,
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

    companion object {
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                SchemaV10.statements().forEach { db.execSQL(it) }
            }
        }
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reader_settings ADD COLUMN splitMode TEXT NOT NULL DEFAULT 'FIT'")
            }
        }
    }
}

object SchemaV10 {
    const val DEFAULT_SOURCE = WorkId.DEFAULT_SOURCE

    fun statements(): List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS favorites_new (
            sourceId TEXT NOT NULL,
            toonId TEXT NOT NULL,
            title TEXT NOT NULL,
            thumbUrl TEXT NOT NULL,
            href TEXT NOT NULL,
            genre TEXT NOT NULL,
            updatedAt TEXT,
            savedAt INTEGER NOT NULL,
            PRIMARY KEY (sourceId, toonId)
        )
        """.trimIndent(),
        """
        INSERT INTO favorites_new (sourceId, toonId, title, thumbUrl, href, genre, updatedAt, savedAt)
        SELECT '$DEFAULT_SOURCE', id, title, thumbUrl, href, genre, updatedAt, savedAt FROM favorites
        """.trimIndent(),
        "DROP TABLE favorites",
        "ALTER TABLE favorites_new RENAME TO favorites",
        "CREATE INDEX IF NOT EXISTS index_favorites_savedAt ON favorites(savedAt)",

        """
        CREATE TABLE IF NOT EXISTS seen_toons_new (
            sourceId TEXT NOT NULL,
            toonId TEXT NOT NULL,
            title TEXT NOT NULL,
            updatedAt TEXT,
            firstSeenAt INTEGER NOT NULL,
            lastSeenAt INTEGER NOT NULL,
            notifiedKey TEXT NOT NULL,
            PRIMARY KEY (sourceId, toonId)
        )
        """.trimIndent(),
        """
        INSERT INTO seen_toons_new (sourceId, toonId, title, updatedAt, firstSeenAt, lastSeenAt, notifiedKey)
        SELECT '$DEFAULT_SOURCE', id, title, updatedAt, firstSeenAt, lastSeenAt, notifiedKey FROM seen_toons
        """.trimIndent(),
        "DROP TABLE seen_toons",
        "ALTER TABLE seen_toons_new RENAME TO seen_toons",
        "UPDATE seen_toons SET notifiedKey = '$DEFAULT_SOURCE:' || notifiedKey WHERE notifiedKey NOT LIKE '%:%'",

        """
        CREATE TABLE IF NOT EXISTS reader_settings_new (
            sourceId TEXT NOT NULL,
            toonId TEXT NOT NULL,
            viewMode TEXT NOT NULL,
            readDirection TEXT NOT NULL,
            updatedAt INTEGER NOT NULL,
            PRIMARY KEY (sourceId, toonId)
        )
        """.trimIndent(),
        """
        INSERT INTO reader_settings_new (sourceId, toonId, viewMode, readDirection, updatedAt)
        SELECT '$DEFAULT_SOURCE', toonId, viewMode, readDirection, updatedAt FROM reader_settings
        """.trimIndent(),
        "DROP TABLE reader_settings",
        "ALTER TABLE reader_settings_new RENAME TO reader_settings",

        """
        CREATE TABLE IF NOT EXISTS read_history_new (
            sourceId TEXT NOT NULL,
            toonId TEXT NOT NULL,
            toonTitle TEXT NOT NULL,
            toonThumbUrl TEXT NOT NULL,
            toonHref TEXT NOT NULL,
            lastWrId TEXT NOT NULL,
            lastEpisodeTitle TEXT NOT NULL,
            lastEpisodeHref TEXT NOT NULL,
            lastReadOrder INTEGER NOT NULL,
            totalEpisodes INTEGER NOT NULL,
            lastReadAt INTEGER NOT NULL,
            nextWrId TEXT,
            nextEpisodeTitle TEXT,
            nextEpisodeHref TEXT,
            hasNew INTEGER NOT NULL,
            PRIMARY KEY (sourceId, toonId)
        )
        """.trimIndent(),
        """
        INSERT INTO read_history_new (
            sourceId, toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle,
            lastEpisodeHref, lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle,
            nextEpisodeHref, hasNew
        )
        SELECT '$DEFAULT_SOURCE', toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle,
            lastEpisodeHref, lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle,
            nextEpisodeHref, hasNew FROM read_history
        """.trimIndent(),
        "DROP TABLE read_history",
        "ALTER TABLE read_history_new RENAME TO read_history",
        "CREATE INDEX IF NOT EXISTS index_read_history_lastReadAt ON read_history(lastReadAt)",

        """
        CREATE TABLE IF NOT EXISTS read_episodes_new (
            sourceId TEXT NOT NULL,
            toonId TEXT NOT NULL,
            wrId TEXT NOT NULL,
            readAt INTEGER NOT NULL,
            lastPage INTEGER NOT NULL,
            PRIMARY KEY (sourceId, toonId, wrId)
        )
        """.trimIndent(),
        """
        INSERT INTO read_episodes_new (sourceId, toonId, wrId, readAt, lastPage)
        SELECT '$DEFAULT_SOURCE', toonId, wrId, readAt, lastPage FROM read_episodes
        """.trimIndent(),
        "DROP TABLE read_episodes",
        "ALTER TABLE read_episodes_new RENAME TO read_episodes",

        """
        CREATE TABLE IF NOT EXISTS downloaded_episodes_new (
            sourceId TEXT NOT NULL,
            toonId TEXT NOT NULL,
            wrId TEXT NOT NULL,
            toonTitle TEXT NOT NULL,
            toonThumbUrl TEXT NOT NULL,
            toonHref TEXT NOT NULL,
            episodeTitle TEXT NOT NULL,
            episodeHref TEXT NOT NULL,
            imageCount INTEGER NOT NULL,
            totalBytes INTEGER NOT NULL,
            downloadedAt INTEGER NOT NULL,
            localDirPath TEXT NOT NULL,
            PRIMARY KEY (sourceId, toonId, wrId)
        )
        """.trimIndent(),
        """
        INSERT INTO downloaded_episodes_new (
            sourceId, toonId, wrId, toonTitle, toonThumbUrl, toonHref, episodeTitle, episodeHref,
            imageCount, totalBytes, downloadedAt, localDirPath
        )
        SELECT '$DEFAULT_SOURCE', toonId, wrId, toonTitle, toonThumbUrl, toonHref, episodeTitle, episodeHref,
            imageCount, totalBytes, downloadedAt, localDirPath FROM downloaded_episodes
        """.trimIndent(),
        "DROP TABLE downloaded_episodes",
        "ALTER TABLE downloaded_episodes_new RENAME TO downloaded_episodes",
        "CREATE INDEX IF NOT EXISTS index_downloaded_episodes_toonId ON downloaded_episodes(toonId)",
        "CREATE INDEX IF NOT EXISTS index_downloaded_episodes_downloadedAt ON downloaded_episodes(downloadedAt)",

        "UPDATE tombstones SET entityId = '$DEFAULT_SOURCE:' || entityId WHERE entityId NOT LIKE '%:%'",
    )
}
