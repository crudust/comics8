package com.comics8.core.source.network

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem
import com.comics8.core.source.ComicSource
import com.comics8.core.source.FileRevision
import com.comics8.core.source.NotificationMode
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceKind
import com.comics8.core.source.local.NaturalSort
import com.comics8.core.source.local.IndexedLibraryEpisode
import com.comics8.core.source.local.IndexedLibraryWork
import com.comics8.core.source.local.LibraryScanIndex
import com.comics8.core.source.local.ZipImageNames
import java.util.Base64

class NetworkLibrarySource(
    val config: NetworkSourceConfig,
    private val backend: NetworkFileSystem = createNetworkFileSystem(config),
    private val index: LibraryScanIndex? = null,
) : ComicSource {
    override val id: String = config.id
    override val displayName: String = config.name
    override val origin: String = when (config.protocol) {
        NetworkProtocol.SMB -> "smb://${config.host}/${config.share}/"
        NetworkProtocol.WEBDAV -> config.url
    }
    override val kind: SourceKind = SourceKind.LOCAL
    override val catalogs: List<SourceCatalog> = listOf(
        SourceCatalog("LIBRARY", "보관함", paginated = true),
        SourceCatalog("LATEST", "최신순", paginated = true),
    )
    override val defaultPolicy: RequestPolicy = RequestPolicy(userAgent = "Comics8/Network")
    override val searchPlaceholder: String = "파일명 검색"
    override val notificationMode: NotificationMode = NotificationMode.NONE
    override val syncParticipates: Boolean = false
    override val writesDownloads: Boolean = false
    override val requiresHttp: Boolean = false
    override val emptyListingOk: Boolean = true
    override val emptyEpisodesOk: Boolean = true
    override val episodePageSize: Int = PAGE_SIZE
    override val defaultProgressDisplayMode: com.comics8.core.model.ProgressDisplayMode =
        com.comics8.core.model.ProgressDisplayMode.READ_COUNT

    init {
        NetworkSourceRuntime.register(id, backend)
    }

    override fun useProxy(url: String): Boolean = false

    override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp): ListingPage {
        val allWorks = scan()
        val works = when (catalogId.uppercase()) {
            "LATEST" -> allWorks.sortedByDescending { work ->
                work.episodes.maxOfOrNull { it.revision.modifiedAtEpochMs } ?: 0L
            }
            else -> allWorks
        }
        val current = page.coerceIn(1, pageCount(works.size))
        return ListingPage(
            works.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE).map(::toItem),
            current,
            pageCount(works.size),
        )
    }


    override suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem> {
        val needle = query.text.trim()
        if (needle.isEmpty()) return emptyList()
        return scan().filter { it.title.contains(needle, ignoreCase = true) }.map(::toItem)
    }

    override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp): EpisodePage {
        val work = workFor(item.id) ?: return EpisodePage(emptyList(), 1, 1)
        val current = page.coerceIn(1, pageCount(work.episodes.size))
        return EpisodePage(
            work.episodes.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE).map { episode ->
                EpisodeItem(
                    wrId = encodeEpisode(episode),
                    title = episode.title,
                    date = null,
                    thumbUrl = firstImageUrl(episode, EPISODE_THUMB_PX),
                    href = NetworkImageUri.encode(id, episode.path),
                )
            },
            current,
            pageCount(work.episodes.size),
        )
    }

    override suspend fun resolveImages(
        episode: EpisodeItem,
        item: ToonItem,
        http: SourceHttp,
    ): List<String> {
        val ref = decodeEpisode(episode.wrId) ?: return emptyList()
        return if (ref.zip) {
            val archive = zipEntries(ref.path)
            archive.entries.map {
                NetworkImageUri.encode(
                    sourceId = id,
                    path = ref.path,
                    zipEntry = it,
                    revision = archive.revision,
                )
            }
        } else {
            backend.list(ref.path)
                .filter { !it.directory && ZipImageNames.isImageEntry(it.name) }
                .sortedWith(compareBy(NaturalSort) { it.name })
                .map {
                    NetworkImageUri.encode(
                        sourceId = id,
                        path = it.path,
                        revision = it.revision,
                    )
                }
        }
    }

    private fun workFor(workId: String): NetworkWork? {
        val parsed = parseWorkId(workId) ?: return scan().firstOrNull { it.id == workId }
        val work = when (parsed.kind) {
            WorkKind.SERIES -> {
                val node = NetworkNode(
                    path = parsed.path,
                    name = parsed.path.substringAfterLast('/').ifBlank { config.name },
                    directory = true,
                    revision = FileRevision.UNKNOWN,
                )
                classifyFolder(node)
            }
            WorkKind.DIR -> {
                val stat = runCatching { backend.stat(parsed.path) }.getOrNull()
                val name = parsed.path.substringAfterLast('/').ifBlank { config.name }
                NetworkWork(
                    id = workId,
                    title = name,
                    path = parsed.path,
                    episodes = listOf(NetworkEpisode(parsed.path, name, zip = false, stat?.revision ?: FileRevision.UNKNOWN)),
                )
            }
            WorkKind.ZIP -> {
                val stat = runCatching { backend.stat(parsed.path) }.getOrNull()
                val name = parsed.path.substringAfterLast('/')
                NetworkWork(
                    id = workId,
                    title = stem(name),
                    path = parsed.path,
                    episodes = listOf(NetworkEpisode(parsed.path, stem(name), zip = true, stat?.revision ?: FileRevision.UNKNOWN)),
                )
            }
        }
        return work
    }

    private enum class WorkKind { SERIES, DIR, ZIP }
    private data class ParsedWorkId(val kind: WorkKind, val path: String)

    private fun parseWorkId(id: String): ParsedWorkId? {
        return when {
            id.startsWith("series:") -> ParsedWorkId(WorkKind.SERIES, id.removePrefix("series:"))
            id.startsWith("dir:") -> ParsedWorkId(WorkKind.DIR, id.removePrefix("dir:"))
            id.startsWith("zip:") -> ParsedWorkId(WorkKind.ZIP, id.removePrefix("zip:"))
            else -> null
        }
    }

    private fun scan(): List<NetworkWork> {
        val stamp = runCatching { backend.stat("") }.getOrNull()
        val statSignature = stamp?.takeIf { it.revision.modifiedAtEpochMs > 0L }?.let { node ->
            LibraryScanIndex.signature(
                listOf("${config.protocol}|${config.path}|${node.revision}"),
            )
        }
        if (statSignature != null) {
            index?.load(statSignature)?.let { cached ->
                return cached.map(::fromIndexed)
            }
        }
        val root = backend.list("").sortedWith(compareBy(NaturalSort) { it.name })
        val signature = statSignature ?: LibraryScanIndex.signature(root.map { node ->
            "${node.path}|${node.name}|${node.directory}|${node.revision}"
        })
        if (statSignature == null) {
            index?.load(signature)?.let { cached ->
                return cached.map(::fromIndexed)
            }
        }
        val works = ArrayList<NetworkWork>()
        for (child in root) {
            if (ZipImageNames.isJunkName(child.name)) continue
            if (!child.directory && ZipImageNames.isZipName(child.name)) {
                works += zipWork(child)
            } else if (child.directory) {
                classifyFolder(child)?.let(works::add)
            }
        }
        index?.save(signature, works.map(::toIndexed))
        return works
    }

    private fun toIndexed(work: NetworkWork): IndexedLibraryWork = IndexedLibraryWork(
        id = work.id,
        title = work.title,
        path = work.path,
        kind = "NETWORK",
        episodes = work.episodes.map { episode ->
            IndexedLibraryEpisode(
                episode.path,
                episode.title,
                episode.zip,
                episode.revision,
            )
        },
    )

    private fun fromIndexed(work: IndexedLibraryWork): NetworkWork = NetworkWork(
        id = work.id,
        title = work.title,
        path = work.path,
        episodes = work.episodes.map { episode ->
            NetworkEpisode(
                episode.path,
                episode.title,
                episode.zip,
                episode.revision,
            )
        },
    )

    private fun classifyFolder(dir: NetworkNode): NetworkWork? {
        val children = backend.list(dir.path).filterNot { ZipImageNames.isJunkName(it.name) }
        val zips = children.filter { !it.directory && ZipImageNames.isZipName(it.name) }
        val subDirs = children.filter { it.directory }
        if (zips.isNotEmpty() || subDirs.isNotEmpty()) {
            val episodes = zips.map { NetworkEpisode(it.path, stem(it.name), zip = true, it.revision) } +
                subDirs.map { NetworkEpisode(it.path, it.name, zip = false, it.revision) }
            return NetworkWork(
                id = "series:${dir.path}",
                title = dir.name,
                path = dir.path,
                episodes = episodes.sortedWith(compareBy(NaturalSort) { it.title }),
            )
        }
        if (children.any { !it.directory && ZipImageNames.isImageEntry(it.name) }) {
            return NetworkWork(
                id = "dir:${dir.path}",
                title = dir.name,
                path = dir.path,
                episodes = listOf(NetworkEpisode(dir.path, dir.name, zip = false, dir.revision)),
            )
        }
        return null
    }

    private fun zipWork(file: NetworkNode): NetworkWork = NetworkWork(
        id = "zip:${file.path}",
        title = stem(file.name),
        path = file.path,
        episodes = listOf(NetworkEpisode(file.path, stem(file.name), zip = true, file.revision)),
    )

    private fun toItem(work: NetworkWork): ToonItem = ToonItem(
        id = work.id,
        title = work.title,
        thumbUrl = work.episodes.firstOrNull()?.let { firstImageUrl(it, GRID_THUMB_PX) }.orEmpty(),
        href = NetworkImageUri.encode(id, work.path),
        sourceId = id,
    )

    private fun firstImageUrl(episode: NetworkEpisode, thumbnailPx: Int): String = if (episode.zip) {
        NetworkImageUri.encode(
            sourceId = id,
            path = episode.path,
            preview = NetworkImageUri.PreviewKind.ZIP_FIRST,
            revision = episode.revision,
            thumbnailPx = thumbnailPx,
        )
    } else {
        NetworkImageUri.encode(
            sourceId = id,
            path = episode.path,
            preview = NetworkImageUri.PreviewKind.FOLDER_FIRST,
            revision = episode.revision,
            thumbnailPx = thumbnailPx,
        )
    }

    private fun zipEntries(path: String): NetworkArchiveIndex =
        NetworkSourceRuntime.zipImageEntries(id, path)

    private fun encodeEpisode(episode: NetworkEpisode): String {
        val payload = "${if (episode.zip) 'z' else 'd'}\n${episode.path}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
    }

    private fun decodeEpisode(value: String): NetworkEpisodeRef? = runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(value)).split('\n', limit = 2)
        require(decoded.size == 2) { "invalid network episode id" }
        NetworkEpisodeRef(decoded[1], decoded[0] == "z")
    }.getOrNull()

    private fun stem(name: String): String = name.substringBeforeLast('.', name)

    private fun pageCount(size: Int): Int = maxOf(1, (size + PAGE_SIZE - 1) / PAGE_SIZE)

    private data class NetworkWork(
        val id: String,
        val title: String,
        val path: String,
        val episodes: List<NetworkEpisode>,
    )

    private data class NetworkEpisode(
        val path: String,
        val title: String,
        val zip: Boolean,
        val revision: FileRevision,
    )

    private data class NetworkEpisodeRef(val path: String, val zip: Boolean)

    companion object {
        private const val PAGE_SIZE = 100
        private const val GRID_THUMB_PX = 320
        private const val EPISODE_THUMB_PX = 192
    }
}

fun createNetworkFileSystem(config: NetworkSourceConfig): NetworkFileSystem = when (config.protocol) {
    NetworkProtocol.SMB -> SmbFileSystem(config.validated())
    NetworkProtocol.WEBDAV -> WebDavFileSystem(config.validated())
}

fun NetworkSourceStore.loadSources(): List<NetworkLibrarySource> = all().mapNotNull { config ->
    runCatching { createSource(config) }.getOrNull()
}
