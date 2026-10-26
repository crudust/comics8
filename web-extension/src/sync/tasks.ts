/**
 * Debounced, single-flight sync scheduling.
 *
 * Reader progress fires constantly; `BaseSyncManager` in `core` coalesces it with a 2.5s debounce and
 * an `AtomicBoolean` mutex. This is the same policy in the browser, where the extra wrinkle is that
 * the service worker may be suspended at any moment — so state lives in `chrome.storage.local` and
 * every scheduled run re-reads it.
 */

import type { FavoriteRecord } from "../data/favorites-store.svelte";
import type { HistoryRecord, ReadEpisodeRecord } from "../data/history-store.svelte";
import {
  emptyUpdates,
  type PushResult,
  type SyncFavorite,
  type SyncHistory,
  type SyncReadEpisode,
  type SyncStatus,
  type SyncUpdates,
} from "./sync-client";

export function buildSyncUpdates(local: {
  favorites: FavoriteRecord[];
  history: HistoryRecord[];
  readEpisodes: ReadEpisodeRecord[];
}): SyncUpdates {
  return {
    favorites: local.favorites.map((f) => ({
      id: f.id,
      sourceId: f.sourceId,
      title: f.title,
      thumbUrl: f.thumbUrl,
      href: f.href,
      genre: f.genre,
      updatedAt: f.updatedAt ?? null,
      savedAt: f.savedAt,
    })),
    history: local.history.map((h) => ({
      toonId: h.toonId,
      sourceId: h.sourceId,
      toonTitle: h.title,
      toonThumbUrl: h.thumbUrl,
      lastWrId: h.lastWrId,
      lastEpisodeTitle: h.lastEpisodeTitle,
      lastReadOrder: h.lastReadOrder ?? 0,
      totalEpisodes: h.totalEpisodes ?? 0,
      lastReadAt: h.updatedAt,
    })),
    readEpisodes: local.readEpisodes.map((r) => ({
      toonId: r.toonId,
      wrId: r.wrId,
      sourceId: r.sourceId,
      readAt: r.readAt,
    })),
    readerSettings: [],
  };
}

export function mergeRemoteHistory(
  local: HistoryRecord[],
  remote: SyncHistory[],
): SyncHistory[] {
  const remoteMap = new Map(
    remote.map((r) => [`${r.sourceId ?? "local"}:${r.toonId}`, r]),
  );
  return local.map((h) => {
    const key = `${h.sourceId ?? "local"}:${h.toonId}`;
    const r = remoteMap.get(key);
    return {
      toonId: h.toonId,
      sourceId: h.sourceId,
      toonTitle: h.title || r?.toonTitle || "",
      toonThumbUrl: h.thumbUrl || r?.toonThumbUrl || "",
      lastWrId: h.lastWrId || r?.lastWrId || "",
      lastEpisodeTitle: h.lastEpisodeTitle || r?.lastEpisodeTitle || "",
      lastReadOrder: Math.max(h.lastReadOrder ?? 0, r?.lastReadOrder ?? 0),
      totalEpisodes: h.totalEpisodes || r?.totalEpisodes || 0,
      lastReadAt: Math.max(h.updatedAt ?? 0, r?.lastReadAt ?? 0),
      hasNew: r?.hasNew ?? false,
    };
  });
}

/** The slice of `SyncClient` the runner needs; lets hosts inject a different transport. */
export type SyncTaskClient = {
  getStatus(): SyncStatus;
  push(updates: SyncUpdates): Promise<PushResult>;
};

export type SyncTaskRunnerOptions = {
  /** Quiet period before a queued sync runs. Default 2500ms (matches `BaseSyncManager`). */
  debounceMs?: number;
  /** Supplies the payload; called at run time so it always reflects the latest local state. */
  collectUpdates: () => Promise<SyncUpdates> | SyncUpdates;
  /** Called after every attempt, successful or not. */
  onResult?: (result: SyncRunResult) => void;
};

export type SyncRunResult =
  | { ok: true; savedAt: number; favorites: number; history: number; readEpisodes: number }
  | { ok: false; error: string; skipped?: boolean };

export class SyncTaskRunner {
  private readonly client: SyncTaskClient;
  private readonly collectUpdates: SyncTaskRunnerOptions["collectUpdates"];
  private readonly debounceMs: number;
  private readonly onResult: SyncTaskRunnerOptions["onResult"];

  private timer: ReturnType<typeof setTimeout> | null = null;
  private running = false;
  private queued = false;
  private disposed = false;

  constructor(client: SyncTaskClient, options: SyncTaskRunnerOptions) {
    this.client = client;
    this.collectUpdates = options.collectUpdates;
    this.debounceMs = options.debounceMs ?? 2_500;
    this.onResult = options.onResult;
  }

  /**
   * Schedules a sync `debounceMs` from now, resetting any pending timer.
   *
   * No-ops when auto-sync is disabled, so a single flag controls both manual and background paths.
   */
  schedule(): void {
    if (this.disposed) return;
    if (!this.client.getStatus().autoSyncEnabled) return;
    if (this.running) {
      // One follow-up run covers every event that arrived mid-flight; deeper queues are pointless
      // because the next run pushes the whole snapshot anyway.
      this.queued = true;
      return;
    }

    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.run();
    }, this.debounceMs);
  }

  /** Cancels a pending run (e.g. the reader was closed without progress). */
  cancel(): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
  }

  /**
   * Runs a sync immediately.
   *
   * Returns `skipped` when a run is already in flight, matching `exclusiveSync`'s "already syncing"
   * behaviour instead of stacking duplicate pushes.
   */
  async run(): Promise<SyncRunResult> {
    if (this.disposed) return { ok: false, error: "runner disposed", skipped: true };
    if (this.running) return { ok: false, error: "sync already running", skipped: true };

    this.running = true;
    try {
      const updates = await this.collectUpdates();
      const pushed = await this.client.push(updates ?? emptyUpdates());
      const result: SyncRunResult = {
        ok: true,
        savedAt: pushed.savedAt,
        favorites: pushed.favorites,
        history: pushed.history,
        readEpisodes: pushed.readEpisodes,
      };
      this.onResult?.(result);
      return result;
    } catch (error) {
      const result: SyncRunResult = {
        ok: false,
        error: error instanceof Error ? error.message : String(error),
      };
      this.onResult?.(result);
      return result;
    } finally {
      this.running = false;
      if (this.queued) {
        this.queued = false;
        this.schedule();
      }
    }
  }

  /** Stops all future scheduling. Does not abort an in-flight request. */
  dispose(): void {
    this.disposed = true;
    this.cancel();
  }
}
