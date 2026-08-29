package com.comics8.desktop.data

import com.comics8.core.backup.BackupEpisodeWire
import com.comics8.core.backup.BackupFavoriteWire
import com.comics8.core.backup.BackupHistoryWire
import com.comics8.core.backup.BackupReaderSettingWire
import com.comics8.core.backup.BackupWireCodec
import com.comics8.core.model.SyncResult
import com.comics8.core.model.SyncState
import com.comics8.core.network.ToonClient
import com.comics8.core.source.WorkId
import com.comics8.core.sync.BaseSyncManager
import com.comics8.core.sync.SyncConstants
import com.comics8.core.sync.SyncStorageAdapter
import com.comics8.core.sync.SyncPayload
import com.comics8.core.sync.SyncPayloadCodec
import com.comics8.core.sync.SyncTombstoneWire
import com.comics8.core.sync.SyncWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

typealias DesktopSyncState = SyncState
typealias DesktopSyncResult = SyncResult

class DesktopSyncStorageAdapter(
    private val database: DesktopDatabase,
) : SyncStorageAdapter {

    private val configFile = File(System.getProperty("user.home"), ".comics8/sync.properties")
    private val properties = Properties().apply {
        if (configFile.exists()) {
            try {
                configFile.inputStream().use { load(it) }
            } catch (_: Exception) {}
        }
    }

    private fun saveProperties() {
        try {
            configFile.parentFile?.mkdirs()
            configFile.outputStream().use { properties.store(it, "Comics8 Sync Configuration") }
        } catch (_: Exception) {}
    }

    override fun getPreference(key: String, defaultValue: String?): String? {
        return properties.getProperty(key, defaultValue)
    }

    override fun setPreference(key: String, value: String?) {
        if (value == null) {
            properties.remove(key)
        } else {
            properties.setProperty(key, value)
        }
        saveProperties()
    }

    override suspend fun deleteOldTombstones(cutoff: Long) {
        database.deleteTombstonesOlderThan(cutoff)
    }

    override suspend fun getLocalChangesSince(since: Long): JSONObject = SyncPayloadCodec.encode(
        syncPayload(
            favorites = database.getFavoritesSince(since),
            history = database.getHistorySince(since),
            episodes = database.getReadEpisodesSince(since),
            settings = database.getReaderSettingsSince(since),
            tombstones = database.getTombstonesSince(since),
        ),
    )

    override suspend fun getFullSnapshot(): JSONObject = SyncPayloadCodec.encode(
        syncPayload(
            favorites = database.getAllFavorites(),
            history = database.getAllHistory(),
            episodes = database.getAllReadEpisodes(),
            settings = database.getAllReaderSettings(),
        ),
        includeMetadata = true,
    )

    private fun syncPayload(
        favorites: List<FavoriteRecord>,
        history: List<ReadHistoryRecord>,
        episodes: List<ReadEpisodeRecord>,
        settings: List<ReaderSettingRecord>,
        tombstones: List<com.comics8.core.model.SyncTombstone> = emptyList(),
    ): SyncPayload = SyncPayload(
        favorites = favorites.filter { SyncWire.isSyncableSource(it.sourceId) }.map { item ->
            BackupFavoriteWire(
                sourceId = item.sourceId,
                id = item.id,
                title = item.title,
                thumbUrl = item.thumbUrl,
                href = item.href,
                genre = item.genre,
                updatedAt = item.updatedAt,
                savedAt = item.savedAt,
            )
        },
        history = history.filter { SyncWire.isSyncableSource(it.sourceId) }.map { item ->
            BackupHistoryWire(
                sourceId = item.sourceId,
                toonId = item.toonId,
                toonTitle = item.toonTitle,
                toonThumbUrl = item.toonThumbUrl,
                toonHref = item.toonHref,
                lastWrId = item.lastWrId,
                lastEpisodeTitle = item.lastEpisodeTitle,
                lastEpisodeHref = item.lastEpisodeHref,
                lastReadOrder = item.lastReadOrder,
                totalEpisodes = item.totalEpisodes,
                lastReadAt = item.lastReadAt,
                nextWrId = item.nextWrId,
                nextEpisodeTitle = item.nextEpisodeTitle,
                nextEpisodeHref = item.nextEpisodeHref,
                hasNew = item.hasNew,
            )
        },
        readEpisodes = episodes.filter { SyncWire.isSyncableSource(it.sourceId) }.map { item ->
            BackupEpisodeWire(
                sourceId = item.sourceId,
                toonId = item.toonId,
                wrId = item.wrId,
                readAt = item.readAt,
                lastPage = item.lastPage,
            )
        },
        readerSettings = settings.filter { SyncWire.isSyncableSource(it.sourceId) }.map { item ->
            BackupReaderSettingWire(
                sourceId = item.sourceId,
                toonId = item.toonId,
                viewMode = item.viewMode,
                readDirection = item.readDirection,
                splitMode = item.splitMode,
                updatedAt = item.updatedAt,
            )
        },
        tombstones = tombstones
            .filter { SyncWire.isSyncableSource(WorkId.parse(it.entityId).sourceId) }
            .map { SyncTombstoneWire(it.entityType, it.entityId, it.deletedAt) },
    )
    override suspend fun applyRemoteChanges(
        serverChanges: JSONObject,
        serverTime: Long,
    ): Pair<Int, Int> = applyPayload(serverChanges, serverTime, applyTombstones = true)

    override suspend fun applyFullSnapshot(
        snapshot: JSONObject,
        serverTime: Long,
    ): Pair<Int, Int> = applyPayload(snapshot, serverTime, applyTombstones = false)

    private suspend fun applyPayload(
        root: JSONObject,
        serverTime: Long,
        applyTombstones: Boolean,
    ): Pair<Int, Int> {
        val payload = SyncPayloadCodec.decode(root, serverTime)
        val favorites = payload.favorites.mapNotNull { item ->
            val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = true)
                ?: return@mapNotNull null
            FavoriteRecord(
                sourceId = workId.sourceId,
                id = workId.toonId,
                title = item.title.orEmpty(),
                thumbUrl = item.thumbUrl,
                href = item.href,
                genre = item.genre,
                updatedAt = item.updatedAt,
                savedAt = item.savedAt,
            )
        }
        val history = payload.history.mapNotNull { item ->
            val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = false)
                ?: return@mapNotNull null
            ReadHistoryRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                toonTitle = item.toonTitle.orEmpty(),
                toonThumbUrl = item.toonThumbUrl,
                toonHref = item.toonHref,
                lastWrId = item.lastWrId.orEmpty(),
                lastEpisodeTitle = item.lastEpisodeTitle.orEmpty(),
                lastEpisodeHref = item.lastEpisodeHref,
                lastReadOrder = item.lastReadOrder,
                totalEpisodes = item.totalEpisodes,
                lastReadAt = item.lastReadAt,
                nextWrId = item.nextWrId?.ifBlank { null },
                nextEpisodeTitle = item.nextEpisodeTitle?.ifBlank { null },
                nextEpisodeHref = item.nextEpisodeHref?.ifBlank { null },
                hasNew = item.hasNew,
            )
        }
        val episodes = payload.readEpisodes.mapNotNull { item ->
            val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = false)
                ?: return@mapNotNull null
            ReadEpisodeRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                wrId = item.wrId.orEmpty(),
                readAt = item.readAt,
                lastPage = item.lastPage,
            )
        }
        val settings = payload.readerSettings.mapNotNull { item ->
            val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = false)
                ?: return@mapNotNull null
            ReaderSettingRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                viewMode = item.viewMode,
                readDirection = item.readDirection,
                splitMode = item.splitMode,
                updatedAt = item.updatedAt,
            )
        }
        val deletions = if (applyTombstones) {
            payload.tombstones.map { it.entityType to WorkId.parse(it.entityId) }
        } else {
            emptyList()
        }
        database.applySyncBatch(deletions, favorites, history, episodes, settings)
        return favorites.size to history.size
    }
}

class DesktopSyncManager(
    private val database: DesktopDatabase,
    toonClient: ToonClient? = null,
    client: OkHttpClient = OkHttpClient.Builder()
        .dns(com.comics8.core.network.FallbackDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) : BaseSyncManager(
    storage = DesktopSyncStorageAdapter(database),
    toonClient = toonClient,
    client = client,
) {
    companion object {
        val DEFAULT_SERVER_URL: String get() = SyncConstants.DEFAULT_SERVER_URL
        const val KEY_SYNC_KEY = SyncConstants.KEY_SYNC_KEY
        const val KEY_SERVER_URL = SyncConstants.KEY_SERVER_URL
        const val KEY_AUTO_SYNC = SyncConstants.KEY_AUTO_SYNC
        const val KEY_LAST_SYNCED_AT = SyncConstants.KEY_LAST_SYNCED_AT
        const val KEY_USE_SERVER_PROXY = SyncConstants.KEY_USE_SERVER_PROXY

        fun generateSyncKey(): String = SyncConstants.generateSyncKey()
    }
}
