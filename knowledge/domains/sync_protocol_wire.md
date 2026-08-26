# Sync Protocol & Wire Domain Contract

## Responsibilities & Boundaries

The Sync domain coordinates bidirectional state synchronization across multi-platform clients (Android, Desktop) and the cloud backend (`server/app/sync.py`).

## Core Invariants & Lifecycle

- **Sync Topology**:
  - Peer devices pair via a persistent `sync_key` (UUID) or a temporary 6-digit numeric pairing code.
  - Server URL defaults to `SyncConstants.DEFAULT_SERVER_URL` and can be customized by the user.
- **Sync Wire Protocol (`SyncWire.kt`)**:
  - Standard JSON payloads transporting entity buckets:
    - `favorites`: Added / deleted favorited comics.
    - `history`: Read episodes, timestamps, last read page index, and completion status.
    - `bookmarks`: Specific saved page bookmarks within chapters.
    - `settings`: Cross-device UI and reading preference flags.
- **Conflict Resolution (Last-Write-Wins - LWW)**:
  - Every sync record carries an `updatedAt` millisecond timestamp.
  - Conflicts between local storage and server records are resolved by choosing the record with the higher `updatedAt`.
  - Deleted entities carry a `deletedAt` tombstone timestamp to prevent resurrected items.
- **Debounce & Exclusive Locks (`BaseSyncManager.kt`)**:
  - UI reading progress mutations are debounced (typically 1.5s - 3s) before triggering server push to minimize redundant network traffic.
  - `exclusiveSync()` uses an `AtomicBoolean` to prevent concurrent in-flight sync passes.
- **Storage Abstraction (`SyncStorageAdapter.kt`)**:
  - `BaseSyncManager` interacts exclusively through `SyncStorageAdapter`.
  - Android provides `AndroidSyncManager` (backed by Room DB).
  - Desktop provides `DesktopSyncManager` (backed by SQLite JDBC).
