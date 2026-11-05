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
import com.comics8.core.source.local.ZipImageNames
import java.util.Base64

class NetworkLibrarySource(
    val config: NetworkSourceConfig,
    private val backend: NetworkFileSystem = createNetworkFileSystem(config),
    private val index: NetworkWorkIndex? = null,
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
        val works = scan()
        val ordered = when (catalogId.uppercase()) {
            // 최신순은 작품 폴더(또는 압축 파일) 자체의 수정일을 쓴다. 루트 목록만으로 결정되므로
            // 회차를 추가한 작품은 캐시 상태와 무관하게 항상 맨 앞으로 올라온다.
            "LATEST" -> works.sortedWith(
                compareByDescending<NetworkListing> { it.modifiedAtEpochMs }
                    .then(compareBy(NaturalSort) { it.title }),
            )
            else -> works
        }
        val current = page.coerceIn(1, pageCount(ordered.size))
        return ListingPage(
            ordered.drop((current - 1) * PAGE_SIZE).take(PAGE_SIZE).map(::toItem),
            current,
            pageCount(ordered.size),
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
                val mtime = episode.revision.modifiedAtEpochMs.takeIf { it > 0L }
                EpisodeItem(
                    wrId = encodeEpisode(episode),
                    title = episode.title,
                    date = mtime?.let { com.comics8.core.model.UpdateDates.formatEpoch(it) },
                    thumbUrl = firstImageUrl(episode, EPISODE_THUMB_PX),
                    href = NetworkImageUri.encode(id, episode.path),
                    mtime = mtime,
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
        val parsed = parseWorkId(workId) ?: return null
        val name = parsed.path.substringAfterLast('/').ifBlank { config.name }
        return when (parsed.kind) {
            // 회차 구성이 작품 종류를 결정하므로 열 때도 목록과 같은 규칙(readFolder)으로 읽는다.
            NetworkWorkKind.SERIES, NetworkWorkKind.DIR -> readFolder(
                NetworkNode(path = parsed.path, name = name, directory = true, revision = FileRevision.UNKNOWN),
            )
            NetworkWorkKind.ZIP -> NetworkWork(
                kind = NetworkWorkKind.ZIP,
                title = stem(name),
                episodes = listOf(NetworkEpisode(parsed.path, stem(name), zip = true, revisionOf(parsed.path))),
            )
            NetworkWorkKind.SKIP -> null
        }
    }

    private fun parseWorkId(id: String): ParsedWorkId? {
        val kind = NetworkWorkKind.entries.firstOrNull { it.prefix.isNotEmpty() && id.startsWith("${it.prefix}:") }
            ?: return null
        return ParsedWorkId(kind, id.removePrefix("${kind.prefix}:"))
    }

    /**
     * 그리드에 노출할 작품 목록.
     *
     * 루트 목록은 매번 새로 읽는다(수정일이 항상 최신). 폴더를 하나씩 열어야 알 수 있는 "작품 종류와
     * 썸네일용 첫 회차"만 [NetworkWorkIndex]에 캐시해, 변하지 않은 목록에서는 폴더를 다시 뒤지지 않는다.
     */
    private fun scan(): List<NetworkListing> {
        val root = backend.list("").sortedWith(compareBy(NaturalSort) { it.name })
        val signature = NetworkWorkIndex.signature(root)
        val cached = index?.load(signature)
        val classified = LinkedHashMap<String, NetworkWorkShape>()
        val works = ArrayList<NetworkListing>()
        for (child in root) {
            if (ZipImageNames.isJunkName(child.name)) continue
            if (!child.directory) {
                if (ZipImageNames.isZipName(child.name)) {
                    works += listing(
                        child,
                        NetworkWorkKind.ZIP,
                        NetworkEpisode(child.path, stem(child.name), zip = true, child.revision),
                    )
                }
                continue
            }
            val shape = cached?.get(child.path)
                ?: (readFolder(child)?.toShape() ?: NetworkWorkShape(NetworkWorkKind.SKIP))
                    .also { classified[child.path] = it }
            if (shape.kind == NetworkWorkKind.SKIP) continue
            works += listing(child, shape.kind, shape.episode)
        }
        if (cached == null) index?.save(signature, classified)
        return works
    }

    private fun listing(node: NetworkNode, kind: NetworkWorkKind, thumb: NetworkEpisode?): NetworkListing =
        NetworkListing(
            id = "${kind.prefix}:${node.path}",
            title = if (kind == NetworkWorkKind.ZIP) stem(node.name) else node.name,
            path = node.path,
            // 작품 자체(폴더 또는 압축 파일)의 수정일. 루트 목록에서 바로 읽으므로 항상 최신이다.
            modifiedAtEpochMs = node.revision.modifiedAtEpochMs,
            thumb = thumb,
        )

    /**
     * 폴더 하나를 작품으로 해석한다. 목록과 열기가 같은 규칙을 공유하도록 회차 구성이 종류를 결정한다.
     * 폴더 목록은 1회만 읽는다.
     */
    private fun readFolder(dir: NetworkNode): NetworkWork? {
        val children = backend.list(dir.path).filterNot { ZipImageNames.isJunkName(it.name) }
        val zips = children.filter { !it.directory && ZipImageNames.isZipName(it.name) }
        val subDirs = children.filter { it.directory }
        if (zips.isNotEmpty() || subDirs.isNotEmpty()) {
            val episodes = zips.map { NetworkEpisode(it.path, stem(it.name), zip = true, it.revision) } +
                subDirs.map { NetworkEpisode(it.path, it.name, zip = false, it.revision) }
            return NetworkWork(
                kind = NetworkWorkKind.SERIES,
                title = dir.name,
                episodes = episodes.sortedWith(compareBy(NaturalSort) { it.title }),
            )
        }
        if (children.any { !it.directory && ZipImageNames.isImageEntry(it.name) }) {
            // 목록에서는 루트 목록의 수정일을 그대로 쓰고, 열 때는 노드에 수정일이 없으므로 직접 확인한다.
            val revision = dir.revision.takeIf { it != FileRevision.UNKNOWN } ?: revisionOf(dir.path)
            return NetworkWork(
                kind = NetworkWorkKind.DIR,
                title = dir.name,
                episodes = listOf(NetworkEpisode(dir.path, dir.name, zip = false, revision)),
            )
        }
        return null
    }

    private fun NetworkWork.toShape(): NetworkWorkShape = NetworkWorkShape(kind, episodes.firstOrNull())

    private fun revisionOf(path: String): FileRevision =
        runCatching { backend.stat(path) }.getOrNull()?.revision ?: FileRevision.UNKNOWN

    private fun toItem(work: NetworkListing): ToonItem = ToonItem(
        id = work.id,
        title = work.title,
        thumbUrl = work.thumb?.let { firstImageUrl(it, GRID_THUMB_PX) }.orEmpty(),
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

    /** 그리드 한 칸. 회차 목록은 담지 않는다. */
    private data class NetworkListing(
        val id: String,
        val title: String,
        val path: String,
        val modifiedAtEpochMs: Long,
        val thumb: NetworkEpisode?,
    )

    /** 열린 작품과 그 회차 목록. */
    private data class NetworkWork(
        val kind: NetworkWorkKind,
        val title: String,
        val episodes: List<NetworkEpisode>,
    )

    private data class ParsedWorkId(val kind: NetworkWorkKind, val path: String)

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
