package com.comics8.core.sync

import com.comics8.core.backup.BackupEpisodeWire
import com.comics8.core.backup.BackupFavoriteWire
import com.comics8.core.backup.BackupHistoryWire
import com.comics8.core.backup.BackupReaderSettingWire
import com.comics8.core.backup.BackupWireCodec
import com.comics8.core.source.SourceBindingManager
import com.comics8.core.source.WorkId
import com.comics8.core.storage.ComicLocalStore
import com.comics8.core.storage.FavoriteRecord
import com.comics8.core.storage.KeyValuePrefs
import com.comics8.core.storage.ReadEpisodeRecord
import com.comics8.core.storage.ReadHistoryRecord
import com.comics8.core.storage.ReaderSettingRecord
import com.comics8.core.storage.TombstoneRecord
import com.comics8.core.storage.ToonCatalogRecord
import org.json.JSONObject

class StoreSyncStorageAdapter(
    private val store: ComicLocalStore,
    private val prefs: KeyValuePrefs,
    private val bindingManager: SourceBindingManager = SourceBindingManager.global,
    private val requireTitleFields: Boolean = false,
) : SyncStorageAdapter {

    override fun getPreference(key: String, defaultValue: String?): String? =
        prefs.getString(key, defaultValue)

    override fun setPreference(key: String, value: String?) {
        prefs.putString(key, value)
    }

    override suspend fun deleteOldTombstones(cutoff: Long) {
        store.deleteTombstonesOlderThan(cutoff)
    }

    override suspend fun getLocalChangesSince(since: Long): JSONObject {
        val payload = syncPayload(
            favorites = store.getFavoritesSince(since),
            history = store.getHistorySince(since),
            episodes = store.getReadEpisodesSince(since),
            settings = store.getReaderSettingsSince(since),
            tombstones = store.getTombstonesSince(since),
        )
        return SyncPayloadCodec.encode(payload)
    }

    override suspend fun getFullSnapshot(): JSONObject = SyncPayloadCodec.encode(
        syncPayload(
            favorites = store.getAllFavorites(),
            history = store.getAllHistory(),
            episodes = store.getAllReadEpisodes(),
            settings = store.getAllReaderSettings(),
        ),
        includeMetadata = true,
    )

    private fun syncPayload(
        favorites: List<FavoriteRecord>,
        history: List<ReadHistoryRecord>,
        episodes: List<ReadEpisodeRecord>,
        settings: List<ReaderSettingRecord>,
        tombstones: List<TombstoneRecord> = emptyList(),
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
            val title = if (requireTitleFields) requireNotNull(item.title) { "Missing favorite title" } else item.title.orEmpty()
            FavoriteRecord(
                sourceId = workId.sourceId,
                id = workId.toonId,
                title = title,
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
            val toonTitle = if (requireTitleFields) requireNotNull(item.toonTitle) { "Missing history title" } else item.toonTitle.orEmpty()
            val lastWrId = if (requireTitleFields) requireNotNull(item.lastWrId) { "Missing episode id" } else item.lastWrId.orEmpty()
            ReadHistoryRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                toonTitle = toonTitle,
                toonThumbUrl = item.toonThumbUrl,
                toonHref = item.toonHref,
                lastWrId = lastWrId,
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
            val wrId = if (requireTitleFields) requireNotNull(item.wrId) { "Missing episode id" } else item.wrId.orEmpty()
            ReadEpisodeRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                wrId = wrId,
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

        var savedHistoryCount = 0
        store.inTransaction {
            if (applyTombstones) {
                payload.tombstones.forEach { tombstone ->
                    val workId = WorkId.parse(tombstone.entityId)
                    when (tombstone.entityType) {
                        "FAVORITE" -> store.deleteFavorite(workId)
                        "HISTORY" -> store.deleteHistory(workId)
                        "EPISODE" -> store.deleteReadEpisodesByToon(workId)
                    }
                }
            }
            if (favorites.isNotEmpty()) store.upsertFavorites(favorites)
            if (history.isNotEmpty()) {
                val existingMap = store.getHistoryByWorkIds(history.map { it.workId() })
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
                    store.upsertHistoryAll(historyToSave)
                }
                savedHistoryCount = historyToSave.size
            }
            if (episodes.isNotEmpty()) store.upsertReadEpisodes(episodes)
            if (settings.isNotEmpty()) store.upsertReaderSettings(settings)

            // Totals only ever come from counting a listing, so history rows (which merely echo a
            // count) are not a catalog source.
            val directCatalog = payload.catalog.mapNotNull { wire ->
                wire.total()?.let { counted ->
                    val localSourceId = bindingManager.getLocalSourceId(wire.sourceId)
                    ToonCatalogRecord.of(
                        WorkId(localSourceId, wire.toonId),
                        counted,
                        wire.updatedAt,
                    )
                }
            }
            if (directCatalog.isNotEmpty()) {
                store.upsertCatalogEntries(directCatalog)
            }
        }
        return SyncApplyCounts(favorites = favorites.size, history = savedHistoryCount, episodes = episodes.size)
    }
}
