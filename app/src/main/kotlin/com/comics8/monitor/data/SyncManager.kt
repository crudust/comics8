package com.comics8.monitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
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
import com.comics8.core.sync.SyncApplyCounts
import com.comics8.core.sync.SyncConstants
import com.comics8.core.sync.SyncStorageAdapter
import com.comics8.core.sync.SyncPayload
import com.comics8.core.sync.SyncPayloadCodec
import com.comics8.core.sync.SyncTombstoneWire
import com.comics8.core.sync.SyncWire
import com.comics8.monitor.data.local.AppDatabase
import com.comics8.monitor.data.local.FavoriteToon
import com.comics8.monitor.data.local.ReadEpisode
import com.comics8.monitor.data.local.ReadHistory
import com.comics8.monitor.data.local.ReaderSetting
import com.comics8.monitor.data.local.Tombstone
import com.comics8.monitor.data.local.delete
import com.comics8.monitor.data.local.deleteByToonId
import com.comics8.monitor.data.local.upsertAllMonotonic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

typealias AndroidSyncState = SyncState
typealias AndroidSyncResult = SyncResult

class AndroidRoomSyncStorageAdapter(
    private val context: Context,
    private val database: AppDatabase,
) : SyncStorageAdapter {

    private val prefs: SharedPreferences = context.getSharedPreferences("comics8_sync", Context.MODE_PRIVATE)

    override fun getPreference(key: String, defaultValue: String?): String? {
        return prefs.getString(key, defaultValue)
    }

    override fun setPreference(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, value).apply()
        }
    }

    override suspend fun deleteOldTombstones(cutoff: Long) {
        database.tombstoneDao().deleteOlderThan(cutoff)
    }

    override suspend fun getLocalChangesSince(since: Long): JSONObject {
        val payload = syncPayload(
            favorites = database.favoriteDao().getSince(since),
            history = database.readHistoryDao().getSince(since),
            episodes = database.readEpisodeDao().getSince(since),
            settings = database.readerSettingDao().getSince(since),
            tombstones = database.tombstoneDao().getSince(since),
        )
        return SyncPayloadCodec.encode(payload)
    }

    override suspend fun getFullSnapshot(): JSONObject = SyncPayloadCodec.encode(
        syncPayload(
            favorites = database.favoriteDao().all(),
            history = database.readHistoryDao().all(),
            episodes = database.readEpisodeDao().all(),
            settings = database.readerSettingDao().all(),
        ),
        includeMetadata = true,
    )

    private fun syncPayload(
        favorites: List<FavoriteToon>,
        history: List<ReadHistory>,
        episodes: List<ReadEpisode>,
        settings: List<ReaderSetting>,
        tombstones: List<Tombstone> = emptyList(),
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
    ): SyncApplyCounts = applyPayload(serverChanges, serverTime, applyTombstones = true)

    override suspend fun applyFullSnapshot(
        snapshot: JSONObject,
        serverTime: Long,
    ): SyncApplyCounts = applyPayload(snapshot, serverTime, applyTombstones = false)

    private suspend fun applyPayload(
        root: JSONObject,
        serverTime: Long,
        applyTombstones: Boolean,
    ): SyncApplyCounts {
        val payload = SyncPayloadCodec.decode(root, serverTime)
        val favorites = payload.favorites.mapNotNull { item ->
            val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = true)
                ?: return@mapNotNull null
            FavoriteToon(
                sourceId = workId.sourceId,
                id = workId.toonId,
                title = requireNotNull(item.title) { "Missing favorite title" },
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
            ReadHistory(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                toonTitle = requireNotNull(item.toonTitle) { "Missing history title" },
                toonThumbUrl = item.toonThumbUrl,
                toonHref = item.toonHref,
                lastWrId = requireNotNull(item.lastWrId) { "Missing episode id" },
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
            ReadEpisode(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                wrId = requireNotNull(item.wrId) { "Missing episode id" },
                readAt = item.readAt,
                lastPage = item.lastPage,
            )
        }
        val settings = payload.readerSettings.mapNotNull { item ->
            val workId = BackupWireCodec.workId(item.sourceId, item.id, item.toonId, preferId = false)
                ?: return@mapNotNull null
            ReaderSetting(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                viewMode = item.viewMode,
                readDirection = item.readDirection,
                splitMode = item.splitMode,
                updatedAt = item.updatedAt,
            )
        }

        var savedHistoryCount = 0
        database.withTransaction {
            if (applyTombstones) {
                payload.tombstones.forEach { tombstone ->
                    val workId = WorkId.parse(tombstone.entityId)
                    when (tombstone.entityType) {
                        "FAVORITE" -> database.favoriteDao().delete(workId)
                        "HISTORY" -> database.readHistoryDao().delete(workId)
                        "EPISODE" -> database.readEpisodeDao().deleteByToonId(workId)
                    }
                }
            }
            if (favorites.isNotEmpty()) database.favoriteDao().upsertAll(favorites)
            if (history.isNotEmpty()) {
                val existingMap = database.readHistoryDao().byToonIds(history.map { it.toonId }.distinct())
                    .associateBy { it.workId().storageKey() }
                val historyToSave = history.filter { incoming ->
                    val local = existingMap[incoming.workId().storageKey()]
                    if (local == null) true
                    else {
                        incoming.lastReadOrder > local.lastReadOrder ||
                            (incoming.lastReadOrder == local.lastReadOrder && incoming.lastReadAt >= local.lastReadAt)
                    }
                }
                if (historyToSave.isNotEmpty()) {
                    database.readHistoryDao().upsertAll(historyToSave)
                }
                savedHistoryCount = historyToSave.size
            }
            if (episodes.isNotEmpty()) database.readEpisodeDao().markReadAll(episodes)
            if (settings.isNotEmpty()) database.readerSettingDao().saveAll(settings)

            val catalogFromHistory = history.filter { it.totalEpisodes > 0 }.map {
                com.comics8.monitor.data.local.ToonCatalogEntry(
                    sourceId = it.sourceId,
                    id = it.toonId,
                    totalEpisodes = it.totalEpisodes,
                    updatedAt = it.lastReadAt,
                )
            }
            val directCatalog = payload.catalog.map {
                com.comics8.monitor.data.local.ToonCatalogEntry(
                    sourceId = it.sourceId,
                    id = it.toonId,
                    totalEpisodes = it.totalEpisodes,
                    updatedAt = it.updatedAt,
                )
            }
            val allCatalog = catalogFromHistory + directCatalog
            if (allCatalog.isNotEmpty()) {
                database.toonCatalogDao().upsertAllMonotonic(allCatalog)
            }
        }
        return SyncApplyCounts(favorites = favorites.size, history = savedHistoryCount, episodes = episodes.size)
    }
}

class SyncManager(
    context: Context,
    database: AppDatabase,
    toonClient: ToonClient? = null,
    client: OkHttpClient = OkHttpClient.Builder()
        .dns(com.comics8.core.network.FallbackDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) : BaseSyncManager(
    storage = AndroidRoomSyncStorageAdapter(context, database),
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
