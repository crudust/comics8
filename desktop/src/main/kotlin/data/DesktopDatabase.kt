package com.comics8.desktop.data

import com.comics8.core.model.SyncTombstone
import com.comics8.core.model.UpdateDates
import com.comics8.core.source.SourceAccess
import com.comics8.core.source.WorkId
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class DesktopDatabase(dbFile: File? = null) : AutoCloseable {
    @Volatile
    var isSourceEnabled: (String) -> Boolean = { false }

    @Volatile
    var installedIds: () -> Set<String> = { emptySet() }

    private val dbPath: String = run {
        try {
            Class.forName("org.sqlite.JDBC")
        } catch (_: Exception) {
        }
        val file = dbFile ?: File(System.getProperty("user.home"), ".comics8/comics8.db")
        file.parentFile?.mkdirs()
        file.absolutePath
    }

    private val connectionLock = Any()
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath").also { conn ->
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA busy_timeout=5000")
            stmt.execute("PRAGMA foreign_keys=ON")
        }
    }

    init {
        initTables()
    }

    private fun writableId(sourceId: String, toonId: String): WorkId? =
        SourceAccess.writable(sourceId, toonId, isSourceEnabled, installedIds())

    private inline fun <T> withConnection(block: (Connection) -> T): T {
        synchronized(connectionLock) {
            return block(connection)
        }
    }

    private inline fun <T> withTransaction(block: (Connection) -> T): T =
        withConnection { conn ->
            val previous = conn.autoCommit
            val ownsTransaction = previous
            if (ownsTransaction) conn.autoCommit = false
            try {
                val result = block(conn)
                if (ownsTransaction) conn.commit()
                result
            } catch (e: Exception) {
                if (ownsTransaction) {
                    try {
                        conn.rollback()
                    } catch (_: Exception) {
                    }
                }
                throw e
            } finally {
                if (ownsTransaction) conn.autoCommit = previous
            }
        }

    fun restoreBackup(
        favorites: List<FavoriteRecord>,
        history: List<ReadHistoryRecord>,
        episodes: List<ReadEpisodeRecord>,
        settings: List<ReaderSettingRecord>,
    ) {
        withTransaction {
            saveAllFavorites(favorites)
            saveAllHistory(history)
            markAllEpisodesRead(episodes)
            saveAllReaderSettings(settings)
        }
    }

    fun applySyncBatch(
        deletions: List<Pair<String, WorkId>>,
        favorites: List<FavoriteRecord>,
        history: List<ReadHistoryRecord>,
        episodes: List<ReadEpisodeRecord>,
        settings: List<ReaderSettingRecord>,
    ) {
        withTransaction {
            deletions.forEach { (entityType, workId) ->
                when (entityType) {
                    "FAVORITE" -> deleteFavorite(workId)
                    "HISTORY" -> deleteHistory(workId)
                    "EPISODE" -> deleteReadEpisodesByToon(workId)
                }
            }
            if (favorites.isNotEmpty()) saveAllFavorites(favorites)
            if (history.isNotEmpty()) saveAllHistory(history)
            if (episodes.isNotEmpty()) markAllEpisodesRead(episodes)
            if (settings.isNotEmpty()) saveAllReaderSettings(settings)
        }
    }

    fun getBackupStats(): DesktopBackupStats = withConnection { conn ->
        fun count(table: String): Int = conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }
        DesktopBackupStats(
            favoriteCount = count("favorites"),
            historyCount = count("read_history"),
            episodeCount = count("read_episodes"),
            settingCount = count("reader_settings"),
        )
    }

    override fun close() {
        synchronized(connectionLock) {
            try {
                if (!connection.isClosed) connection.close()
            } catch (_: Exception) {
            }
        }
    }

    fun userVersion(): Int {
        withConnection { conn ->
            return userVersion(conn)
        }
    }

    private fun userVersion(conn: Connection): Int {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA user_version").use { rs ->
                return if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    private fun initTables() {
        withConnection { conn ->
            val version = userVersion(conn)
            if (version < 1) {
                rebuildIfLegacy(conn)
                conn.createStatement().use { it.execute("PRAGMA user_version = 1") }
            }
            createCurrentTables(conn)
        }
    }

    private fun tableExists(conn: Connection, name: String): Boolean {
        conn.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        ).use { stmt ->
            stmt.setString(1, name)
            stmt.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun hasColumn(conn: Connection, table: String, column: String): Boolean {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name") == column) return true
                }
            }
        }
        return false
    }

    private fun rebuildIfLegacy(conn: Connection) {
        val previous = conn.autoCommit
        conn.autoCommit = false
        try {
            rebuildIfLegacyUnlocked(conn)
            conn.commit()
        } catch (e: Exception) {
            conn.rollback()
            throw e
        } finally {
            conn.autoCommit = previous
        }
    }

    private fun rebuildIfLegacyUnlocked(conn: Connection) {
        val eleven = WorkId.DEFAULT_SOURCE
        if (tableExists(conn, "favorites") && !hasColumn(conn, "favorites", "sourceId")) {
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS favorites_new")
                stmt.execute(
                    """
                    CREATE TABLE favorites_new (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        title TEXT,
                        thumbUrl TEXT,
                        href TEXT,
                        genre TEXT,
                        updatedAt TEXT,
                        savedAt INTEGER,
                        PRIMARY KEY (sourceId, toonId)
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO favorites_new (sourceId, toonId, title, thumbUrl, href, genre, updatedAt, savedAt)
                    SELECT '$eleven', id, title, thumbUrl, href, genre, updatedAt, savedAt FROM favorites
                    """.trimIndent(),
                )
                stmt.execute("DROP TABLE favorites")
                stmt.execute("ALTER TABLE favorites_new RENAME TO favorites")
            }
        }
        if (tableExists(conn, "seen_toons") && !hasColumn(conn, "seen_toons", "sourceId")) {
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS seen_toons_new")
                stmt.execute(
                    """
                    CREATE TABLE seen_toons_new (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        title TEXT,
                        updatedAt TEXT,
                        firstSeenAt INTEGER,
                        lastSeenAt INTEGER,
                        notifiedKey TEXT,
                        PRIMARY KEY (sourceId, toonId)
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO seen_toons_new (sourceId, toonId, title, updatedAt, firstSeenAt, lastSeenAt, notifiedKey)
                    SELECT '$eleven', id, title, updatedAt, firstSeenAt, lastSeenAt, notifiedKey FROM seen_toons
                    """.trimIndent(),
                )
                stmt.execute("DROP TABLE seen_toons")
                stmt.execute("ALTER TABLE seen_toons_new RENAME TO seen_toons")
            }
        }
        if (tableExists(conn, "seen_toons")) {
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "UPDATE seen_toons SET notifiedKey = '$eleven:' || notifiedKey WHERE notifiedKey NOT LIKE '%:%'",
                )
            }
        }
        if (tableExists(conn, "reader_settings") && !hasColumn(conn, "reader_settings", "sourceId")) {
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS reader_settings_new")
                stmt.execute(
                    """
                    CREATE TABLE reader_settings_new (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        viewMode TEXT,
                        readDirection TEXT,
                        updatedAt INTEGER,
                        PRIMARY KEY (sourceId, toonId)
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO reader_settings_new (sourceId, toonId, viewMode, readDirection, updatedAt)
                    SELECT '$eleven', toonId, viewMode, readDirection, updatedAt FROM reader_settings
                    """.trimIndent(),
                )
                stmt.execute("DROP TABLE reader_settings")
                stmt.execute("ALTER TABLE reader_settings_new RENAME TO reader_settings")
            }
        }
        if (tableExists(conn, "read_history") && !hasColumn(conn, "read_history", "sourceId")) {
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS read_history_new")
                stmt.execute(
                    """
                    CREATE TABLE read_history_new (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
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
                        hasNew INTEGER DEFAULT 0,
                        PRIMARY KEY (sourceId, toonId)
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO read_history_new (
                        sourceId, toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle,
                        lastEpisodeHref, lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle,
                        nextEpisodeHref, hasNew
                    )
                    SELECT '$eleven', toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle,
                        lastEpisodeHref, lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle,
                        nextEpisodeHref, hasNew FROM read_history
                    """.trimIndent(),
                )
                stmt.execute("DROP TABLE read_history")
                stmt.execute("ALTER TABLE read_history_new RENAME TO read_history")
            }
        }
        if (tableExists(conn, "read_episodes") && !hasColumn(conn, "read_episodes", "sourceId")) {
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS read_episodes_new")
                stmt.execute(
                    """
                    CREATE TABLE read_episodes_new (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        wrId TEXT NOT NULL,
                        readAt INTEGER,
                        lastPage INTEGER DEFAULT 0,
                        PRIMARY KEY (sourceId, toonId, wrId)
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO read_episodes_new (sourceId, toonId, wrId, readAt, lastPage)
                    SELECT '$eleven', toonId, wrId, readAt, lastPage FROM read_episodes
                    """.trimIndent(),
                )
                stmt.execute("DROP TABLE read_episodes")
                stmt.execute("ALTER TABLE read_episodes_new RENAME TO read_episodes")
            }
        }
        if (tableExists(conn, "downloaded_episodes") && !hasColumn(conn, "downloaded_episodes", "sourceId")) {
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS downloaded_episodes_new")
                stmt.execute(
                    """
                    CREATE TABLE downloaded_episodes_new (
                        sourceId TEXT NOT NULL,
                        toonId TEXT NOT NULL,
                        wrId TEXT NOT NULL,
                        toonTitle TEXT,
                        toonThumbUrl TEXT,
                        toonHref TEXT,
                        episodeTitle TEXT,
                        episodeHref TEXT,
                        imageCount INTEGER,
                        totalBytes INTEGER,
                        downloadedAt INTEGER,
                        localDirPath TEXT,
                        PRIMARY KEY (sourceId, toonId, wrId)
                    )
                    """.trimIndent(),
                )
                stmt.execute(
                    """
                    INSERT INTO downloaded_episodes_new (
                        sourceId, toonId, wrId, toonTitle, toonThumbUrl, toonHref, episodeTitle, episodeHref,
                        imageCount, totalBytes, downloadedAt, localDirPath
                    )
                    SELECT '$eleven', toonId, wrId, toonTitle, toonThumbUrl, toonHref, episodeTitle, episodeHref,
                        imageCount, totalBytes, downloadedAt, localDirPath FROM downloaded_episodes
                    """.trimIndent(),
                )
                stmt.execute("DROP TABLE downloaded_episodes")
                stmt.execute("ALTER TABLE downloaded_episodes_new RENAME TO downloaded_episodes")
            }
        }
        if (tableExists(conn, "tombstones")) {
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "UPDATE tombstones SET entityId = '$eleven:' || entityId WHERE entityId NOT LIKE '%:%'",
                )
            }
        }
    }

    private fun createCurrentTables(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS seen_toons (
                    sourceId TEXT NOT NULL,
                    toonId TEXT NOT NULL,
                    title TEXT,
                    updatedAt TEXT,
                    firstSeenAt INTEGER,
                    lastSeenAt INTEGER,
                    notifiedKey TEXT,
                    PRIMARY KEY (sourceId, toonId)
                )
                """.trimIndent(),
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS index_seen_toons_toonId ON seen_toons(toonId)")
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS favorites (
                    sourceId TEXT NOT NULL,
                    toonId TEXT NOT NULL,
                    title TEXT,
                    thumbUrl TEXT,
                    href TEXT,
                    genre TEXT,
                    updatedAt TEXT,
                    savedAt INTEGER,
                    PRIMARY KEY (sourceId, toonId)
                )
                """.trimIndent(),
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS index_favorites_toonId ON favorites(toonId)")
            stmt.execute("CREATE INDEX IF NOT EXISTS index_favorites_savedAt ON favorites(savedAt)")
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS reader_settings (
                    sourceId TEXT NOT NULL,
                    toonId TEXT NOT NULL,
                    viewMode TEXT,
                    readDirection TEXT,
                    splitMode TEXT DEFAULT 'FIT',
                    updatedAt INTEGER,
                    PRIMARY KEY (sourceId, toonId)
                )
                """.trimIndent(),
            )
            if (!hasColumn(conn, "reader_settings", "splitMode")) {
                stmt.execute("ALTER TABLE reader_settings ADD COLUMN splitMode TEXT DEFAULT 'FIT'")
            }
            stmt.execute("CREATE INDEX IF NOT EXISTS index_reader_settings_updatedAt ON reader_settings(updatedAt)")
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS read_episodes (
                    sourceId TEXT NOT NULL,
                    toonId TEXT NOT NULL,
                    wrId TEXT NOT NULL,
                    readAt INTEGER,
                    lastPage INTEGER DEFAULT 0,
                    PRIMARY KEY (sourceId, toonId, wrId)
                )
                """.trimIndent(),
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS index_read_episodes_toonId ON read_episodes(toonId)")
            stmt.execute("CREATE INDEX IF NOT EXISTS index_read_episodes_readAt ON read_episodes(readAt)")
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS read_history (
                    sourceId TEXT NOT NULL,
                    toonId TEXT NOT NULL,
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
                    hasNew INTEGER DEFAULT 0,
                    PRIMARY KEY (sourceId, toonId)
                )
                """.trimIndent(),
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS index_read_history_toonId ON read_history(toonId)")
            stmt.execute("CREATE INDEX IF NOT EXISTS index_read_history_lastReadAt ON read_history(lastReadAt)")
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS tombstones (
                    entityType TEXT,
                    entityId TEXT,
                    deletedAt INTEGER,
                    PRIMARY KEY (entityType, entityId)
                )
                """.trimIndent(),
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS index_tombstones_deletedAt ON tombstones(deletedAt)")
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS downloaded_episodes (
                    sourceId TEXT NOT NULL,
                    toonId TEXT NOT NULL,
                    wrId TEXT NOT NULL,
                    toonTitle TEXT,
                    toonThumbUrl TEXT,
                    toonHref TEXT,
                    episodeTitle TEXT,
                    episodeHref TEXT,
                    imageCount INTEGER,
                    totalBytes INTEGER,
                    downloadedAt INTEGER,
                    localDirPath TEXT,
                    PRIMARY KEY (sourceId, toonId, wrId)
                )
                """.trimIndent(),
            )
        }
    }

    // --- Tombstones ---
    fun recordTombstone(entityType: String, entityId: String, deletedAt: Long = System.currentTimeMillis()) {
        withConnection { conn ->
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
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM tombstones WHERE entityType = ? AND entityId = ?").use { stmt ->
                stmt.setString(1, entityType)
                stmt.setString(2, entityId)
                stmt.executeUpdate()
            }
        }
    }

    fun getTombstonesSince(since: Long): List<SyncTombstone> {
        val list = mutableListOf<SyncTombstone>()
        withConnection { conn ->
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
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM tombstones WHERE deletedAt < ?").use { stmt ->
                stmt.setLong(1, cutoff)
                stmt.executeUpdate()
            }
        }
    }

    // --- Favorites ---
    fun getAllFavorites(): List<FavoriteRecord> {
        val list = mutableListOf<FavoriteRecord>()
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM favorites ORDER BY COALESCE(updatedAt, '') DESC, savedAt DESC").use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(favoriteFrom(rs))
                }
            }
        }
        return list
    }

    fun getFavoritesSince(since: Long): List<FavoriteRecord> {
        val list = mutableListOf<FavoriteRecord>()
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM favorites WHERE savedAt > ? ORDER BY savedAt DESC").use { stmt ->
                stmt.setLong(1, since)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(favoriteFrom(rs))
                }
            }
        }
        return list
    }

    fun isFavorite(workId: WorkId): Boolean {
        withConnection { conn ->
            conn.prepareStatement("SELECT 1 FROM favorites WHERE sourceId = ? AND toonId = ?").use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                val rs = stmt.executeQuery()
                return rs.next()
            }
        }
    }

    fun getFavorite(workId: WorkId): FavoriteRecord? {
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM favorites WHERE sourceId = ? AND toonId = ?").use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                val rs = stmt.executeQuery()
                if (!rs.next()) return null
                return favoriteFrom(rs)
            }
        }
    }

    fun updateFavoriteUpdatedAt(workId: WorkId, updatedAt: String) {
        if (updatedAt.isBlank()) return
        val current = getFavorite(workId) ?: return
        if (!UpdateDates.shouldReplace(current.updatedAt, updatedAt)) return
        val stored = UpdateDates.toListingDate(updatedAt) ?: updatedAt
        withConnection { conn ->
            conn.prepareStatement(
                "UPDATE favorites SET updatedAt = ? WHERE sourceId = ? AND toonId = ?",
            ).use { stmt ->
                stmt.setString(1, stored)
                stmt.setString(2, workId.sourceId)
                stmt.setString(3, workId.toonId)
                stmt.executeUpdate()
            }
        }
    }

    fun getFavoriteIds(): Set<WorkId> {
        val set = mutableSetOf<WorkId>()
        withConnection { conn ->
            conn.prepareStatement("SELECT sourceId, toonId FROM favorites").use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    set.add(WorkId(rs.getString("sourceId"), rs.getString("toonId")))
                }
            }
        }
        return set
    }

    fun getFavoritesByToonIds(ids: List<WorkId>): List<FavoriteRecord> {
        if (ids.isEmpty()) return emptyList()
        val wanted = ids.map { it.storageKey() }.toSet()
        val toonIds = ids.map { it.toonId }.distinct()
        val list = mutableListOf<FavoriteRecord>()
        withConnection { conn ->
            val placeholders = toonIds.joinToString(",") { "?" }
            conn.prepareStatement("SELECT * FROM favorites WHERE toonId IN ($placeholders)").use { stmt ->
                toonIds.forEachIndexed { index, id -> stmt.setString(index + 1, id) }
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val record = favoriteFrom(rs)
                    if (record.workId().storageKey() in wanted) {
                        list.add(record)
                    }
                }
            }
        }
        return list
    }

    fun saveFavorite(item: FavoriteRecord) {
        val workId = writableId(item.sourceId, item.id) ?: return
        withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO favorites (sourceId, toonId, title, thumbUrl, href, genre, updatedAt, savedAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                stmt.setString(3, item.title)
                stmt.setString(4, item.thumbUrl)
                stmt.setString(5, item.href)
                stmt.setString(6, item.genre)
                stmt.setString(7, item.updatedAt)
                stmt.setLong(8, item.savedAt)
                stmt.executeUpdate()
            }
        }
    }

    fun saveAllFavorites(items: List<FavoriteRecord>) {
        withTransaction { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO favorites (sourceId, toonId, title, thumbUrl, href, genre, updatedAt, savedAt)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                for (item in items) {
                    val workId = WorkId.stored(item.sourceId, item.id) ?: continue
                    stmt.setString(1, workId.sourceId)
                    stmt.setString(2, workId.toonId)
                    stmt.setString(3, item.title)
                    stmt.setString(4, item.thumbUrl)
                    stmt.setString(5, item.href)
                    stmt.setString(6, item.genre)
                    stmt.setString(7, item.updatedAt)
                    stmt.setLong(8, item.savedAt)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    fun deleteFavorite(workId: WorkId) {
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM favorites WHERE sourceId = ? AND toonId = ?").use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                stmt.executeUpdate()
            }
        }
    }

    private fun favoriteFrom(rs: ResultSet): FavoriteRecord = FavoriteRecord(
        sourceId = rs.getString("sourceId"),
        id = rs.getString("toonId"),
        title = rs.getString("title"),
        thumbUrl = rs.getString("thumbUrl"),
        href = rs.getString("href"),
        genre = rs.getString("genre"),
        updatedAt = rs.getString("updatedAt"),
        savedAt = rs.getLong("savedAt"),
    )

    // --- Seen Toons ---
    fun getSeenCount(): Int {
        withConnection { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM seen_toons").use { stmt ->
                val rs = stmt.executeQuery()
                return if (rs.next()) rs.getInt(1) else 0
            }
        }
    }

    fun getSeenByIds(ids: List<WorkId>): Map<String, SeenRecord> {
        if (ids.isEmpty()) return emptyMap()
        val wanted = ids.map { it.storageKey() }.toSet()
        val toonIds = ids.map { it.toonId }.distinct()
        val map = mutableMapOf<String, SeenRecord>()
        withConnection { conn ->
            val placeholders = toonIds.joinToString(",") { "?" }
            conn.prepareStatement("SELECT * FROM seen_toons WHERE toonId IN ($placeholders)").use { stmt ->
                toonIds.forEachIndexed { index, id -> stmt.setString(index + 1, id) }
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val record = seenFrom(rs)
                    val key = record.workId().storageKey()
                    if (key in wanted) {
                        map[key] = record
                    }
                }
            }
        }
        return map
    }

    fun saveAllSeen(items: List<SeenRecord>) {
        if (items.isEmpty()) return
        withTransaction { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO seen_toons (sourceId, toonId, title, updatedAt, firstSeenAt, lastSeenAt, notifiedKey)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                for (item in items) {
                    val workId = writableId(item.sourceId, item.id) ?: continue
                    stmt.setString(1, workId.sourceId)
                    stmt.setString(2, workId.toonId)
                    stmt.setString(3, item.title)
                    stmt.setString(4, item.updatedAt)
                    stmt.setLong(5, item.firstSeenAt)
                    stmt.setLong(6, item.lastSeenAt)
                    stmt.setString(7, item.notifiedKey)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    private fun seenFrom(rs: ResultSet): SeenRecord = SeenRecord(
        sourceId = rs.getString("sourceId"),
        id = rs.getString("toonId"),
        title = rs.getString("title"),
        updatedAt = rs.getString("updatedAt"),
        firstSeenAt = rs.getLong("firstSeenAt"),
        lastSeenAt = rs.getLong("lastSeenAt"),
        notifiedKey = rs.getString("notifiedKey"),
    )

    // --- Read History ---
    fun getAllHistory(): List<ReadHistoryRecord> {
        val list = mutableListOf<ReadHistoryRecord>()
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM read_history ORDER BY lastReadAt DESC").use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(historyFrom(rs))
                }
            }
        }
        return list
    }

    fun getHistoryByToonIds(ids: List<WorkId>): List<ReadHistoryRecord> {
        if (ids.isEmpty()) return emptyList()
        val wanted = ids.map { it.storageKey() }.toSet()
        val toonIds = ids.map { it.toonId }.distinct()
        val list = mutableListOf<ReadHistoryRecord>()
        withConnection { conn ->
            val placeholders = toonIds.joinToString(",") { "?" }
            conn.prepareStatement("SELECT * FROM read_history WHERE toonId IN ($placeholders)").use { stmt ->
                toonIds.forEachIndexed { index, id -> stmt.setString(index + 1, id) }
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val record = historyFrom(rs)
                    if (record.workId().storageKey() in wanted) {
                        list.add(record)
                    }
                }
            }
        }
        return list
    }

    fun getHistoryBySource(sourceId: String): List<ReadHistoryRecord> {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        val list = mutableListOf<ReadHistoryRecord>()
        withConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM read_history WHERE sourceId = ? ORDER BY lastReadAt DESC",
            ).use { stmt ->
                stmt.setString(1, sid)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(historyFrom(rs))
                }
            }
        }
        return list
    }

    fun getHistorySince(since: Long): List<ReadHistoryRecord> {
        val list = mutableListOf<ReadHistoryRecord>()
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM read_history WHERE lastReadAt > ? ORDER BY lastReadAt DESC").use { stmt ->
                stmt.setLong(1, since)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(historyFrom(rs))
                }
            }
        }
        return list
    }

    fun getHistory(workId: WorkId): ReadHistoryRecord? {
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM read_history WHERE sourceId = ? AND toonId = ?").use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return historyFrom(rs)
                }
            }
        }
        return null
    }

    fun saveHistory(history: ReadHistoryRecord) {
        val workId = writableId(history.sourceId, history.toonId) ?: return
        withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO read_history 
                (sourceId, toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref, lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                bindHistory(stmt, history, workId)
                stmt.executeUpdate()
            }
        }
    }

    fun saveAllHistory(items: List<ReadHistoryRecord>) {
        if (items.isEmpty()) return
        withTransaction { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO read_history 
                (sourceId, toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref, lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                for (history in items) {
                    val workId = WorkId.stored(history.sourceId, history.toonId) ?: continue
                    bindHistory(stmt, history, workId)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    fun deleteHistory(workId: WorkId) {
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM read_history WHERE sourceId = ? AND toonId = ?").use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                stmt.executeUpdate()
            }
        }
    }

    fun clearHistoryBySource(sourceId: String) {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM read_history WHERE sourceId = ?").use { stmt ->
                stmt.setString(1, sid)
                stmt.executeUpdate()
            }
        }
    }

    fun clearAllHistory() {
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM read_history").use { stmt ->
                stmt.executeUpdate()
            }
        }
    }

    // --- Read Episodes ---
    fun getAllReadEpisodes(): List<ReadEpisodeRecord> {
        val list = mutableListOf<ReadEpisodeRecord>()
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM read_episodes").use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(episodeFrom(rs))
                }
            }
        }
        return list
    }

    fun getReadEpisodesSince(since: Long): List<ReadEpisodeRecord> {
        val list = mutableListOf<ReadEpisodeRecord>()
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM read_episodes WHERE readAt > ?").use { stmt ->
                stmt.setLong(1, since)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(episodeFrom(rs))
                }
            }
        }
        return list
    }

    fun getReadEpisodesByToon(workId: WorkId): List<ReadEpisodeRecord> {
        val list = mutableListOf<ReadEpisodeRecord>()
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM read_episodes WHERE sourceId = ? AND toonId = ?").use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(episodeFrom(rs))
                }
            }
        }
        return list
    }

    fun countReadEpisodes(workId: WorkId): Int {
        withConnection { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM read_episodes WHERE sourceId = ? AND toonId = ?",
            ).use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                val rs = stmt.executeQuery()
                if (rs.next()) return rs.getInt(1)
            }
        }
        return 0
    }

    fun countReadEpisodes(workIds: List<WorkId>): Map<String, Int> {
        if (workIds.isEmpty()) return emptyMap()
        val wanted = workIds.map { it.storageKey() }.toSet()
        val toonIds = workIds.map { it.toonId }.distinct()
        val counts = mutableMapOf<String, Int>()
        withConnection { conn ->
            val placeholders = toonIds.joinToString(",") { "?" }
            conn.prepareStatement(
                """
                SELECT sourceId, toonId, COUNT(*)
                FROM read_episodes
                WHERE toonId IN ($placeholders)
                GROUP BY sourceId, toonId
                """.trimIndent(),
            ).use { stmt ->
                toonIds.forEachIndexed { index, id -> stmt.setString(index + 1, id) }
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val key = "${rs.getString(1)}:${rs.getString(2)}"
                    if (key in wanted) {
                        counts[key] = rs.getInt(3)
                    }
                }
            }
        }
        return counts
    }

    fun getReadEpisode(workId: WorkId, wrId: String): ReadEpisodeRecord? {
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM read_episodes WHERE sourceId = ? AND toonId = ? AND wrId = ?").use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                stmt.setString(3, wrId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return episodeFrom(rs)
                }
            }
        }
        return null
    }

    fun markEpisodeRead(record: ReadEpisodeRecord) {
        val workId = writableId(record.sourceId, record.toonId) ?: return
        withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO read_episodes (sourceId, toonId, wrId, readAt, lastPage)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                stmt.setString(3, record.wrId)
                stmt.setLong(4, record.readAt)
                stmt.setInt(5, record.lastPage)
                stmt.executeUpdate()
            }
        }
    }

    fun markAllEpisodesRead(items: List<ReadEpisodeRecord>) {
        if (items.isEmpty()) return
        withTransaction { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO read_episodes (sourceId, toonId, wrId, readAt, lastPage)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                for (item in items) {
                    val workId = WorkId.stored(item.sourceId, item.toonId) ?: continue
                    stmt.setString(1, workId.sourceId)
                    stmt.setString(2, workId.toonId)
                    stmt.setString(3, item.wrId)
                    stmt.setLong(4, item.readAt)
                    stmt.setInt(5, item.lastPage)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    fun updateLastPage(workId: WorkId, wrId: String, page: Int, readAt: Long = System.currentTimeMillis()) {
        withConnection { conn ->
            conn.prepareStatement(
                "UPDATE read_episodes SET lastPage = ?, readAt = ? WHERE sourceId = ? AND toonId = ? AND wrId = ?",
            ).use { stmt ->
                stmt.setInt(1, page)
                stmt.setLong(2, readAt)
                stmt.setString(3, workId.sourceId)
                stmt.setString(4, workId.toonId)
                stmt.setString(5, wrId)
                stmt.executeUpdate()
            }
        }
    }

    fun deleteReadEpisodesByToon(workId: WorkId) {
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM read_episodes WHERE sourceId = ? AND toonId = ?").use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                stmt.executeUpdate()
            }
        }
    }

    // --- Reader Settings ---
    fun getAllReaderSettings(): List<ReaderSettingRecord> {
        val list = mutableListOf<ReaderSettingRecord>()
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM reader_settings").use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(settingFrom(rs))
                }
            }
        }
        return list
    }

    fun getReaderSettingsSince(since: Long): List<ReaderSettingRecord> {
        val list = mutableListOf<ReaderSettingRecord>()
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM reader_settings WHERE updatedAt > ?").use { stmt ->
                stmt.setLong(1, since)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(settingFrom(rs))
                }
            }
        }
        return list
    }

    fun getReaderSetting(workId: WorkId): ReaderSettingRecord? {
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM reader_settings WHERE sourceId = ? AND toonId = ?").use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return settingFrom(rs)
                }
            }
        }
        return null
    }

    fun saveReaderSetting(setting: ReaderSettingRecord) {
        val workId = writableId(setting.sourceId, setting.toonId) ?: return
        withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO reader_settings (sourceId, toonId, viewMode, readDirection, splitMode, updatedAt)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                stmt.setString(3, setting.viewMode)
                stmt.setString(4, setting.readDirection)
                stmt.setString(5, setting.splitMode)
                stmt.setLong(6, setting.updatedAt)
                stmt.executeUpdate()
            }
        }
    }

    fun saveAllReaderSettings(items: List<ReaderSettingRecord>) {
        if (items.isEmpty()) return
        withTransaction { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO reader_settings (sourceId, toonId, viewMode, readDirection, splitMode, updatedAt)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                for (item in items) {
                    val workId = WorkId.stored(item.sourceId, item.toonId) ?: continue
                    stmt.setString(1, workId.sourceId)
                    stmt.setString(2, workId.toonId)
                    stmt.setString(3, item.viewMode)
                    stmt.setString(4, item.readDirection)
                    stmt.setString(5, item.splitMode)
                    stmt.setLong(6, item.updatedAt)
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
    }

    // --- Downloaded Episodes ---
    fun getAllDownloadedEpisodes(): List<DownloadedEpisodeRecord> {
        val list = mutableListOf<DownloadedEpisodeRecord>()
        withConnection { conn ->
            conn.prepareStatement("SELECT * FROM downloaded_episodes ORDER BY downloadedAt DESC").use { stmt ->
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(downloadedFrom(rs))
                }
            }
        }
        return list
    }

    fun getDownloadedEpisodesBySource(sourceId: String): List<DownloadedEpisodeRecord> {
        val sid = sourceId.ifBlank { WorkId.DEFAULT_SOURCE }
        val list = mutableListOf<DownloadedEpisodeRecord>()
        withConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM downloaded_episodes WHERE sourceId = ? ORDER BY downloadedAt DESC",
            ).use { stmt ->
                stmt.setString(1, sid)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(downloadedFrom(rs))
                }
            }
        }
        return list
    }

    fun getDownloadedEpisodesByToon(workId: WorkId): List<DownloadedEpisodeRecord> {
        val list = mutableListOf<DownloadedEpisodeRecord>()
        withConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM downloaded_episodes WHERE sourceId = ? AND toonId = ? ORDER BY downloadedAt DESC",
            ).use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    list.add(downloadedFrom(rs))
                }
            }
        }
        return list
    }

    fun getDownloadedEpisode(workId: WorkId, wrId: String): DownloadedEpisodeRecord? {
        withConnection { conn ->
            conn.prepareStatement(
                "SELECT * FROM downloaded_episodes WHERE sourceId = ? AND toonId = ? AND wrId = ?",
            ).use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                stmt.setString(3, wrId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    return downloadedFrom(rs)
                }
            }
        }
        return null
    }

    fun saveDownloadedEpisode(item: DownloadedEpisodeRecord) {
        val workId = writableId(item.sourceId, item.toonId) ?: return
        withConnection { conn ->
            conn.prepareStatement(
                """
                INSERT OR REPLACE INTO downloaded_episodes
                (sourceId, toonId, wrId, toonTitle, toonThumbUrl, toonHref, episodeTitle, episodeHref, imageCount, totalBytes, downloadedAt, localDirPath)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                stmt.setString(3, item.wrId)
                stmt.setString(4, item.toonTitle)
                stmt.setString(5, item.toonThumbUrl)
                stmt.setString(6, item.toonHref)
                stmt.setString(7, item.episodeTitle)
                stmt.setString(8, item.episodeHref)
                stmt.setInt(9, item.imageCount)
                stmt.setLong(10, item.totalBytes)
                stmt.setLong(11, item.downloadedAt)
                stmt.setString(12, item.localDirPath)
                stmt.executeUpdate()
            }
        }
    }

    fun deleteDownloadedEpisode(workId: WorkId, wrId: String) {
        withConnection { conn ->
            conn.prepareStatement(
                "DELETE FROM downloaded_episodes WHERE sourceId = ? AND toonId = ? AND wrId = ?",
            ).use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                stmt.setString(3, wrId)
                stmt.executeUpdate()
            }
        }
    }

    fun deleteDownloadedEpisodesByToon(workId: WorkId) {
        withConnection { conn ->
            conn.prepareStatement("DELETE FROM downloaded_episodes WHERE sourceId = ? AND toonId = ?").use { stmt ->
                stmt.setString(1, workId.sourceId)
                stmt.setString(2, workId.toonId)
                stmt.executeUpdate()
            }
        }
    }

    private fun historyFrom(rs: ResultSet): ReadHistoryRecord = ReadHistoryRecord(
        sourceId = rs.getString("sourceId"),
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

    private fun bindHistory(stmt: java.sql.PreparedStatement, history: ReadHistoryRecord, workId: WorkId) {
        stmt.setString(1, workId.sourceId)
        stmt.setString(2, workId.toonId)
        stmt.setString(3, history.toonTitle)
        stmt.setString(4, history.toonThumbUrl)
        stmt.setString(5, history.toonHref)
        stmt.setString(6, history.lastWrId)
        stmt.setString(7, history.lastEpisodeTitle)
        stmt.setString(8, history.lastEpisodeHref)
        stmt.setInt(9, history.lastReadOrder)
        stmt.setInt(10, history.totalEpisodes)
        stmt.setLong(11, history.lastReadAt)
        stmt.setString(12, history.nextWrId)
        stmt.setString(13, history.nextEpisodeTitle)
        stmt.setString(14, history.nextEpisodeHref)
        stmt.setInt(15, if (history.hasNew) 1 else 0)
    }

    private fun episodeFrom(rs: ResultSet): ReadEpisodeRecord = ReadEpisodeRecord(
        sourceId = rs.getString("sourceId"),
        toonId = rs.getString("toonId"),
        wrId = rs.getString("wrId"),
        readAt = rs.getLong("readAt"),
        lastPage = rs.getInt("lastPage"),
    )

    private fun settingFrom(rs: ResultSet): ReaderSettingRecord = ReaderSettingRecord(
        sourceId = rs.getString("sourceId"),
        toonId = rs.getString("toonId"),
        viewMode = rs.getString("viewMode"),
        readDirection = rs.getString("readDirection"),
        splitMode = try { rs.getString("splitMode") ?: "FIT" } catch (_: Exception) { "FIT" },
        updatedAt = rs.getLong("updatedAt"),
    )

    private fun downloadedFrom(rs: ResultSet): DownloadedEpisodeRecord = DownloadedEpisodeRecord(
        sourceId = rs.getString("sourceId"),
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
