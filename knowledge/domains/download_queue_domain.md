# Download Queue Domain Contract

## Responsibilities & Boundaries

Coordinates background downloading of entire comic series or specific chapters for offline access, image integrity verification, and storage management.

## Core Invariants

- **Storage Layout (`DownloadLayout.kt`)**:
  - Saved files follow predictable folder structures: `<DownloadDir>/<SourceId>/<ComicTitle>/<EpisodeTitle>/<PageIndex>.jpg`.
  - Episode download directories contain a `.complete` or metadata marker file only after all image assets have finished downloading successfully.
- **Batch Image Downloader (`ImageBatchDownload.kt`)**:
  - Parallel page downloads with configurable concurrency limit (default: 3-5 concurrent image connections).
  - Exponential backoff retry on HTTP 429 (Rate Limit) and 5xx errors.
  - Image referer and custom headers injected per source requirement.
- **Platform Implementations**:
  - Android: `DownloadManager` backed by foreground service / WorkManager to ensure completion across lifecycle pauses.
  - Desktop: `DesktopDownloadManager` with non-blocking coroutine queues and progress UI callbacks.
