/**
 * Sync layer entry point.
 *
 * `sync-client` talks to `server/app/sync.py`; `tasks` is the debounced, single-flight wrapper that
 * turns reader progress events into `/sync` pushes (mirroring
 * `core/src/main/kotlin/sync/BaseSyncManager.kt`).
 */

export {
  DEFAULT_SERVER_URL,
  EMPTY_STATUS,
  MAX_SYNC_ITEMS_PER_REQUEST,
  MAX_CATALOG_BATCH_ITEMS,
  PAIR_CODE_TTL_SECONDS,
  SYNC_STORAGE_KEY,
  SyncClient,
  SyncError,
  emptyUpdates,
  generateSyncKey,
  normalizeApiRoot,
  type DeltaResult,
  type PairConfirmResult,
  type PairRequestResult,
  type PullResult,
  type PushResult,
  type SyncCatalogRow,
  type SyncClientOptions,
  type SyncFavorite,
  type SyncHistory,
  type SyncReaderSetting,
  type SyncReadEpisode,
  type SyncRemoteChanges,
  type SyncStatus,
  type SyncTombstone,
  type SyncUpdates,
} from "./sync-client";

export {
  SyncTaskRunner,
  type SyncTaskClient,
  type SyncRunResult,
  type SyncTaskRunnerOptions,
} from "./tasks";
