# Core Module Contract

## Core Responsibilities & Boundaries

- **Target JVM**: Kotlin JVM 17 (`compileJava.options.release = 17`).
- **Shared Domain Model**:
  - `ToonItem`: Universal metadata for a webtoon / comic series (id, title, author, thumbnail, origin source, tags).
  - `EpisodeItem`: Single chapter/episode metadata (id, parent toonId, title, orderIndex, date, pageCount).
  - `EpisodePage`: Paginated or complete list of episodes.
  - `ListingPage`: Paginated browse results.
- **Source Interface (`ComicSource`)**:
  - All comic sources (JS plugins, Local CBZ/ZIP, SMB, WebDAV) must implement `ComicSource`.
  - Suspend functions for asynchronous retrieval: `loadListing()`, `search()`, `suggest()`, `loadEpisodes()`, `resolveImages()`.
  - Non-blocking execution requirement: Long-running network or I/O calls must run under `Dispatchers.IO` / `runInterruptible`.
- **Network Interceptors & DNS**:
  - `FallbackDns`: Custom DNS resolver with Cloudflare/Google DoH fallback for domain resolution resilience.
  - `ComicImageInterceptor` & `ImageReferer`: Dynamic Referer / User-Agent header injection based on source domain origin. Guards against cancellation (`isCanceled()`), restricts fallbacks to HTTP 404/5xx or unrecoverable host errors, and prevents cascading timeouts on legitimate hosts.
  - `Image HTTP Client` (`ToonClient.newImageHttpClient`): Unsets OkHttp disk cache (`cache(null)`) so upper layers (Coil / desktop disk cache) retain single-source ownership, applies 15s connect/read timeouts for mobile DNS/TLS negotiation, and limits per-host concurrency to 4 via its dispatcher.
  - `ImageBatchDownload`: Parallel image chunk downloader with rate limiting and retry mechanism.
- **Dependency Invariant**:
  - `core` MUST NOT depend on Android framework (`android.*`) or Desktop Compose UI (`androidx.compose.*`).
  - Keep `core` completely agnostic to UI layers to allow pure Kotlin multiplatform/JVM compilation and headless unit testing.
- **Cross-Platform Data Boundaries**:
  - `BackupWireCodec` owns the local backup JSON v2 wire format and legacy-compatible decoding; Android Room and Desktop SQLite layers only map their persistence records to and from wire DTOs.
  - `RepositoryTransforms` owns pure source ordering, episode enrichment/counting, listing flags, and persisted reader-setting parsing. Platform repositories retain database, filesystem, network, and coroutine ownership.
- **Image Memory Contract** (`core/image/ImageMemoryPolicy.kt`):
  - Every decoded UI image is classified as `GRID`, `EPISODE`, or `READER`; caches are bounded by estimated decoded bytes, never entry count.
  - `ImageMemoryPolicy` partitions each platform's chosen total budget into GRID 32%, EPISODE 5%, and READER 63%. Android Coil and Desktop Skia own their platform cache implementations.
  - GRID survives reader navigation, EPISODE uses its own small LRU, and READER is cleared on episode change and reader exit.
  - Clearing READER also cancels or invalidates in-flight reader loads. Encoded disk caches remain intact so reopening does not force a network download.
