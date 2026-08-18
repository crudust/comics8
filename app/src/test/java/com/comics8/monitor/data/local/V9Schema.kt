package com.comics8.monitor.data.local

object V9Schema {
    val statements: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS seen_toons (
            id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            updatedAt TEXT,
            firstSeenAt INTEGER NOT NULL,
            lastSeenAt INTEGER NOT NULL,
            notifiedKey TEXT NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS favorites (
            id TEXT PRIMARY KEY NOT NULL,
            title TEXT NOT NULL,
            thumbUrl TEXT NOT NULL,
            href TEXT NOT NULL,
            genre TEXT NOT NULL,
            updatedAt TEXT,
            savedAt INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_favorites_savedAt ON favorites(savedAt)",
        """
        CREATE TABLE IF NOT EXISTS reader_settings (
            toonId TEXT PRIMARY KEY NOT NULL,
            viewMode TEXT NOT NULL,
            readDirection TEXT NOT NULL,
            updatedAt INTEGER NOT NULL
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS read_episodes (
            toonId TEXT NOT NULL,
            wrId TEXT NOT NULL,
            readAt INTEGER NOT NULL,
            lastPage INTEGER NOT NULL,
            PRIMARY KEY (toonId, wrId)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS read_history (
            toonId TEXT PRIMARY KEY NOT NULL,
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
            hasNew INTEGER NOT NULL
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_read_history_lastReadAt ON read_history(lastReadAt)",
        """
        CREATE TABLE IF NOT EXISTS tombstones (
            entityType TEXT NOT NULL,
            entityId TEXT NOT NULL,
            deletedAt INTEGER NOT NULL,
            PRIMARY KEY (entityType, entityId)
        )
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS downloaded_episodes (
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
            PRIMARY KEY (toonId, wrId)
        )
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS index_downloaded_episodes_toonId ON downloaded_episodes(toonId)",
        "CREATE INDEX IF NOT EXISTS index_downloaded_episodes_downloadedAt ON downloaded_episodes(downloadedAt)",
        "INSERT INTO favorites VALUES ('123','원피스','t','h','액션','08.16',1)",
        "INSERT INTO seen_toons VALUES ('123','원피스','08.16',1,2,'123|999')",
        "INSERT INTO reader_settings VALUES ('123','SCROLL','RIGHT_TO_LEFT',1)",
        "INSERT INTO read_history VALUES ('123','원피스','t','h','999','ep','eh',1,10,1,NULL,NULL,NULL,0)",
        "INSERT INTO read_episodes VALUES ('123','999',1,5)",
        "INSERT INTO downloaded_episodes VALUES ('123','999','원피스','t','h','ep','eh',3,10,1,'/old')",
        "INSERT INTO tombstones VALUES ('FAVORITE','456',1)",
    )
}
