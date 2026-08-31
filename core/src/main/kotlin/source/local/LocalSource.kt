package com.comics8.core.source.local

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem
import com.comics8.core.source.ComicSource
import com.comics8.core.source.LocalImageUri
import com.comics8.core.source.NotificationMode
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceKind
import com.comics8.core.source.WorkId
import java.io.File

class LocalSource(
    private val roots: () -> List<File>,
    private val scan: LibraryScanner = LibraryScanner(),
    private val index: LibraryScanIndex? = null,
) : ComicSource {
    override val id: String = WorkId.LOCAL_SOURCE
    override val displayName: String = "저장소"
    override val origin: String = "local://"
    override val kind: SourceKind = SourceKind.LOCAL
    override val catalogs: List<SourceCatalog> = listOf(
        SourceCatalog("LIBRARY", "보관함", paginated = true),
        SourceCatalog("LATEST", "최신순", paginated = true),
    )
    override val defaultPolicy: RequestPolicy = RequestPolicy(userAgent = "Comics8/Local")
    override val searchPlaceholder: String = "파일명 검색"
    override val emptyListingOk: Boolean = true
    override val notificationMode: NotificationMode = NotificationMode.NONE
    override val syncParticipates: Boolean = false
    override val writesDownloads: Boolean = false
    override val requiresHttp: Boolean = false
    override val emptyEpisodesOk: Boolean = true
    override val episodePageSize: Int = PAGE_SIZE
    override val defaultProgressDisplayMode: com.comics8.core.model.ProgressDisplayMode =
        com.comics8.core.model.ProgressDisplayMode.READ_COUNT

    override fun useProxy(url: String): Boolean = false

    override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp): ListingPage {
        val allWorks = scanWorks()
        val works = when (catalogId.uppercase()) {
            "LATEST" -> allWorks.sortedByDescending { work ->
                work.episodes.maxOfOrNull { it.path.lastModified() } ?: work.path.lastModified()
            }
            else -> allWorks
        }
        val current = page.coerceIn(1, pageCount(works.size))
        return ListingPage(
            items = works.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE).map(::toItem),
            currentPage = current,
            lastPage = pageCount(works.size),
        )
    }


    override suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem> {
        val needle = query.text.trim()
        if (needle.isEmpty()) return emptyList()
        return scanWorks()
            .filter { it.title.contains(needle, ignoreCase = true) }
            .map { toItem(it) }
    }

    override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp): EpisodePage {
        val work = workFor(item.id) ?: return EpisodePage(emptyList(), 1, 1)
        val current = page.coerceIn(1, pageCount(work.episodes.size))
        val episodes = work.episodes.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE).map { episode ->
            val mtime = episode.path.lastModified().takeIf { it > 0L }
            EpisodeItem(
                wrId = episode.wrId,
                title = episode.title,
                date = mtime?.let { com.comics8.core.model.UpdateDates.formatEpoch(it) },
                thumbUrl = episodeThumb(episode),
                href = LocalImageUri.fromFile(episode.path),
                mtime = mtime,
            )
        }
        return EpisodePage(items = episodes, currentPage = current, lastPage = pageCount(work.episodes.size))
    }

    override suspend fun resolveImages(episode: EpisodeItem, item: ToonItem, http: SourceHttp): List<String> {
        val path = File(episode.wrId)
        if (path.isFile && ZipImageNames.isZipName(path.name)) {
            return ZipArchive(path).use { zip ->
                zip.imageEntries().map { ZipImageUri.encode(path, it) }
            }
        }
        if (path.isDirectory) {
            return scan.listFolderImages(path).map { LocalImageUri.fromFile(it) }
        }
        return emptyList()
    }

    private fun scanWorks(): List<ScannedWork> {
        val currentRoots = roots()
        val signature = LibraryScanIndex.signature(currentRoots.map { root ->
            val rootPath = LocalWorkId.canonical(root)
            "root|$rootPath|${root.lastModified()}|${root.length()}"
        })
        index?.load(signature)?.let { cached -> return cached.map(::fromIndexed) }
        return scan.scan(currentRoots).also { works ->
            index?.save(signature, works.map(::toIndexed))
        }
    }

    private fun toIndexed(work: ScannedWork): IndexedLibraryWork = IndexedLibraryWork(
        id = work.toonId,
        title = work.title,
        path = LocalWorkId.canonical(work.path),
        kind = work.kind.name,
        episodes = work.episodes.map { episode ->
            IndexedLibraryEpisode(
                path = LocalWorkId.canonical(episode.path),
                title = episode.title,
                zip = episode.kind == LocalEpisodeKind.ZIP,
                revision = com.comics8.core.source.FileRevision(
                    episode.path.length(),
                    episode.path.lastModified(),
                ),
            )
        },
    )

    private fun fromIndexed(work: IndexedLibraryWork): ScannedWork = ScannedWork(
        kind = LocalWorkKind.valueOf(work.kind),
        toonId = work.id,
        title = work.title,
        path = File(work.path),
        episodes = work.episodes.map { episode ->
            ScannedEpisode(
                kind = if (episode.zip) LocalEpisodeKind.ZIP else LocalEpisodeKind.DIR,
                wrId = episode.path,
                title = episode.title,
                path = File(episode.path),
            )
        },
    )

    private fun workFor(toonId: String): ScannedWork? {
        scanWorks().firstOrNull { it.toonId == toonId }?.let { return it }
        val path = pathFromToonId(toonId) ?: return null
        val parent = path.parentFile ?: return null
        return scan.scan(parent).firstOrNull { it.toonId == toonId }
    }

    private fun toItem(work: ScannedWork): ToonItem =
        ToonItem(
            id = work.toonId,
            title = work.title,
            thumbUrl = coverUrl(work),
            href = LocalImageUri.fromFile(work.path),
            sourceId = id,
        )

    private fun coverUrl(work: ScannedWork): String {
        val episode = work.episodes.firstOrNull() ?: return ""
        return previewUrl(episode, GRID_THUMB_PX)
    }

    private fun episodeThumb(episode: ScannedEpisode): String = previewUrl(episode, EPISODE_THUMB_PX)

    private fun previewUrl(episode: ScannedEpisode, thumbnailPx: Int): String = LocalPreviewUri.encode(
        episode.path,
        if (episode.kind == LocalEpisodeKind.ZIP) LocalPreviewUri.Kind.ZIP else LocalPreviewUri.Kind.FOLDER,
        thumbnailPx,
    )

    private fun pathFromToonId(toonId: String): File? {
        val prefix = PREFIXES.firstOrNull { toonId.startsWith(it) } ?: return null
        val raw = toonId.substring(prefix.length)
        if (raw.isEmpty()) return null
        return File(raw)
    }

    companion object {
        private val PREFIXES = listOf("zip:", "dir:", "series:")
        private const val PAGE_SIZE = 100
        private const val GRID_THUMB_PX = 320
        private const val EPISODE_THUMB_PX = 192
    }

    private fun pageCount(size: Int): Int = maxOf(1, (size + PAGE_SIZE - 1) / PAGE_SIZE)
}
