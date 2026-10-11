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
    - `catalog`: Global crowdsourced monotonic episode count metadata.
- **Source Resolution Protocol (`/catalog/source/resolve`)**:
  - Maps arbitrary client JS source IDs to canonical server source clusters via a 4-tier pipeline: Host match (O(1)) -> Cumulative toon ID intersection (threshold >= 15%) with domain self-healing -> Title semantic fallback -> Isolated new source creation.
  - Clients bind canonical source IDs locally via `SourceBindingManager` upon initial discovery to ensure zero ongoing overhead during batch catalog queries and reports.
- **Conflict Resolution (Last-Write-Wins - LWW)**:
  - Every sync record carries an `updatedAt` millisecond timestamp.
  - Conflicts between local storage and server records are resolved by choosing the record with the higher `updatedAt`.
  - Deleted entities carry a `deletedAt` tombstone timestamp to prevent resurrected items.
- **Debounce & Exclusive Locks (`BaseSyncManager.kt`)**:
  - UI reading progress mutations are debounced (typically 1.5s - 3s) before triggering server push to minimize redundant network traffic.
  - `exclusiveSync()` uses an `AtomicBoolean` to prevent concurrent in-flight sync passes.
- **Server Account Materialization (`server/app/sync.py`)**:
  - Resolving a master sync key is side-effect free. Reads, empty/invalid sync payloads, pairing requests, and subscription status checks must not create a `users` row.
  - A user is materialized transactionally only when an accepted sync entity or an authoritatively verified subscription is persisted.
  - Resource limits follow the owned resource, not a shared signup quota: request bytes and item count are bounded before persistence, and total sync rows are bounded per account while updates and deletions remain possible at the limit.
  - A non-null stored `sync_key` is immutable through authentication. Alternate raw keys that normalize to the same `user_id` must be rejected rather than rebound.
- **Credential Transport**:
  - Long-lived sync keys are accepted only through `Authorization: Bearer` or `X-Sync-Key` headers. Query-string credentials are not part of the wire protocol.
  - Pairing codes are issued only for materialized accounts and are atomically consumed once from PostgreSQL. Confirmation attempts are bounded per client in the current single-process server topology.
- **Storage Abstraction (`SyncStorageAdapter.kt`)**:
  - `BaseSyncManager` interacts exclusively through `SyncStorageAdapter`.
  - Android provides `AndroidSyncManager` (backed by Room DB).
  - Desktop provides `DesktopSyncManager` (backed by SQLite JDBC).
