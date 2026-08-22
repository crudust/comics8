package com.comics8.core.source.network

import com.comics8.core.model.EpisodeItem
import com.comics8.core.model.EpisodePage
import com.comics8.core.model.ListingPage
import com.comics8.core.model.ToonItem
import com.comics8.core.source.ComicSource
import com.comics8.core.source.NotificationMode
import com.comics8.core.source.RequestPolicy
import com.comics8.core.source.SearchQuery
import com.comics8.core.source.SourceCatalog
import com.comics8.core.source.SourceHttp
import com.comics8.core.source.SourceKind
import com.comics8.core.source.local.NaturalSort
import com.comics8.core.source.local.ZipArchive
import com.comics8.core.source.local.ZipImageNames
import org.apache.commons.compress.archivers.zip.ZipFile
import java.util.Base64

class NetworkLibrarySource(
    val config: NetworkSourceConfig,
    private val backend: NetworkFileSystem = createNetworkFileSystem(config),
) : ComicSource {
    override val id: String = config.id
    override val displayName: String = config.name
    override val origin: String = when (config.protocol) {
        NetworkProtocol.SMB -> "smb://${config.host}/${config.share}/"
        NetworkProtocol.WEBDAV -> config.url
    }
    override val kind: SourceKind = SourceKind.LOCAL
    override val catalogs: List<SourceCatalog> = listOf(SourceCatalog("LIBRARY", "보관함", false))
    override val defaultPolicy: RequestPolicy = RequestPolicy(userAgent = "Comics8/Network")
    override val searchPlaceholder: String = "파일명 검색"
    override val notificationMode: NotificationMode = NotificationMode.NONE
    override val syncParticipates: Boolean = false
    override val writesDownloads: Boolean = false
    override val requiresHttp: Boolean = false
    override val emptyListingOk: Boolean = true
    override val emptyEpisodesOk: Boolean = true

    init {
        NetworkSourceRuntime.register(id, backend)
    }

    override fun useProxy(url: String): Boolean = false

    override suspend fun loadListing(catalogId: String, page: Int, http: SourceHttp): ListingPage =
        ListingPage(scan().map(::toItem), 1, 1)

    override suspend fun search(query: SearchQuery, http: SourceHttp): List<ToonItem> {
        val needle = query.text.trim()
        if (needle.isEmpty()) return emptyList()
        return scan().filter { it.title.contains(needle, ignoreCase = true) }.map(::toItem)
    }

    override suspend fun loadEpisodes(item: ToonItem, page: Int, http: SourceHttp): EpisodePage {
        val work = scan().firstOrNull { it.id == item.id } ?: return EpisodePage(emptyList(), 1, 1)
        return EpisodePage(
            work.episodes.map { episode ->
                EpisodeItem(
                    wrId = encodeEpisode(episode),
                    title = episode.title,
                    date = null,
                    thumbUrl = firstImageUrl(episode),
                    href = NetworkImageUri.encode(id, episode.path),
                )
            },
            1,
            1,
        )
    }

    override suspend fun resolveImages(
        episode: EpisodeItem,
        item: ToonItem,
        http: SourceHttp,
    ): List<String> {
        val ref = decodeEpisode(episode.wrId) ?: return emptyList()
        return if (ref.zip) {
            zipEntries(ref.path, ref.size).map { NetworkImageUri.encode(id, ref.path, it, ref.size) }
        } else {
            backend.list(ref.path)
                .filter { !it.directory && ZipImageNames.isImageEntry(it.name) }
                .sortedWith(compareBy(NaturalSort) { it.name })
                .map { NetworkImageUri.encode(id, it.path) }
        }
    }

    private fun scan(): List<NetworkWork> {
        val works = ArrayList<NetworkWork>()
        for (child in backend.list("").sortedWith(compareBy(NaturalSort) { it.name })) {
            if (ZipImageNames.isJunkName(child.name)) continue
            if (!child.directory && ZipImageNames.isZipName(child.name)) {
                works += zipWork(child)
            } else if (child.directory) {
                classifyFolder(child)?.let(works::add)
            }
        }
        return works
    }

    private fun classifyFolder(dir: NetworkNode): NetworkWork? {
        val children = backend.list(dir.path).filterNot { ZipImageNames.isJunkName(it.name) }
        val zips = children.filter { !it.directory && ZipImageNames.isZipName(it.name) }
        val imageFolders = children.filter { it.directory && hasDirectImages(it.path) }
        if (zips.isNotEmpty() || imageFolders.isNotEmpty()) {
            val episodes = zips.map { NetworkEpisode(it.path, stem(it.name), zip = true, it.size) } +
                imageFolders.map { NetworkEpisode(it.path, it.name, zip = false, 0L) }
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
                episodes = listOf(NetworkEpisode(dir.path, dir.name, zip = false, 0L)),
            )
        }
        return null
    }

    private fun hasDirectImages(path: String): Boolean = backend.list(path).any {
        !it.directory && ZipImageNames.isImageEntry(it.name)
    }

    private fun zipWork(file: NetworkNode): NetworkWork = NetworkWork(
        id = "zip:${file.path}",
        title = stem(file.name),
        path = file.path,
        episodes = listOf(NetworkEpisode(file.path, stem(file.name), zip = true, file.size)),
    )

    private fun toItem(work: NetworkWork): ToonItem = ToonItem(
        id = work.id,
        title = work.title,
        thumbUrl = work.episodes.firstNotNullOfOrNull(::firstImageUrl).orEmpty(),
        href = NetworkImageUri.encode(id, work.path),
        sourceId = id,
    )

    private fun firstImageUrl(episode: NetworkEpisode): String? = if (episode.zip) {
        zipEntries(episode.path, episode.size).firstOrNull()
            ?.let { NetworkImageUri.encode(id, episode.path, it, episode.size) }
    } else {
        backend.list(episode.path)
            .filter { !it.directory && ZipImageNames.isImageEntry(it.name) }
            .minWithOrNull(compareBy(NaturalSort) { it.name })
            ?.let { NetworkImageUri.encode(id, it.path) }
    }

    private fun zipEntries(path: String, knownSize: Long = -1L): List<String> {
        backend.openChannel(path, knownSize).use { channel ->
            ZipFile.builder().setSeekableByteChannel(channel).get().use { zip ->
                val names = ArrayList<String>()
                val entries = zip.entries
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val normalized = runCatching { ZipArchive.normalizeZipEntry(entry.name) }.getOrNull() ?: continue
                    if (ZipImageNames.isImageEntry(normalized)) names += normalized
                }
                names.sortWith(NaturalSort)
                return names
            }
        }
    }

    private fun encodeEpisode(episode: NetworkEpisode): String {
        val payload = "${if (episode.zip) 'z' else 'd'}\n${episode.size}\n${episode.path}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
    }

    private fun decodeEpisode(value: String): NetworkEpisodeRef? = runCatching {
        val decoded = String(Base64.getUrlDecoder().decode(value)).split('\n', limit = 3)
        NetworkEpisodeRef(decoded[2], decoded[0] == "z", decoded[1].toLong())
    }.getOrNull()

    private fun stem(name: String): String = name.substringBeforeLast('.', name)

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
        val size: Long,
    )

    private data class NetworkEpisodeRef(val path: String, val zip: Boolean, val size: Long)
}

fun createNetworkFileSystem(config: NetworkSourceConfig): NetworkFileSystem = when (config.protocol) {
    NetworkProtocol.SMB -> SmbFileSystem(config.validated())
    NetworkProtocol.WEBDAV -> WebDavFileSystem(config.validated())
}

fun NetworkSourceStore.loadSources(): List<NetworkLibrarySource> = all().mapNotNull { config ->
    runCatching { NetworkLibrarySource(config) }.getOrNull()
}
