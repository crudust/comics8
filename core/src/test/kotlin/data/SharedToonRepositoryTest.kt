package com.comics8.core.data

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodeListingTotal
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem
import com.comics8.core.network.ToonClient
import com.comics8.core.source.ComicSource
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceRegistry
import com.comics8.core.source.WorkId
import com.comics8.core.storage.ComicLocalStore
import com.comics8.core.storage.DownloadedEpisodeRecord
import com.comics8.core.storage.FavoriteRecord
import com.comics8.core.storage.KeyValuePrefs
import com.comics8.core.storage.ReadEpisodeRecord
import com.comics8.core.storage.ReadHistoryRecord
import com.comics8.core.storage.ReaderSettingRecord
import com.comics8.core.storage.SeenRecord
import com.comics8.core.storage.TombstoneRecord
import com.comics8.core.storage.ToonCatalogRecord
import com.comics8.core.sync.StoreSyncStorageAdapter
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SharedToonRepositoryTest {
    @Test
    fun backupRoundTripAndRepositoryFavoriteToggleWork() = runBlocking {
        val store = InMemoryComicLocalStore()
        val registry = SourceRegistry(listOf(StubSource()))
        val repo = SharedToonRepository(
            client = ToonClient(sources = { registry }),
            store = store,
            sources = registry,
            isSourceEnabled = { true },
            installedIds = { setOf("stub") },
            now = { 1_700_000_000_000L },
        )

        val item = ToonItem(
            id = "w1",
            title = "Test Work",
            thumbUrl = "https://example.com/t.jpg",
            href = "https://example.com/w1",
            sourceId = "stub",
        )
        assertThat(repo.toggleFavorite(item)).isTrue()
        assertThat(repo.isFavorite(item.workId())).isTrue()

        repo.saveHistoryRecord(
            ReadHistoryRecord(
                sourceId = "stub",
                toonId = "w1",
                toonTitle = "Test Work",
                toonThumbUrl = "https://example.com/t.jpg",
                toonHref = "https://example.com/w1",
                lastWrId = "ep1",
                lastEpisodeTitle = "1화",
                lastEpisodeHref = "https://example.com/ep1",
                lastReadOrder = 1,
                totalEpisodes = 10,
                lastReadAt = 1_700_000_000_000L,
            ),
        )
        repo.markEpisodeRead(item.workId(), "ep1", lastPage = 4)

        val stats = repo.getBackupStats()
        assertThat(stats.favoriteCount).isEqualTo(1)
        assertThat(stats.historyCount).isEqualTo(1)
        assertThat(stats.episodeCount).isEqualTo(1)

        val backupJson = repo.exportBackupJson()
        val restoredStore = InMemoryComicLocalStore()
        val restored = SharedBackupManager.restoreBackupJson(restoredStore, backupJson)
        assertThat(restored.success).isTrue()
        assertThat(restored.favoriteCount).isEqualTo(1)
        assertThat(restored.historyCount).isEqualTo(1)
        assertThat(restored.episodeCount).isEqualTo(1)

        // Toggle off removes favorite and writes tombstone
        assertThat(repo.toggleFavorite(item)).isFalse()
        assertThat(store.getTombstonesSince(0L)).hasSize(1)
        repo.close()
    }

    @Test
    fun syncAdapterRespectsStaleHistoryOrder() = runBlocking {
        val store = InMemoryComicLocalStore()
        val prefs = InMemoryKeyValuePrefs()
        val adapter = StoreSyncStorageAdapter(store, prefs)
        val workId = WorkId("stub", "w1")
        store.upsertHistory(
            ReadHistoryRecord(
                sourceId = workId.sourceId,
                toonId = workId.toonId,
                toonTitle = "Work",
                toonThumbUrl = "",
                toonHref = "",
                lastWrId = "10",
                lastEpisodeTitle = "10화",
                lastEpisodeHref = "",
                lastReadOrder = 10,
                totalEpisodes = 10,
                lastReadAt = 1000L,
            ),
        )
        val incoming = org.json.JSONObject().apply {
            put(
                "history",
                org.json.JSONArray().put(
                    org.json.JSONObject().apply {
                        put("sourceId", "stub")
                        put("toonId", "w1")
                        put("toonTitle", "Work")
                        put("lastWrId", "9")
                        put("lastEpisodeTitle", "9화")
                        put("lastReadOrder", 9)
                        put("totalEpisodes", 10)
                        put("lastReadAt", 9999L)
                    },
                ),
            )
        }
        val counts = adapter.applyRemoteChanges(incoming, 9999L)
        assertThat(counts.history).isEqualTo(0)
        assertThat(store.getHistory(workId)?.lastReadOrder).isEqualTo(10)
    }

    private class StubSource : ComicSource {
        override val id = "stub"
        override val displayName = "Stub"
        override val origin = "https://example.com"
        override val catalogs = listOf(SourceCatalog("LATEST", "Latest", paginated = true))
        override val defaultPolicy = RequestPolicy("test")
        override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp) =
            ListingPage(emptyList(), 1, 1)
        override suspend fun search(query: SearchQuery, http: SourceHttp) = emptyList<ToonItem>()
        override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp) =
            EpisodePage(emptyList(), 1, 1)
        override suspend fun resolveImages(episode: EpisodeItem, item: ToonItem, http: SourceHttp) =
            emptyList<String>()
    }
}

internal class InMemoryKeyValuePrefs : KeyValuePrefs {
    private val map = linkedMapOf<String, String>()
    override fun contains(key: String) = key in map
    override fun getString(key: String, defaultValue: String?) = map[key] ?: defaultValue
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}

internal class InMemoryComicLocalStore : ComicLocalStore {
    val favorites = linkedMapOf<String, FavoriteRecord>()
    val seen = linkedMapOf<String, SeenRecord>()
    val settings = linkedMapOf<String, ReaderSettingRecord>()
    val episodes = linkedMapOf<String, ReadEpisodeRecord>()
    val history = linkedMapOf<String, ReadHistoryRecord>()
    val downloads = linkedMapOf<String, DownloadedEpisodeRecord>()
    val catalog = linkedMapOf<String, ToonCatalogRecord>()
    val tombstones = linkedMapOf<String, TombstoneRecord>()

    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()

    override suspend fun countFavorites() = favorites.size
    override suspend fun getAllFavorites() = favorites.values.toList()
    override suspend fun getFavoritesByWorkIds(workIds: List<WorkId>): List<FavoriteRecord> {
        val keys = workIds.map { it.storageKey() }.toSet()
        return favorites.filterKeys { it in keys }.values.toList()
    }
    override suspend fun getFavoritesSince(since: Long) = favorites.values.filter { it.savedAt > since }
    override suspend fun getFavorite(workId: WorkId) = favorites[workId.storageKey()]
    override suspend fun hasFavorite(workId: WorkId) = workId.storageKey() in favorites
    override suspend fun upsertFavorite(item: FavoriteRecord) { favorites[item.workId().storageKey()] = item }
    override suspend fun upsertFavorites(items: List<FavoriteRecord>) { items.forEach { upsertFavorite(it) } }
    override suspend fun updateFavoriteUpdatedAt(workId: WorkId, updatedAt: String) {
        favorites[workId.storageKey()]?.let { favorites[workId.storageKey()] = it.copy(updatedAt = updatedAt) }
    }
    override suspend fun updateFavoriteThumbUrlIfBlank(workId: WorkId, thumbUrl: String) {
        favorites[workId.storageKey()]?.let {
            if (it.thumbUrl.isBlank()) favorites[workId.storageKey()] = it.copy(thumbUrl = thumbUrl)
        }
    }
    override suspend fun deleteFavorite(workId: WorkId) { favorites.remove(workId.storageKey()) }

    override suspend fun countSeen() = seen.size
    override suspend fun getSeenByWorkIds(workIds: List<WorkId>): Map<String, SeenRecord> {
        val keys = workIds.map { it.storageKey() }.toSet()
        return seen.filterKeys { it in keys }
    }
    override suspend fun upsertSeenAll(items: List<SeenRecord>) {
        items.forEach { seen[it.workId().storageKey()] = it }
    }

    override suspend fun countReaderSettings() = settings.size
    override suspend fun getReaderSetting(workId: WorkId) = settings[workId.storageKey()]
    override suspend fun getAllReaderSettings() = settings.values.toList()
    override suspend fun getReaderSettingsSince(since: Long) = settings.values.filter { it.updatedAt > since }
    override suspend fun upsertReaderSetting(setting: ReaderSettingRecord) {
        settings[setting.workId().storageKey()] = setting
    }
    override suspend fun upsertReaderSettings(settings: List<ReaderSettingRecord>) {
        settings.forEach { upsertReaderSetting(it) }
    }

    private fun epKey(workId: WorkId, wrId: String) = "${workId.storageKey()}|$wrId"
    override suspend fun countReadEpisodes() = episodes.size
    override suspend fun getReadEpisodes(workId: WorkId) =
        episodes.values.filter { it.workId() == workId }
    override suspend fun getAllReadEpisodes() = episodes.values.toList()
    override suspend fun getReadEpisodesSince(since: Long) = episodes.values.filter { it.readAt > since }
    override suspend fun getReadEpisode(workId: WorkId, wrId: String) = episodes[epKey(workId, wrId)]
    override suspend fun countReadEpisodesByToon(workId: WorkId) = getReadEpisodes(workId).size
    override suspend fun countReadEpisodesByWorkIds(workIds: List<WorkId>): Map<String, Int> =
        workIds.associate { it.storageKey() to getReadEpisodes(it).size }
    override suspend fun upsertReadEpisode(item: ReadEpisodeRecord) {
        episodes[epKey(item.workId(), item.wrId)] = item
    }
    override suspend fun upsertReadEpisodes(items: List<ReadEpisodeRecord>) {
        items.forEach { upsertReadEpisode(it) }
    }
    override suspend fun updateReadEpisodeLastPage(workId: WorkId, wrId: String, page: Int, readAt: Long) {
        val k = epKey(workId, wrId)
        episodes[k]?.let { episodes[k] = it.copy(lastPage = page, readAt = readAt) }
    }
    override suspend fun deleteReadEpisodesByToon(workId: WorkId) {
        episodes.entries.removeIf { it.value.workId() == workId }
    }

    override suspend fun countHistory() = history.size
    override suspend fun getAllHistory() = history.values.toList()
    override suspend fun getHistoryBySource(sourceId: String) =
        history.values.filter { it.sourceId == sourceId }
    override suspend fun getHistoryByWorkIds(workIds: List<WorkId>): List<ReadHistoryRecord> {
        val keys = workIds.map { it.storageKey() }.toSet()
        return history.filterKeys { it in keys }.values.toList()
    }
    override suspend fun getHistorySince(since: Long) = history.values.filter { it.lastReadAt > since }
    override suspend fun getHistory(workId: WorkId) = history[workId.storageKey()]
    override suspend fun upsertHistory(item: ReadHistoryRecord) {
        history[item.workId().storageKey()] = item
    }
    override suspend fun upsertHistoryAll(items: List<ReadHistoryRecord>) {
        items.forEach { upsertHistory(it) }
    }
    override suspend fun deleteHistory(workId: WorkId) { history.remove(workId.storageKey()) }
    override suspend fun clearHistoryBySource(sourceId: String) {
        history.entries.removeIf { it.value.sourceId == sourceId }
    }

    override suspend fun getDownloadedEpisodesBySource(sourceId: String) =
        downloads.values.filter { it.sourceId == sourceId }
    override suspend fun getDownloadedEpisodesByToon(workId: WorkId) =
        downloads.values.filter { it.workId() == workId }
    override suspend fun getDownloadedEpisode(workId: WorkId, wrId: String) =
        downloads[epKey(workId, wrId)]
    override suspend fun upsertDownloadedEpisode(item: DownloadedEpisodeRecord) {
        downloads[epKey(item.workId(), item.wrId)] = item
    }
    override suspend fun deleteDownloadedEpisode(workId: WorkId, wrId: String) {
        downloads.remove(epKey(workId, wrId))
    }
    override suspend fun deleteDownloadedEpisodesByToon(workId: WorkId) {
        downloads.entries.removeIf { it.value.workId() == workId }
    }

    override suspend fun getCatalogEntry(workId: WorkId) = catalog[workId.storageKey()]
    override suspend fun getCatalogTotalsByWorkIds(workIds: List<WorkId>): Map<String, EpisodeListingTotal> {
        val keys = workIds.map { it.storageKey() }.toSet()
        return catalog.filterKeys { it in keys }
            .mapNotNull { (k, v) -> v.total()?.let { k to it } }
            .toMap()
    }
    override suspend fun recordCatalogTotal(workId: WorkId, total: EpisodeListingTotal?, updatedAt: Long): Boolean {
        if (total == null || !total.isValid) return false
        val k = workId.storageKey()
        if (catalog[k]?.total() == total) return false
        catalog[k] = ToonCatalogRecord.of(workId, total, updatedAt)
        return true
    }
    override suspend fun upsertCatalogEntries(entries: List<ToonCatalogRecord>) {
        entries.forEach { e ->
            e.total()?.let { recordCatalogTotal(e.workId(), it, e.updatedAt) }
        }
    }

    override suspend fun getTombstonesSince(since: Long) = tombstones.values.filter { it.deletedAt > since }
    override suspend fun recordTombstone(entityType: String, entityId: String, deletedAt: Long) {
        tombstones["$entityType|$entityId"] = TombstoneRecord(entityType, entityId, deletedAt)
    }
    override suspend fun deleteTombstone(entityType: String, entityId: String) {
        tombstones.remove("$entityType|$entityId")
    }
    override suspend fun deleteTombstonesOlderThan(cutoff: Long) {
        tombstones.entries.removeIf { it.value.deletedAt < cutoff }
    }
}
