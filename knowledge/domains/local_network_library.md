# Local & Network Library Domain Contract

## Responsibilities & Boundaries

Handles scanning, indexing, cover extraction, and streaming of locally stored comics (CBZ, ZIP, image folders) and network shares (SMB v2/v3, WebDAV).

## Core Invariants

- **Zero-Extraction Archive Streaming**:
  - ZIP and CBZ archives are read on-demand without fully extracting them to disk.
  - Image files inside archives are accessed via random-access streams and decoded directly into bitmap memory caches.
- **Natural Sorting (`NaturalSort.kt`)**:
  - Episode titles and page file names (e.g., `page_1.jpg`, `page_2.jpg`, `page_10.jpg`) MUST be sorted using natural alpha-numeric comparison so that `10` comes after `9` (not after `1`).
- **Cover Thumbnail Pipeline (`CoverThumbCache.kt`, `PreviewImageResolver.kt`)**:
  - First valid image found in a folder/archive is cached as the comic thumbnail.
  - Thumbnails are hashed by path + `FileRevision` so growth or replacement of the underlying file invalidates both successful and failed thumbnail entries.
- **Network Storage Protocols**:
  - SMB: Backed by `smbj` / JCIFS-NG with credential caching and connection reuse.
  - WebDAV: Backed by OkHttp with Digest/Basic auth and directory XML parsing.
  - Scan/index metadata is only a discovery hint. Random-access reads derive the authoritative `FileRevision` from the opened file, and ZIP sessions are keyed by source + path + revision.
  - WebDAV range reads use ETag or Last-Modified preconditions to prevent bytes from different file revisions being combined.
  - Errors in network availability gracefully surface as offline indicators without clearing the indexed library catalog.
