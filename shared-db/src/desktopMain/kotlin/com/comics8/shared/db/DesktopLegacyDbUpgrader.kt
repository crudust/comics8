package com.comics8.shared.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.comics8.core.source.WorkId
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One-time normalizer that upgrades a legacy Desktop JDBC SQLite database (`PRAGMA user_version = 1`
 * without `room_master_table`) into the Room v15 schema before Room opens it.
 */
object DesktopLegacyDbUpgrader {
    private val APP_TABLES = setOf(
        "seen_toons",
        "favorites",
        "reader_settings",
        "read_episodes",
        "read_history",
        "tombstones",
        "downloaded_episodes",
        "toon_catalog",
    )

    fun upgradeIfNeeded(dbFile: File) {
        if (!dbFile.isFile || dbFile.length() == 0L) return

        val driver = BundledSQLiteDriver()
        var needsUpgrade = false

        driver.open(dbFile.absolutePath).use { conn ->
            val userVersion = queryInt(conn, "PRAGMA user_version")
            val hasRoomMaster = hasTable(conn, "room_master_table")
            val existingTables = queryExistingAppTables(conn)
            if (!hasRoomMaster && userVersion <= 1 && existingTables.isNotEmpty()) {
                // Flush any pending WAL frames into the main file before creating the backup copy.
                conn.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
                needsUpgrade = true
            }
        }

        if (!needsUpgrade) return

        val backupFile = File(dbFile.parentFile, "${dbFile.name}.pre-room.bak")
        if (!backupFile.exists()) {
            Files.copy(dbFile.toPath(), backupFile.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        }

        driver.open(dbFile.absolutePath).use { conn ->
            conn.execSQL("PRAGMA busy_timeout = 5000")
            conn.execSQL("BEGIN IMMEDIATE")
            try {
                upgradeAllTables(conn)
                conn.execSQL("PRAGMA user_version = 15")
                conn.execSQL("COMMIT")
            } catch (t: Throwable) {
                runCatching { conn.execSQL("ROLLBACK") }
                throw t
            }
        }
    }

    private fun upgradeAllTables(conn: SQLiteConnection) {
        upgradeSeenToons(conn)
        upgradeFavorites(conn)
        upgradeReaderSettings(conn)
        upgradeReadEpisodes(conn)
        upgradeReadHistory(conn)
        upgradeTombstones(conn)
        upgradeDownloadedEpisodes(conn)
        upgradeToonCatalog(conn)
    }

    private fun upgradeSeenToons(conn: SQLiteConnection) {
        val cols = tableColumns(conn, "seen_toons")
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS seen_toons_room (
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
        )
        if (cols.isNotEmpty()) {
            val sourceExpr = sourceIdExpr(cols)
            val toonIdCol = when {
                "toonId" in cols -> "toonId"
                "id" in cols -> "id"
                else -> null
            }
            if (toonIdCol != null) {
                conn.execSQL(
                    """
                    INSERT OR REPLACE INTO seen_toons_room (
                        sourceId, toonId, title, updatedAt, firstSeenAt, lastSeenAt, notifiedKey
                    )
                    SELECT
                        $sourceExpr,
                        $toonIdCol,
                        ${textColOrEmpty(cols, "title")},
                        ${nullableTextCol(cols, "updatedAt")},
                        ${longColOrZero(cols, "firstSeenAt")},
                        ${longColOrZero(cols, "lastSeenAt")},
                        ${textColOrEmpty(cols, "notifiedKey")}
                    FROM seen_toons
                    WHERE $toonIdCol IS NOT NULL AND $toonIdCol != ''
                    """.trimIndent(),
                )
            }
            conn.execSQL("DROP TABLE seen_toons")
        }
        conn.execSQL("ALTER TABLE seen_toons_room RENAME TO seen_toons")
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_seen_toons_toonId ON seen_toons(toonId)")
    }

    private fun upgradeFavorites(conn: SQLiteConnection) {
        val cols = tableColumns(conn, "favorites")
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS favorites_room (
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
        )
        if (cols.isNotEmpty()) {
            val sourceExpr = sourceIdExpr(cols)
            val toonIdCol = when {
                "toonId" in cols -> "toonId"
                "id" in cols -> "id"
                else -> null
            }
            if (toonIdCol != null) {
                conn.execSQL(
                    """
                    INSERT OR REPLACE INTO favorites_room (
                        sourceId, toonId, title, thumbUrl, href, genre, updatedAt, savedAt
                    )
                    SELECT
                        $sourceExpr,
                        $toonIdCol,
                        ${textColOrEmpty(cols, "title")},
                        ${textColOrEmpty(cols, "thumbUrl")},
                        ${textColOrEmpty(cols, "href")},
                        ${textColOrEmpty(cols, "genre")},
                        ${nullableTextCol(cols, "updatedAt")},
                        ${longColOrZero(cols, "savedAt")}
                    FROM favorites
                    WHERE $toonIdCol IS NOT NULL AND $toonIdCol != ''
                    """.trimIndent(),
                )
            }
            conn.execSQL("DROP TABLE favorites")
        }
        conn.execSQL("ALTER TABLE favorites_room RENAME TO favorites")
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_savedAt ON favorites(savedAt)")
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_toonId ON favorites(toonId)")
    }

    private fun upgradeReaderSettings(conn: SQLiteConnection) {
        val cols = tableColumns(conn, "reader_settings")
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS reader_settings_room (
                sourceId TEXT NOT NULL,
                toonId TEXT NOT NULL,
                viewMode TEXT NOT NULL,
                readDirection TEXT NOT NULL,
                splitMode TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY (sourceId, toonId)
            )
            """.trimIndent(),
        )
        if (cols.isNotEmpty() && "toonId" in cols) {
            val sourceExpr = sourceIdExpr(cols)
            conn.execSQL(
                """
                INSERT OR REPLACE INTO reader_settings_room (
                    sourceId, toonId, viewMode, readDirection, splitMode, updatedAt
                )
                SELECT
                    $sourceExpr,
                    toonId,
                    ${textColOrDefault(cols, "viewMode", "SCROLL")},
                    ${textColOrDefault(cols, "readDirection", "LTR")},
                    ${textColOrDefault(cols, "splitMode", "FIT")},
                    ${longColOrZero(cols, "updatedAt")}
                FROM reader_settings
                WHERE toonId IS NOT NULL AND toonId != ''
                """.trimIndent(),
            )
            conn.execSQL("DROP TABLE reader_settings")
        } else if (cols.isNotEmpty()) {
            conn.execSQL("DROP TABLE reader_settings")
        }
        conn.execSQL("ALTER TABLE reader_settings_room RENAME TO reader_settings")
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_reader_settings_updatedAt ON reader_settings(updatedAt)")
    }

    private fun upgradeReadEpisodes(conn: SQLiteConnection) {
        val cols = tableColumns(conn, "read_episodes")
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS read_episodes_room (
                sourceId TEXT NOT NULL,
                toonId TEXT NOT NULL,
                wrId TEXT NOT NULL,
                readAt INTEGER NOT NULL,
                lastPage INTEGER NOT NULL,
                PRIMARY KEY (sourceId, toonId, wrId)
            )
            """.trimIndent(),
        )
        if (cols.isNotEmpty() && "toonId" in cols && "wrId" in cols) {
            val sourceExpr = sourceIdExpr(cols)
            conn.execSQL(
                """
                INSERT OR REPLACE INTO read_episodes_room (
                    sourceId, toonId, wrId, readAt, lastPage
                )
                SELECT
                    $sourceExpr,
                    toonId,
                    wrId,
                    ${longColOrZero(cols, "readAt")},
                    ${longColOrZero(cols, "lastPage")}
                FROM read_episodes
                WHERE toonId IS NOT NULL AND toonId != '' AND wrId IS NOT NULL AND wrId != ''
                """.trimIndent(),
            )
            conn.execSQL("DROP TABLE read_episodes")
        } else if (cols.isNotEmpty()) {
            conn.execSQL("DROP TABLE read_episodes")
        }
        conn.execSQL("ALTER TABLE read_episodes_room RENAME TO read_episodes")
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_read_episodes_toonId ON read_episodes(toonId)")
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_read_episodes_readAt ON read_episodes(readAt)")
    }

    private fun upgradeReadHistory(conn: SQLiteConnection) {
        val cols = tableColumns(conn, "read_history")
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS read_history_room (
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
        )
        if (cols.isNotEmpty() && "toonId" in cols) {
            val sourceExpr = sourceIdExpr(cols)
            conn.execSQL(
                """
                INSERT OR REPLACE INTO read_history_room (
                    sourceId, toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle,
                    lastEpisodeHref, lastReadOrder, totalEpisodes, lastReadAt, nextWrId,
                    nextEpisodeTitle, nextEpisodeHref, hasNew
                )
                SELECT
                    $sourceExpr,
                    toonId,
                    ${textColOrEmpty(cols, "toonTitle")},
                    ${textColOrEmpty(cols, "toonThumbUrl")},
                    ${textColOrEmpty(cols, "toonHref")},
                    ${textColOrEmpty(cols, "lastWrId")},
                    ${textColOrEmpty(cols, "lastEpisodeTitle")},
                    ${textColOrEmpty(cols, "lastEpisodeHref")},
                    ${longColOrZero(cols, "lastReadOrder")},
                    ${longColOrZero(cols, "totalEpisodes")},
                    ${longColOrZero(cols, "lastReadAt")},
                    ${nullableTextCol(cols, "nextWrId")},
                    ${nullableTextCol(cols, "nextEpisodeTitle")},
                    ${nullableTextCol(cols, "nextEpisodeHref")},
                    ${longColOrZero(cols, "hasNew")}
                FROM read_history
                WHERE toonId IS NOT NULL AND toonId != ''
                """.trimIndent(),
            )
            conn.execSQL("DROP TABLE read_history")
        } else if (cols.isNotEmpty()) {
            conn.execSQL("DROP TABLE read_history")
        }
        conn.execSQL("ALTER TABLE read_history_room RENAME TO read_history")
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_read_history_lastReadAt ON read_history(lastReadAt)")
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_read_history_toonId ON read_history(toonId)")
    }

    private fun upgradeTombstones(conn: SQLiteConnection) {
        val cols = tableColumns(conn, "tombstones")
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tombstones_room (
                entityType TEXT NOT NULL,
                entityId TEXT NOT NULL,
                deletedAt INTEGER NOT NULL,
                PRIMARY KEY (entityType, entityId)
            )
            """.trimIndent(),
        )
        if (cols.isNotEmpty() && "entityType" in cols && "entityId" in cols) {
            conn.execSQL(
                """
                INSERT OR REPLACE INTO tombstones_room (entityType, entityId, deletedAt)
                SELECT
                    entityType,
                    entityId,
                    ${longColOrZero(cols, "deletedAt")}
                FROM tombstones
                WHERE entityType IS NOT NULL AND entityType != ''
                  AND entityId IS NOT NULL AND entityId != ''
                """.trimIndent(),
            )
            conn.execSQL("DROP TABLE tombstones")
        } else if (cols.isNotEmpty()) {
            conn.execSQL("DROP TABLE tombstones")
        }
        conn.execSQL("ALTER TABLE tombstones_room RENAME TO tombstones")
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_tombstones_deletedAt ON tombstones(deletedAt)")
    }

    private fun upgradeDownloadedEpisodes(conn: SQLiteConnection) {
        val cols = tableColumns(conn, "downloaded_episodes")
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS downloaded_episodes_room (
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
        )
        if (cols.isNotEmpty() && "toonId" in cols && "wrId" in cols) {
            val sourceExpr = sourceIdExpr(cols)
            conn.execSQL(
                """
                INSERT OR REPLACE INTO downloaded_episodes_room (
                    sourceId, toonId, wrId, toonTitle, toonThumbUrl, toonHref, episodeTitle,
                    episodeHref, imageCount, totalBytes, downloadedAt, localDirPath
                )
                SELECT
                    $sourceExpr,
                    toonId,
                    wrId,
                    ${textColOrEmpty(cols, "toonTitle")},
                    ${textColOrEmpty(cols, "toonThumbUrl")},
                    ${textColOrEmpty(cols, "toonHref")},
                    ${textColOrEmpty(cols, "episodeTitle")},
                    ${textColOrEmpty(cols, "episodeHref")},
                    ${longColOrZero(cols, "imageCount")},
                    ${longColOrZero(cols, "totalBytes")},
                    ${longColOrZero(cols, "downloadedAt")},
                    ${textColOrEmpty(cols, "localDirPath")}
                FROM downloaded_episodes
                WHERE toonId IS NOT NULL AND toonId != '' AND wrId IS NOT NULL AND wrId != ''
                """.trimIndent(),
            )
            conn.execSQL("DROP TABLE downloaded_episodes")
        } else if (cols.isNotEmpty()) {
            conn.execSQL("DROP TABLE downloaded_episodes")
        }
        conn.execSQL("ALTER TABLE downloaded_episodes_room RENAME TO downloaded_episodes")
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_downloaded_episodes_toonId ON downloaded_episodes(toonId)")
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_downloaded_episodes_downloadedAt ON downloaded_episodes(downloadedAt)")
    }

    private fun upgradeToonCatalog(conn: SQLiteConnection) {
        val cols = tableColumns(conn, "toon_catalog")
        conn.execSQL(
            """
            CREATE TABLE IF NOT EXISTS toon_catalog_room (
                sourceId TEXT NOT NULL,
                toonId TEXT NOT NULL,
                totalEpisodes INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                lastPage INTEGER NOT NULL,
                pageSize INTEGER NOT NULL,
                delta INTEGER NOT NULL,
                PRIMARY KEY (sourceId, toonId)
            )
            """.trimIndent(),
        )
        val hasShapeColumns = "lastPage" in cols && "pageSize" in cols && "delta" in cols
        if (cols.isNotEmpty() && "toonId" in cols && hasShapeColumns) {
            val sourceExpr = sourceIdExpr(cols)
            conn.execSQL(
                """
                INSERT OR REPLACE INTO toon_catalog_room (
                    sourceId, toonId, totalEpisodes, updatedAt, lastPage, pageSize, delta
                )
                SELECT
                    $sourceExpr,
                    toonId,
                    ${longColOrZero(cols, "totalEpisodes")},
                    ${longColOrZero(cols, "updatedAt")},
                    ${longColOrZero(cols, "lastPage")},
                    ${longColOrZero(cols, "pageSize")},
                    ${longColOrZero(cols, "delta")}
                FROM toon_catalog
                WHERE toonId IS NOT NULL AND toonId != ''
                """.trimIndent(),
            )
        }
        if (cols.isNotEmpty()) {
            conn.execSQL("DROP TABLE toon_catalog")
        }
        conn.execSQL("ALTER TABLE toon_catalog_room RENAME TO toon_catalog")
        conn.execSQL("CREATE INDEX IF NOT EXISTS index_toon_catalog_toonId ON toon_catalog(toonId)")
    }

    private fun sourceIdExpr(cols: Set<String>): String =
        if ("sourceId" in cols) {
            "COALESCE(NULLIF(sourceId, ''), '${WorkId.DEFAULT_SOURCE}')"
        } else {
            "'${WorkId.DEFAULT_SOURCE}'"
        }

    private fun textColOrEmpty(cols: Set<String>, col: String): String =
        if (col in cols) "COALESCE($col, '')" else "''"

    private fun textColOrDefault(cols: Set<String>, col: String, default: String): String =
        if (col in cols) "COALESCE(NULLIF($col, ''), '$default')" else "'$default'"

    private fun nullableTextCol(cols: Set<String>, col: String): String =
        if (col in cols) col else "NULL"

    private fun longColOrZero(cols: Set<String>, col: String): String =
        if (col in cols) "COALESCE($col, 0)" else "0"

    private fun queryInt(conn: SQLiteConnection, sql: String): Int =
        conn.prepare(sql).use { stmt ->
            if (stmt.step()) stmt.getLong(0).toInt() else 0
        }

    private fun hasTable(conn: SQLiteConnection, tableName: String): Boolean =
        conn.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use { stmt ->
            stmt.bindText(1, tableName)
            stmt.step()
        }

    private fun queryExistingAppTables(conn: SQLiteConnection): Set<String> {
        val found = mutableSetOf<String>()
        conn.prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { stmt ->
            while (stmt.step()) {
                val name = stmt.getText(0)
                if (name in APP_TABLES) found.add(name)
            }
        }
        return found
    }

    private fun tableColumns(conn: SQLiteConnection, table: String): Set<String> {
        val cols = mutableSetOf<String>()
        conn.prepare("PRAGMA table_info($table)").use { stmt ->
            while (stmt.step()) {
                cols.add(stmt.getText(1))
            }
        }
        return cols
    }
}
