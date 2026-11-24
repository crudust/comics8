package com.comics8.desktop.data

import com.comics8.core.data.SharedDownloadManager
import com.comics8.core.network.ToonClient
import com.comics8.core.source.SourceRegistry
import com.comics8.shared.db.AppDatabase
import com.comics8.shared.db.RoomComicLocalStore
import java.io.File

class DesktopDownloadManager(
    database: AppDatabase,
    client: ToonClient,
    isSourceEnabled: (String) -> Boolean = { false },
    baseDir: File = File(System.getProperty("user.home"), ".comics8/downloads"),
    sources: SourceRegistry,
    installedIds: () -> Set<String> = { sources.knownIds() },
) : SharedDownloadManager(
    store = RoomComicLocalStore(database),
    client = client,
    isSourceEnabled = isSourceEnabled,
    baseDir = baseDir,
    sources = sources,
    installedIds = installedIds,
)
