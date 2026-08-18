package com.comics8.desktop.data

import com.comics8.core.model.SyncTombstone
import com.comics8.core.model.UpdateDates
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class DesktopDatabase(dbFile: File? = null) {
    private val dbPath: String

    init {
        try {
            Class.forName("org.sqlite.JDBC")
        } catch (_: Exception) {
        }
        val file = dbFile ?: File(System.getProperty("user.home"), ".comics8/comics8.db")
        file.parentFile?.mkdirs()
        dbPath = file.absolutePath
        initTables()
    }

    private fun getConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:$dbPath")

    private fun initTables() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS seen_toons (
                        id TEXT PRIMARY KEY,
                        title TEXT,
                        updatedAt TEXT,
                        firstSeenAt INTEGER,
                        lastSeenAt INTEGER,
                        notifiedKey TEXT
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS favorites (
                        id TEXT PRIMARY KEY,
                        title TEXT,
                        thumbUrl TEXT,
                        href TEXT,
                        genre TEXT,
                        updatedAt TEXT,
                        savedAt INTEGER
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS reader_settings (
                        toonId TEXT PRIMARY KEY,
                        viewMode TEXT,
                        readDirection TEXT,
                        updatedAt INTEGER
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS read_episodes (
                        toonId TEXT,
                        wrId TEXT,
                        readAt INTEGER,
                        lastPage INTEGER DEFAULT 0,
                        PRIMARY KEY (toonId, wrId)
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS read_history (
                        toonId TEXT PRIMARY KEY,
                        toonTitle TEXT,
                        toonThumbUrl TEXT,
                        toonHref TEXT,
                        lastWrId TEXT,
                        lastEpisodeTitle TEXT,
                        lastEpisodeHref TEXT,
                        lastReadOrder INTEGER,
                        totalEpisodes INTEGER,
                        lastReadAt INTEGER,
                        nextWrId TEXT,
                        nextEpisodeTitle TEXT,
                        nextEpisodeHref TEXT,
                        hasNew INTEGER DEFAULT 0
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS tombstones (
                        entityType TEXT,
                        entityId TEXT,
                        deletedAt INTEGER,
                        PRIMARY KEY (entityType, entityId)
                    );
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS downloaded_episodes (
                        toonId TEXT,
                        wrId TEXT,
                        toonTitle TEXT,
                        toonThumbUrl TEXT,
                        toonHref TEXT,
                        episodeTitle TEXT,
                        episodeHref TEXT,
                        imageCount INTEGER,
                        totalBytes INTEGER,
                        downloadedAt INTEGER,
                        localDirPath TEXT,
                        PRIMARY KEY (toonId, wrId)
                    );
                    """.trimIndent(),
                )
            }
        }
    }

    // --- Tombstones ---
    fun recordTombstone(entityType: String, entityId: String, deletedAt: Long = System.currentTimeMillis()) {
        getConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO tombstones (entityType, entityId, deletedAt)
                VALUES (?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, entityType)
                stmt.setString(2, entityId)
                stmt.setLong(3, deletedAt)
                stmt.executeUpdate()
            }
        }
    }

    fun deleteTombstone(entityType: String, entityId: String) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM tombstones WHERE entityType = ? AND entityId = ?").use { stmt ->
                stmt.setString(1, entityType)
                stmt.setString(2, entityId)
                stmt.executeUpdate()
            }
        }
    }

    fun getTombstonesSince(since: Long): List<SyncTombstone> {
        val list = mutableListOf<SyncTombstone>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT entityType, entityId, deletedAt FROM tombstones WHERE deletedAt > ?").use { stmt ->
                stmt.setLong(1, since)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        SyncTombstone(
                            entityType = rs.getString("entityType"),
                            entityId = rs.getString("entityId"),
                            deletedAt = rs.getLong("deletedAt"),
                        )
                    )
                }
            }
        }
        return list
    }

    fun deleteTombstonesOlderThan(cutoff: Long) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM tombstones WHERE deletedAt < ?").use { stmt ->
                stmt.setLong(1, cutoff)
                stmt.executeUpdate()
            }
        }
    }

    // --- Favorites ---
    fun getAllFavorites(): List<FavoriteRecord> {
        val list = mutableListOf<FavoriteRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM favorites ORDER BY COALESCE(updatedAt, '') DESC, savedAt DESC").use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        FavoriteRecord(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            thumbUrl = rs.getString("thumbUrl"),
                            href = rs.getString("href"),
                            genre = rs.getString("genre"),
                            updatedAt = rs.getString("updatedAt"),
                            savedAt = rs.getLong("savedAt"),
                        ),
                    )
                }
            }
        }
        return list
    }

    fun getFavoritesSince(since: Long): List<FavoriteRecord> {
        val list = mutableListOf<FavoriteRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM favorites WHERE savedAt > ? ORDER BY savedAt DESC").use { stmt ->
                stmt.setLong(1, since)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        FavoriteRecord(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            thumbUrl = rs.getString("thumbUrl"),
                            href = rs.getString("href"),
                            genre = rs.getString("genre"),
                            updatedAt = rs.getString("updatedAt"),
                            savedAt = rs.getLong("savedAt"),
                        ),
                    )
                }
            }
        }
        return list
    }

    fun isFavorite(id: String): Boolean {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT 1 FROM favorites WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                val rs = stmt.executeQuery()
                return rs.next()
            }
        }
    }

    fun getFavorite(id: String): FavoriteRecord? {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM favorites WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                val rs = stmt.executeQuery()
                if (!rs.next()) return null
                return FavoriteRecord(
                    id = rs.getString("id"),
                    title = rs.getString("title"),
                    thumbUrl = rs.getString("thumbUrl"),
                    href = rs.getString("href"),
                    genre = rs.getString("genre"),
                    updatedAt = rs.getString("updatedAt"),
                    savedAt = rs.getLong("savedAt"),
                )
            }
        }
    }

    fun updateFavoriteUpdatedAt(id: String, updatedAt: String) {
        if (updatedAt.isBlank()) return
        val current = getFavorite(id) ?: return
        if (!UpdateDates.shouldReplace(current.updatedAt, updatedAt)) return
        val stored = UpdateDates.toListingDate(updatedAt) ?: updatedAt
        getConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE favorites SET updatedAt = ? WHERE id = ?"
            ).use { stmt ->
                stmt.setString(1, stored)
                stmt.setString(2, id)
                stmt.executeUpdate()
            }
        }
    }

    fun getFavoriteIds(): Set<String> {
        val set = mutableSetOf<String>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT id FROM favorites").use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    set.add(rs.getString("id"))
                }
            }
        }
        return set
    }

    fun saveFavorite(item: FavoriteRecord) {
        getConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO favorites (id, title, thumbUrl, href, genre, updatedAt, savedAt)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, item.id)
                stmt.setString(2, item.title)
                stmt.setString(3, item.thumbUrl)
                stmt.setString(4, item.href)
                stmt.setString(5, item.genre)
                stmt.setString(6, item.updatedAt)
                stmt.setLong(7, item.savedAt)
                stmt.executeUpdate()
            }
        }
    }

    fun saveAllFavorites(items: List<FavoriteRecord>) {
        getConnection().use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO favorites (id, title, thumbUrl, href, genre, updatedAt, savedAt)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                for (item in items) {
                    stmt.setString(1, item.id)
                    stmt.setString(2, item.title)
                    stmt.setString(3, item.thumbUrl)
                    stmt.setString(4, item.href)
                    stmt.setString(5, item.genre)
                    stmt.setString(6, item.updatedAt)
                    stmt.setLong(7, item.savedAt)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        }
    }

    fun deleteFavorite(id: String) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM favorites WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate()
            }
        }
    }

    // --- Seen Toons ---
    fun getSeenCount(): Int {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM seen_toons").use { stmt ->
                val rs = stmt.executeQuery()
                return if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    fun getSeenByIds(ids: List<String>): Map<String, SeenRecord> {
        if (ids.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, SeenRecord>()
        getConnection().use { conn ->
            val placeholders = ids.joinToString(",") { "?" }
            conn.prepareStatement("SELECT * FROM seen_toons WHERE id IN ($placeholders)").use { stmt ->
                ids.forEachIndexed { index, id -> stmt.setString(index + 1, id) }
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val record = SeenRecord(
                        id = rs.getString("id"),
                        title = rs.getString("title"),
                        updatedAt = rs.getString("updatedAt"),
                        firstSeenAt = rs.getLong("firstSeenAt"),
                        lastSeenAt = rs.getLong("lastSeenAt"),
                        notifiedKey = rs.getString("notifiedKey"),
                    )
                    map[record.id] = record
                }
            }
        }
        return map
    }

    fun saveAllSeen(items: List<SeenRecord>) {
        if (items.isEmpty()) return
        getConnection().use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO seen_toons (id, title, updatedAt, firstSeenAt, lastSeenAt, notifiedKey)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                for (item in items) {
                    stmt.setString(1, item.id)
                    stmt.setString(2, item.title)
                    stmt.setString(3, item.updatedAt)
                    stmt.setLong(4, item.firstSeenAt)
                    stmt.setLong(5, item.lastSeenAt)
                    stmt.setString(6, item.notifiedKey)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        }
    }

    // --- Read History ---
    fun getAllHistory(): List<ReadHistoryRecord> {
        val list = mutableListOf<ReadHistoryRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM read_history ORDER BY lastReadAt DESC").use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        ReadHistoryRecord(
                            toonId = rs.getString("toonId"),
                            toonTitle = rs.getString("toonTitle"),
                            toonThumbUrl = rs.getString("toonThumbUrl"),
                            toonHref = rs.getString("toonHref"),
                            lastWrId = rs.getString("lastWrId"),
                            lastEpisodeTitle = rs.getString("lastEpisodeTitle"),
                            lastEpisodeHref = rs.getString("lastEpisodeHref"),
                            lastReadOrder = rs.getInt("lastReadOrder"),
                            totalEpisodes = rs.getInt("totalEpisodes"),
                            lastReadAt = rs.getLong("lastReadAt"),
                            nextWrId = rs.getString("nextWrId"),
                            nextEpisodeTitle = rs.getString("nextEpisodeTitle"),
                            nextEpisodeHref = rs.getString("nextEpisodeHref"),
                            hasNew = rs.getInt("hasNew") == 1,
                        ),
                    )
                }
            }
        }
        return list
    }

    fun getHistorySince(since: Long): List<ReadHistoryRecord> {
        val list = mutableListOf<ReadHistoryRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM read_history WHERE lastReadAt > ? ORDER BY lastReadAt DESC").use { stmt ->
                stmt.setLong(1, since)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        ReadHistoryRecord(
                            toonId = rs.getString("toonId"),
                            toonTitle = rs.getString("toonTitle"),
                            toonThumbUrl = rs.getString("toonThumbUrl"),
                            toonHref = rs.getString("toonHref"),
                            lastWrId = rs.getString("lastWrId"),
                            lastEpisodeTitle = rs.getString("lastEpisodeTitle"),
                            lastEpisodeHref = rs.getString("lastEpisodeHref"),
                            lastReadOrder = rs.getInt("lastReadOrder"),
                            totalEpisodes = rs.getInt("totalEpisodes"),
                            lastReadAt = rs.getLong("lastReadAt"),
                            nextWrId = rs.getString("nextWrId"),
                            nextEpisodeTitle = rs.getString("nextEpisodeTitle"),
                            nextEpisodeHref = rs.getString("nextEpisodeHref"),
                            hasNew = rs.getInt("hasNew") == 1,
                        ),
                    )
                }
            }
        }
        return list
    }

    fun getHistory(toonId: String): ReadHistoryRecord? {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM read_history WHERE toonId = ?").use { stmt ->
                stmt.setString(1, toonId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return ReadHistoryRecord(
                        toonId = rs.getString("toonId"),
                        toonTitle = rs.getString("toonTitle"),
                        toonThumbUrl = rs.getString("toonThumbUrl"),
                        toonHref = rs.getString("toonHref"),
                        lastWrId = rs.getString("lastWrId"),
                        lastEpisodeTitle = rs.getString("lastEpisodeTitle"),
                        lastEpisodeHref = rs.getString("lastEpisodeHref"),
                        lastReadOrder = rs.getInt("lastReadOrder"),
                        totalEpisodes = rs.getInt("totalEpisodes"),
                        lastReadAt = rs.getLong("lastReadAt"),
                        nextWrId = rs.getString("nextWrId"),
                        nextEpisodeTitle = rs.getString("nextEpisodeTitle"),
                        nextEpisodeHref = rs.getString("nextEpisodeHref"),
                        hasNew = rs.getInt("hasNew") == 1,
                    )
                }
            }
        }
        return null
    }

    fun saveHistory(history: ReadHistoryRecord) {
        getConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO read_history 
                (toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref, lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, history.toonId)
                stmt.setString(2, history.toonTitle)
                stmt.setString(3, history.toonThumbUrl)
                stmt.setString(4, history.toonHref)
                stmt.setString(5, history.lastWrId)
                stmt.setString(6, history.lastEpisodeTitle)
                stmt.setString(7, history.lastEpisodeHref)
                stmt.setInt(8, history.lastReadOrder)
                stmt.setInt(9, history.totalEpisodes)
                stmt.setLong(10, history.lastReadAt)
                stmt.setString(11, history.nextWrId)
                stmt.setString(12, history.nextEpisodeTitle)
                stmt.setString(13, history.nextEpisodeHref)
                stmt.setInt(14, if (history.hasNew) 1 else 0)
                stmt.executeUpdate()
            }
        }
    }

    fun saveAllHistory(items: List<ReadHistoryRecord>) {
        if (items.isEmpty()) return
        getConnection().use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO read_history 
                (toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref, lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                for (history in items) {
                    stmt.setString(1, history.toonId)
                    stmt.setString(2, history.toonTitle)
                    stmt.setString(3, history.toonThumbUrl)
                    stmt.setString(4, history.toonHref)
                    stmt.setString(5, history.lastWrId)
                    stmt.setString(6, history.lastEpisodeTitle)
                    stmt.setString(7, history.lastEpisodeHref)
                    stmt.setInt(8, history.lastReadOrder)
                    stmt.setInt(9, history.totalEpisodes)
                    stmt.setLong(10, history.lastReadAt)
                    stmt.setString(11, history.nextWrId)
                    stmt.setString(12, history.nextEpisodeTitle)
                    stmt.setString(13, history.nextEpisodeHref)
                    stmt.setInt(14, if (history.hasNew) 1 else 0)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        }
    }

    fun deleteHistory(toonId: String) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM read_history WHERE toonId = ?").use { stmt ->
                stmt.setString(1, toonId)
                stmt.executeUpdate()
            }
        }
    }

    fun clearAllHistory() {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM read_history").use { stmt ->
                stmt.executeUpdate()
            }
        }
    }

    // --- Read Episodes ---
    fun getAllReadEpisodes(): List<ReadEpisodeRecord> {
        val list = mutableListOf<ReadEpisodeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM read_episodes").use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        ReadEpisodeRecord(
                            toonId = rs.getString("toonId"),
                            wrId = rs.getString("wrId"),
                            readAt = rs.getLong("readAt"),
                            lastPage = rs.getInt("lastPage"),
                        ),
                    )
                }
            }
        }
        return list
    }

    fun getReadEpisodesSince(since: Long): List<ReadEpisodeRecord> {
        val list = mutableListOf<ReadEpisodeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM read_episodes WHERE readAt > ?").use { stmt ->
                stmt.setLong(1, since)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        ReadEpisodeRecord(
                            toonId = rs.getString("toonId"),
                            wrId = rs.getString("wrId"),
                            readAt = rs.getLong("readAt"),
                            lastPage = rs.getInt("lastPage"),
                        ),
                    )
                }
            }
        }
        return list
    }

    fun getReadEpisodesByToon(toonId: String): List<ReadEpisodeRecord> {
        val list = mutableListOf<ReadEpisodeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM read_episodes WHERE toonId = ?").use { stmt ->
                stmt.setString(1, toonId)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        ReadEpisodeRecord(
                            toonId = rs.getString("toonId"),
                            wrId = rs.getString("wrId"),
                            readAt = rs.getLong("readAt"),
                            lastPage = rs.getInt("lastPage"),
                        ),
                    )
                }
            }
        }
        return list
    }

    fun getReadEpisode(toonId: String, wrId: String): ReadEpisodeRecord? {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM read_episodes WHERE toonId = ? AND wrId = ?").use { stmt ->
                stmt.setString(1, toonId)
                stmt.setString(2, wrId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return ReadEpisodeRecord(
                        toonId = rs.getString("toonId"),
                        wrId = rs.getString("wrId"),
                        readAt = rs.getLong("readAt"),
                        lastPage = rs.getInt("lastPage"),
                    )
                }
            }
        }
        return null
    }

    fun markEpisodeRead(record: ReadEpisodeRecord) {
        getConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO read_episodes (toonId, wrId, readAt, lastPage)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, record.toonId)
                stmt.setString(2, record.wrId)
                stmt.setLong(3, record.readAt)
                stmt.setInt(4, record.lastPage)
                stmt.executeUpdate()
            }
        }
    }

    fun markAllEpisodesRead(items: List<ReadEpisodeRecord>) {
        if (items.isEmpty()) return
        getConnection().use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO read_episodes (toonId, wrId, readAt, lastPage)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                for (item in items) {
                    stmt.setString(1, item.toonId)
                    stmt.setString(2, item.wrId)
                    stmt.setLong(3, item.readAt)
                    stmt.setInt(4, item.lastPage)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        }
    }

    fun updateLastPage(toonId: String, wrId: String, page: Int, readAt: Long = System.currentTimeMillis()) {
        getConnection().use { conn ->
            conn.prepareStatement("UPDATE read_episodes SET lastPage = ?, readAt = ? WHERE toonId = ? AND wrId = ?").use { stmt ->
                stmt.setInt(1, page)
                stmt.setLong(2, readAt)
                stmt.setString(3, toonId)
                stmt.setString(4, wrId)
                stmt.executeUpdate()
            }
        }
    }

    fun deleteReadEpisodesByToon(toonId: String) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM read_episodes WHERE toonId = ?").use { stmt ->
                stmt.setString(1, toonId)
                stmt.executeUpdate()
            }
        }
    }

    // --- Reader Settings ---
    fun getAllReaderSettings(): List<ReaderSettingRecord> {
        val list = mutableListOf<ReaderSettingRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM reader_settings").use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        ReaderSettingRecord(
                            toonId = rs.getString("toonId"),
                            viewMode = rs.getString("viewMode"),
                            readDirection = rs.getString("readDirection"),
                            updatedAt = rs.getLong("updatedAt"),
                        ),
                    )
                }
            }
        }
        return list
    }

    fun getReaderSettingsSince(since: Long): List<ReaderSettingRecord> {
        val list = mutableListOf<ReaderSettingRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM reader_settings WHERE updatedAt > ?").use { stmt ->
                stmt.setLong(1, since)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        ReaderSettingRecord(
                            toonId = rs.getString("toonId"),
                            viewMode = rs.getString("viewMode"),
                            readDirection = rs.getString("readDirection"),
                            updatedAt = rs.getLong("updatedAt"),
                        ),
                    )
                }
            }
        }
        return list
    }

    fun getReaderSetting(toonId: String): ReaderSettingRecord? {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM reader_settings WHERE toonId = ?").use { stmt ->
                stmt.setString(1, toonId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return ReaderSettingRecord(
                        toonId = rs.getString("toonId"),
                        viewMode = rs.getString("viewMode"),
                        readDirection = rs.getString("readDirection"),
                        updatedAt = rs.getLong("updatedAt"),
                    )
                }
            }
        }
        return null
    }

    fun saveReaderSetting(setting: ReaderSettingRecord) {
        getConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO reader_settings (toonId, viewMode, readDirection, updatedAt)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, setting.toonId)
                stmt.setString(2, setting.viewMode)
                stmt.setString(3, setting.readDirection)
                stmt.setLong(4, setting.updatedAt)
                stmt.executeUpdate()
            }
        }
    }

    fun saveAllReaderSettings(items: List<ReaderSettingRecord>) {
        if (items.isEmpty()) return
        getConnection().use { conn ->
            conn.autoCommit = false
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO reader_settings (toonId, viewMode, readDirection, updatedAt)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                for (item in items) {
                    stmt.setString(1, item.toonId)
                    stmt.setString(2, item.viewMode)
                    stmt.setString(3, item.readDirection)
                    stmt.setLong(4, item.updatedAt)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
            conn.commit()
        }
    }

    // --- Downloaded Episodes ---
    fun getAllDownloadedEpisodes(): List<DownloadedEpisodeRecord> {
        val list = mutableListOf<DownloadedEpisodeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM downloaded_episodes ORDER BY downloadedAt DESC").use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        DownloadedEpisodeRecord(
                            toonId = rs.getString("toonId"),
                            wrId = rs.getString("wrId"),
                            toonTitle = rs.getString("toonTitle"),
                            toonThumbUrl = rs.getString("toonThumbUrl"),
                            toonHref = rs.getString("toonHref"),
                            episodeTitle = rs.getString("episodeTitle"),
                            episodeHref = rs.getString("episodeHref"),
                            imageCount = rs.getInt("imageCount"),
                            totalBytes = rs.getLong("totalBytes"),
                            downloadedAt = rs.getLong("downloadedAt"),
                            localDirPath = rs.getString("localDirPath"),
                        )
                    )
                }
            }
        }
        return list
    }

    fun getDownloadedEpisodesByToon(toonId: String): List<DownloadedEpisodeRecord> {
        val list = mutableListOf<DownloadedEpisodeRecord>()
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM downloaded_episodes WHERE toonId = ? ORDER BY downloadedAt DESC").use { stmt ->
                stmt.setString(1, toonId)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(
                        DownloadedEpisodeRecord(
                            toonId = rs.getString("toonId"),
                            wrId = rs.getString("wrId"),
                            toonTitle = rs.getString("toonTitle"),
                            toonThumbUrl = rs.getString("toonThumbUrl"),
                            toonHref = rs.getString("toonHref"),
                            episodeTitle = rs.getString("episodeTitle"),
                            episodeHref = rs.getString("episodeHref"),
                            imageCount = rs.getInt("imageCount"),
                            totalBytes = rs.getLong("totalBytes"),
                            downloadedAt = rs.getLong("downloadedAt"),
                            localDirPath = rs.getString("localDirPath"),
                        )
                    )
                }
            }
        }
        return list
    }

    fun getDownloadedEpisode(toonId: String, wrId: String): DownloadedEpisodeRecord? {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM downloaded_episodes WHERE toonId = ? AND wrId = ?").use { stmt ->
                stmt.setString(1, toonId)
                stmt.setString(2, wrId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return DownloadedEpisodeRecord(
                        toonId = rs.getString("toonId"),
                        wrId = rs.getString("wrId"),
                        toonTitle = rs.getString("toonTitle"),
                        toonThumbUrl = rs.getString("toonThumbUrl"),
                        toonHref = rs.getString("toonHref"),
                        episodeTitle = rs.getString("episodeTitle"),
                        episodeHref = rs.getString("episodeHref"),
                        imageCount = rs.getInt("imageCount"),
                        totalBytes = rs.getLong("totalBytes"),
                        downloadedAt = rs.getLong("downloadedAt"),
                        localDirPath = rs.getString("localDirPath"),
                    )
                }
            }
        }
        return null
    }

    fun saveDownloadedEpisode(item: DownloadedEpisodeRecord) {
        getConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO downloaded_episodes
                (toonId, wrId, toonTitle, toonThumbUrl, toonHref, episodeTitle, episodeHref, imageCount, totalBytes, downloadedAt, localDirPath)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, item.toonId)
                stmt.setString(2, item.wrId)
                stmt.setString(3, item.toonTitle)
                stmt.setString(4, item.toonThumbUrl)
                stmt.setString(5, item.toonHref)
                stmt.setString(6, item.episodeTitle)
                stmt.setString(7, item.episodeHref)
                stmt.setInt(8, item.imageCount)
                stmt.setLong(9, item.totalBytes)
                stmt.setLong(10, item.downloadedAt)
                stmt.setString(11, item.localDirPath)
                stmt.executeUpdate()
            }
        }
    }

    fun deleteDownloadedEpisode(toonId: String, wrId: String) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM downloaded_episodes WHERE toonId = ? AND wrId = ?").use { stmt ->
                stmt.setString(1, toonId)
                stmt.setString(2, wrId)
                stmt.executeUpdate()
            }
        }
    }

    fun deleteDownloadedEpisodesByToon(toonId: String) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM downloaded_episodes WHERE toonId = ?").use { stmt ->
                stmt.setString(1, toonId)
                stmt.executeUpdate()
            }
        }
    }
}
