/**
 * Read-history store: where the user stopped in each series, mirrored into `chrome.storage.local`.
 *
 * Two storage keys are used because they answer different questions:
 *
 * - {@link HISTORY_STORAGE_KEY} (`comics8.history`) holds one {@link HistoryRecord} per series —
 *   the resumable "continue reading" row, shaped like the `/sync` `SyncHistory` payload.
 * - {@link READ_EPISODES_STORAGE_KEY} (`comics8.history.readEpisodes`) holds the per-chapter read
 *   set. A single history row cannot answer {@link HistoryStore.isEpisodeRead} for the *other*
 *   chapters of a series, and the Kotlin model keeps the same split (`ReadHistoryRecord` vs
 *   `ReadEpisodeRecord`).
 *
 * The records are Svelte 5 runes, so a component can render `historyStore.history` and re-render on
 * every progress update.
 */

import { sanitizeCloneable } from "../sources/serialize";
import type { EpisodeItem } from "../sources/types";

/** `chrome.storage.local` key holding one progress record per series. */
export const HISTORY_STORAGE_KEY = "comics8.history";
/** `chrome.storage.local` key holding the set of chapters already read. */
export const READ_EPISODES_STORAGE_KEY = "comics8.history.readEpisodes";

/** Where the reader stopped inside one series. */
export type HistoryRecord = {
  sourceId: string;
  toonId: string;
  title: string;
  thumbUrl: string;
  /** Chapter the user last opened; empty when no chapter is recorded yet. */
  lastWrId: string;
  lastEpisodeTitle: string;
  /** Zero-based page inside `lastWrId`. */
  pageIndex: number;
  /** Page count of `lastWrId` at the time it was read (0 = unknown). */
  totalPages: number;
  /** 1-based order of the chapter in the work (e.g. chapter 52). Matches Kotlin's lastReadOrder. */
  lastReadOrder?: number;
  /** Total episode count of the work. Matches Kotlin's totalEpisodes. */
  totalEpisodes?: number;
  /** Epoch milliseconds of the last progress update. */
  updatedAt: number;
};

/** One read chapter; the identity is `sourceId` + `toonId` + `wrId`. */
export type ReadEpisodeRecord = {
  sourceId: string;
  toonId: string;
  wrId: string;
  readAt: number;
};

export class HistoryStore {
  history = $state<HistoryRecord[]>([]);
  readEpisodes = $state<ReadEpisodeRecord[]>([]);

  #initPromise: Promise<void> | null = null;
  #writeChain: Promise<void> = Promise.resolve();

  /** Reads both history keys once; safe to call from several components. */
  init(): Promise<void> {
    if (!this.#initPromise) {
      this.#initPromise = this.#load();
    }
    return this.#initPromise;
  }

  /**
   * Records reading progress and marks `episode` as read.
   *
   * Progress arrives on every page turn, so this keeps the in-memory list updated synchronously and
   * writes storage afterwards; the write is appended to {@link #writeChain} so two quick updates
   * cannot land out of order.
   */
  async recordProgress(
    sourceId: string,
    toonId: string,
    title: string,
    thumbUrl: string,
    episode: EpisodeItem | null,
    pageIndex: number,
    totalPages: number,
    lastReadOrder?: number,
    totalEpisodes?: number,
  ): Promise<void> {
    await this.init();

    const source = normaliseSourceId(sourceId);
    const id = String(toonId ?? "").trim();
    if (!id) return;

    const previous = this.history.find(
      (entry) => entry.sourceId === source && entry.toonId === id,
    );

    const record: HistoryRecord = {
      sourceId: source,
      toonId: id,
      title: preferNonEmpty(title, previous?.title),
      thumbUrl: preferNonEmpty(thumbUrl, previous?.thumbUrl),
      lastWrId: preferNonEmpty(episode?.wrId ?? "", previous?.lastWrId),
      lastEpisodeTitle: preferNonEmpty(episode?.title ?? "", previous?.lastEpisodeTitle),
      pageIndex: normaliseIndex(pageIndex),
      totalPages: normaliseIndex(totalPages),
      lastReadOrder: Math.max(
        normaliseIndex(lastReadOrder),
        previous?.lastReadOrder ?? 0,
      ),
      totalEpisodes: normaliseIndex(totalEpisodes ?? previous?.totalEpisodes ?? 0),
      updatedAt: Date.now(),
    };

    // Newest first, so `getHistory()` is already in display order.
    this.history = [record, ...this.history.filter((entry) => entry !== previous)];
    this.#markRead(source, id, record.lastWrId);

    await this.#persist();
  }

  /** The progress row for a series, or `null` when it has never been opened. */
  getProgress(sourceId: string, toonId: string): HistoryRecord | null {
    const source = normaliseSourceId(sourceId);
    const id = String(toonId ?? "").trim();
    if (!id) return null;
    return this.history.find((entry) => entry.sourceId === source && entry.toonId === id) ?? null;
  }

  /** True once a chapter has been opened at least once. */
  isEpisodeRead(sourceId: string, toonId: string, wrId: string): boolean {
    const source = normaliseSourceId(sourceId);
    const id = String(toonId ?? "").trim();
    const episode = String(wrId ?? "").trim();
    if (!id || !episode) return false;

    // A recorded last chapter is read even if its read-set entry was pruned with the history row.
    if (this.getProgress(source, id)?.lastWrId === episode) return true;

    return this.readEpisodes.some(
      (entry) => entry.sourceId === source && entry.toonId === id && entry.wrId === episode,
    );
  }

  /** Every progress row, most recently read first. */
  getHistory(): HistoryRecord[] {
    return [...this.history].sort((a, b) => b.updatedAt - a.updatedAt);
  }

  /**
   * Applies remote history and read-episodes from a sync pull or backup restore.
   * Mirrors `DesktopSyncStorageAdapter.applyPayload`:
   * - Saves missing history or updates when remote has a higher read order or newer timestamp.
   * - Merges read episodes into the local set.
   */
  async applyRemoteHistory(
    remoteHistory: Array<
      Partial<HistoryRecord> & {
        toonTitle?: string;
        toonThumbUrl?: string;
        lastReadOrder?: number;
        totalEpisodes?: number;
        lastReadAt?: number;
      }
    >,
    remoteReadEpisodes: Array<Partial<ReadEpisodeRecord>>,
  ): Promise<{ historyCount: number; episodeCount: number }> {
    await this.init();

    let histChanged = 0;
    const historyMap = new Map<string, HistoryRecord>();
    for (const h of this.history) {
      historyMap.set(`${h.sourceId}\u0000${h.toonId}`, h);
    }

    if (Array.isArray(remoteHistory)) {
      for (const r of remoteHistory) {
        if (!r || typeof r !== "object") continue;
        const sourceId = normaliseSourceId(r.sourceId ?? "");
        const toonId = String(r.toonId ?? "").trim();
        if (!toonId) continue;

        const key = `${sourceId}\u0000${toonId}`;
        const existing = historyMap.get(key);
        const remoteOrder = normaliseIndex(r.lastReadOrder ?? r.pageIndex ?? 0);
        const remoteTotal = normaliseIndex(r.totalEpisodes ?? r.totalPages ?? 0);
        const remoteTime =
          typeof r.updatedAt === "number" && Number.isFinite(r.updatedAt)
            ? r.updatedAt
            : typeof r.lastReadAt === "number" && Number.isFinite(r.lastReadAt)
              ? r.lastReadAt
              : 0;

        if (!existing) {
          historyMap.set(key, {
            sourceId,
            toonId,
            title: r.title ?? r.toonTitle ?? "",
            thumbUrl: r.thumbUrl ?? r.toonThumbUrl ?? "",
            lastWrId: r.lastWrId ?? "",
            lastEpisodeTitle: r.lastEpisodeTitle ?? "",
            pageIndex: normaliseIndex(r.pageIndex ?? 0),
            totalPages: normaliseIndex(r.totalPages ?? 0),
            lastReadOrder: remoteOrder,
            totalEpisodes: remoteTotal,
            updatedAt: remoteTime || Date.now(),
          });
          histChanged++;
        } else {
          const currentOrder = existing.lastReadOrder ?? 0;
          const currentTotal = existing.totalEpisodes ?? 0;
          const shouldUpdate =
            remoteOrder > currentOrder ||
            (remoteOrder === currentOrder && remoteTotal > currentTotal) ||
            (remoteOrder === currentOrder && remoteTime >= existing.updatedAt);
          if (shouldUpdate) {
            existing.title = preferNonEmpty(r.title ?? r.toonTitle ?? "", existing.title);
            existing.thumbUrl = preferNonEmpty(r.thumbUrl ?? r.toonThumbUrl ?? "", existing.thumbUrl);
            existing.lastWrId = preferNonEmpty(r.lastWrId ?? "", existing.lastWrId);
            existing.lastEpisodeTitle = preferNonEmpty(
              r.lastEpisodeTitle ?? "",
              existing.lastEpisodeTitle,
            );
            existing.lastReadOrder = Math.max(existing.lastReadOrder ?? 0, remoteOrder);
            existing.totalEpisodes = remoteTotal || existing.totalEpisodes || 0;
            existing.pageIndex = normaliseIndex(r.pageIndex ?? existing.pageIndex);
            existing.totalPages = normaliseIndex(r.totalPages ?? existing.totalPages);
            existing.updatedAt = remoteTime || existing.updatedAt;
            histChanged++;
          }
        }
      }
    }

    let epChanged = 0;
    const episodeSet = new Set<string>();
    for (const ep of this.readEpisodes) {
      episodeSet.add(`${ep.sourceId}\u0000${ep.toonId}\u0000${ep.wrId}`);
    }

    const newEpisodes = [...this.readEpisodes];
    if (Array.isArray(remoteReadEpisodes)) {
      for (const rep of remoteReadEpisodes) {
        if (!rep || typeof rep !== "object") continue;
        const sourceId = normaliseSourceId(rep.sourceId ?? "");
        const toonId = String(rep.toonId ?? "").trim();
        const wrId = String(rep.wrId ?? "").trim();
        if (!toonId || !wrId) continue;

        const key = `${sourceId}\u0000${toonId}\u0000${wrId}`;
        if (!episodeSet.has(key)) {
          episodeSet.add(key);
          newEpisodes.push({
            sourceId,
            toonId,
            wrId,
            readAt:
              typeof rep.readAt === "number" && Number.isFinite(rep.readAt)
                ? rep.readAt
                : Date.now(),
          });
          epChanged++;
        }
      }
    }

    if (histChanged > 0) {
      this.history = Array.from(historyMap.values()).sort((a, b) => b.updatedAt - a.updatedAt);
    }
    if (epChanged > 0) {
      this.readEpisodes = newEpisodes;
    }
    if (histChanged > 0 || epChanged > 0) {
      await this.#persist();
    }

    return { historyCount: histChanged, episodeCount: epChanged };
  }

  /** Forgets a series entirely (progress row and read set). */
  async deleteHistory(sourceId: string, toonId: string): Promise<void> {
    await this.init();

    const source = normaliseSourceId(sourceId);
    const id = String(toonId ?? "").trim();
    if (!id) return;

    this.history = this.history.filter(
      (entry) => !(entry.sourceId === source && entry.toonId === id),
    );
    this.readEpisodes = this.readEpisodes.filter(
      (entry) => !(entry.sourceId === source && entry.toonId === id),
    );
    await this.#persist();
  }

  /** Adds `wrId` to the read set (idempotent). */
  #markRead(sourceId: string, toonId: string, wrId: string): void {
    const episode = String(wrId ?? "").trim();
    if (!episode) return;
    if (
      this.readEpisodes.some(
        (entry) =>
          entry.sourceId === sourceId && entry.toonId === toonId && entry.wrId === episode,
      )
    ) {
      return;
    }
    this.readEpisodes = [
      { sourceId, toonId, wrId: episode, readAt: Date.now() },
      ...this.readEpisodes,
    ];
  }

  async #load(): Promise<void> {
    const area = chromeStorageArea();
    if (!area) return;
    try {
      const stored = await area.get([HISTORY_STORAGE_KEY, READ_EPISODES_STORAGE_KEY]);
      this.history = parseHistory(stored?.[HISTORY_STORAGE_KEY]);
      this.readEpisodes = parseReadEpisodes(stored?.[READ_EPISODES_STORAGE_KEY]);
    } catch {
      this.history = [];
      this.readEpisodes = [];
    }
  }

  async #persist(): Promise<void> {
    // Serialise writes: two page turns must not race into `chrome.storage.local` out of order.
    this.#writeChain = this.#writeChain.then(
      () => this.#writeNow(),
      () => this.#writeNow(),
    );
    return this.#writeChain;
  }

  async #writeNow(): Promise<void> {
    const area = chromeStorageArea();
    if (!area) return;
    // `chrome.storage` requires cloneable data; plugin-sourced strings are plain, but be explicit.
    await area.set({
      [HISTORY_STORAGE_KEY]: sanitizeCloneable<HistoryRecord[]>(this.getHistory()),
      [READ_EPISODES_STORAGE_KEY]: sanitizeCloneable<ReadEpisodeRecord[]>(this.readEpisodes),
    });
  }
}

/** `"local"` is reserved for library files and is rejected by the sync backend. */
function normaliseSourceId(sourceId: string): string {
  const value = String(sourceId ?? "").trim();
  return value === "" ? "local" : value;
}

function preferNonEmpty(next: string, previous: string | undefined): string {
  const value = typeof next === "string" ? next : "";
  if (value.trim() !== "") return value;
  return previous ?? "";
}

/** Page indexes/counts are clamped to non-negative integers. */
function normaliseIndex(value: unknown): number {
  const number = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(number) || number < 0) return 0;
  return Math.trunc(number);
}

function chromeStorageArea(): chrome.storage.StorageArea | null {
  if (typeof chrome === "undefined" || !chrome.storage?.local) return null;
  return chrome.storage.local;
}

/** Keeps only well-formed rows, so a corrupted/older payload cannot poison the store. */
function parseHistory(value: unknown): HistoryRecord[] {
  if (!Array.isArray(value)) return [];
  const out: HistoryRecord[] = [];
  const seen = new Set<string>();
  for (const entry of value) {
    if (typeof entry !== "object" || entry === null) continue;
    const record = entry as Partial<HistoryRecord>;
    const toonId = typeof record.toonId === "string" ? record.toonId.trim() : "";
    if (!toonId) continue;
    const sourceId = normaliseSourceId(typeof record.sourceId === "string" ? record.sourceId : "");
    const key = `${sourceId}\u0000${toonId}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({
      sourceId,
      toonId,
      title: typeof record.title === "string" ? record.title : "",
      thumbUrl: typeof record.thumbUrl === "string" ? record.thumbUrl : "",
      lastWrId: typeof record.lastWrId === "string" ? record.lastWrId : "",
      lastEpisodeTitle: typeof record.lastEpisodeTitle === "string" ? record.lastEpisodeTitle : "",
      pageIndex: normaliseIndex(record.pageIndex),
      totalPages: normaliseIndex(record.totalPages),
      lastReadOrder: normaliseIndex(record.lastReadOrder),
      totalEpisodes: normaliseIndex(record.totalEpisodes),
      updatedAt:
        typeof record.updatedAt === "number" && Number.isFinite(record.updatedAt)
          ? record.updatedAt
          : 0,
    });
  }
  return out;
}

function parseReadEpisodes(value: unknown): ReadEpisodeRecord[] {
  if (!Array.isArray(value)) return [];
  const out: ReadEpisodeRecord[] = [];
  const seen = new Set<string>();
  for (const entry of value) {
    if (typeof entry !== "object" || entry === null) continue;
    const record = entry as Partial<ReadEpisodeRecord>;
    const toonId = typeof record.toonId === "string" ? record.toonId.trim() : "";
    const wrId = typeof record.wrId === "string" ? record.wrId.trim() : "";
    if (!toonId || !wrId) continue;
    const sourceId = normaliseSourceId(typeof record.sourceId === "string" ? record.sourceId : "");
    const key = `${sourceId}\u0000${toonId}\u0000${wrId}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({
      sourceId,
      toonId,
      wrId,
      readAt: typeof record.readAt === "number" && Number.isFinite(record.readAt) ? record.readAt : 0,
    });
  }
  return out;
}

/** Process-wide store used by the extension's single reader surface. */
export const historyStore = new HistoryStore();
