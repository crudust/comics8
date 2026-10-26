/**
 * Offline downloads: the web twin of `DesktopDownloadManager` + the `downloaded_episodes` table.
 *
 * Kotlin owners:
 * - `desktop/src/main/kotlin/data/DesktopDownloadManager.kt` — serial queue, per-episode directory,
 *   numbered image files, one record per finished episode.
 * - `desktop/src/main/kotlin/data/DesktopDatabase.kt` (`downloaded_episodes`) — the metadata row.
 * - `core/src/main/kotlin/source/DownloadLayout.kt` — the on-disk layout, reproduced here as
 *   `Comics8/downloads/<sourceId>/<toonId>/<wrId>/001.jpg`.
 *
 * Two browsers APIs replace the desktop's `File`/SQLite pair:
 *
 * 1. **Metadata** lives in `chrome.storage.local` under {@link DOWNLOADED_EPISODES_KEY}. One row per
 *    finished episode, keyed by `sourceId` + `toonId` + `wrId`, exactly like the SQLite primary key.
 *    The reduced row type omits the desktop's `toonTitle`/`toonThumbUrl` columns, so the toon
 *    header needed by {@link DownloadsStore.getDownloadedToonSummaries} is kept next to it under
 *    {@link DOWNLOADED_TOONS_KEY}.
 * 2. **Bytes** live in a directory the user picks with `showDirectoryPicker({ mode: "readwrite" })`
 *    (File System Access API). The handle itself is persisted in IndexedDB
 *    (`comics8_storage` / `handles` / `download_dir`) because handles are structured-cloneable and
 *    must survive a reload; `chrome.storage.local` cannot hold them.
 *
 * Queue semantics mirror `DownloadQueueEngine`: serial (one episode at a time), duplicates of an
 * already-downloaded chapter are dropped before they enter the queue, a failed chapter is skipped
 * without stopping the rest, and progress resets to idle when the queue drains. Unlike the Kotlin
 * manager the web queue is owned by this module, so a UI can read the reactive
 * {@link DownloadsStore.progress} directly.
 *
 * Everything browser-specific is injectable:
 * - {@link DownloadsStoreOptions.folder} replaces the picker/IndexedDB pair (unit tests);
 * - {@link DownloadsStoreOptions.fetchBytes} replaces the background-worker fetch (unit tests).
 */

import { backgroundFetchBytes } from "../background-fetch";
import { sanitizeCloneable } from "../sources/serialize";
import type { DesktopEpisodeItem, DesktopToonItem } from "../state/desktop-state.svelte";

// ---------------------------------------------------------------------------------------------
// constants and row types
// ---------------------------------------------------------------------------------------------

/** `chrome.storage.local` key holding one {@link DownloadedEpisodeRecord} per finished episode. */
export const DOWNLOADED_EPISODES_KEY = "comics8:downloaded_episodes";
/**
 * `chrome.storage.local` key holding the toon header per work (`sourceId:toonId`).
 *
 * The desktop row carries `toonTitle` / `toonThumbUrl`; the web row type is the reduced one, so the
 * header that {@link DownloadsStore.getDownloadedToonSummaries} renders lives here.
 */
export const DOWNLOADED_TOONS_KEY = "comics8:downloaded_toons";

/** IndexedDB database/store/key of the persisted download-directory handle. */
export const DOWNLOAD_HANDLE_DB = "comics8_storage";
export const DOWNLOAD_HANDLE_STORE = "handles";
export const DOWNLOAD_HANDLE_KEY = "download_dir";

/** Root folder created inside the picked directory (`DownloadLayout`'s `baseDir`). */
export const DOWNLOAD_ROOT = "Comics8";
/** Child of {@link DOWNLOAD_ROOT} that holds every source tree. */
export const DOWNLOAD_SUBDIR = "downloads";

/** One finished episode; the SQLite `downloaded_episodes` row without the storage-only columns. */
export type DownloadedEpisodeRecord = {
  sourceId: string;
  toonId: string;
  wrId: string;
  episodeTitle: string;
  episodeHref: string;
  /** Number of image files written (`imageCount`). */
  pageCount: number;
  /** Epoch milliseconds (`downloadedAt`). */
  downloadedAt: number;
};

/** Key formatting for work in download store (`sourceId:toonId`). */
export function downloadedWorkKey(sourceId: string, toonId: string): string {
  return `${sourceId}:${toonId}`;
}

/** `com.comics8.core.model.DownloadedToonSummary` 1:1 match. */
export type DownloadedToonSummary = {
  sourceId: string;
  toonId: string;
  toonTitle: string;
  toonThumbUrl: string;
  toonHref: string;
  episodeCount: number;
  totalBytes: number;
  latestDownloadedAt: number;
  // Aliases for compatibility
  title?: string;
  thumbUrl?: string;
  lastDownloadedAt?: number;
};

/** `DesktopUiState.downloadProgress` (`DownloadProgressState`) 1:1 match. */
export type DownloadProgressState = {
  isRunning: boolean;
  currentTask: {
    episodeTitle: string;
    progress: number;
    total: number;
    episode?: DesktopEpisodeItem | { wrId: string; title: string };
  } | null;
  currentImage: number;
  totalImages: number;
  queueSize: number;
  activeToonTitle: string;
};

export const EMPTY_DOWNLOAD_PROGRESS: DownloadProgressState = {
  isRunning: false,
  currentTask: null,
  currentImage: 0,
  totalImages: 0,
  queueSize: 0,
  activeToonTitle: "",
};

export type DownloadProgress = DownloadProgressState;

/** Toon header persisted next to the rows (`toonTitle` / `toonThumbUrl` on the desktop row). */
type DownloadedToonHeader = {
  title: string;
  thumbUrl: string;
  href?: string;
};

/** One queued chapter, the web `DownloadTask`. */
type DownloadTask = {
  sourceId: string;
  toonId: string;
  series: DesktopToonItem;
  episode: DesktopEpisodeItem;
};

/** Replaces the picker + IndexedDB persistence (unit tests, or a custom backend). */
export type DownloadFolderProvider = {
  /** Same contract as {@link DownloadsStore.getDownloadFolder}. */
  get(): Promise<FileSystemDirectoryHandle | null>;
  /** Same contract as {@link DownloadsStore.promptDownloadFolder}, returning the chosen handle. */
  prompt(): Promise<FileSystemDirectoryHandle | null>;
  /** Forgets the persisted handle. */
  clear(): Promise<void>;
};

export type DownloadsStoreOptions = {
  folder?: DownloadFolderProvider;
  /** Downloads one image; defaults to the background worker (`backgroundFetchBytes`). */
  fetchBytes?: (url: string) => Promise<Uint8Array>;
  /** Overrides `URL.createObjectURL` / `revokeObjectURL` (unit tests without blob URLs). */
  objectUrls?: {
    create(blob: Blob): string;
    revoke(url: string): void;
  };
  resolveImages?: (
    sourceId: string,
    episode: DesktopEpisodeItem,
    series: DesktopToonItem,
  ) => Promise<string[]>;
  headersFor?: (sourceId: string) => Record<string, string> | undefined;
  onEpisodeDownloaded?: (workKey: string, wrId: string) => void;
  onProgress?: (progress: DownloadProgressState) => void;
};

// ---------------------------------------------------------------------------------------------
// File System Access surface (Chrome-only members are missing from `lib.dom`)
// ---------------------------------------------------------------------------------------------

type DirectoryPickerWindow = Window & {
  showDirectoryPicker?: (options?: {
    id?: string;
    mode?: "read" | "write" | "readwrite";
  }) => Promise<FileSystemDirectoryHandle>;
};

type PermissionDescriptor = { mode?: "read" | "write" | "readwrite" };

type PermissionCapableHandle = FileSystemHandle & {
  queryPermission?: (descriptor?: PermissionDescriptor) => Promise<PermissionState>;
};

type IterableDirectoryHandle = FileSystemDirectoryHandle & {
  entries?: () => AsyncIterableIterator<[string, FileSystemHandle]>;
};

const IMAGE_FILE_PATTERN = /\.(?:jpe?g|png|webp|avif|gif)$/i;

/** Path segments below the picked directory: `[작품 제목, 회차 제목]`. */
export function downloadEpisodePath(
  sourceId: string,
  toonId: string,
  wrId: string,
  toonTitle?: string,
  episodeTitle?: string,
): string[] {
  const seriesFolder = downloadPathSegment(toonTitle || toonId);
  const chapterFolder = downloadPathSegment(episodeTitle || wrId);
  return [seriesFolder, chapterFolder];
}

/**
 * One path segment the File System Access API accepts.
 *
 * The desktop passes ids straight to `File` (where only `/` is special); the browser API rejects
 * `/` and `\` on every platform and Windows rejects `: * ? " < > |` as well, so those are folded to
 * `_` when they occur. Ordinary ids (`e3`, `42`) are untouched.
 */
export function downloadPathSegment(value: string): string {
  const trimmed = String(value ?? "").trim();
  if (trimmed === "" || trimmed === "." || trimmed === "..") return "_";
  return trimmed.replace(/[\\/:*?"<>|\u0000-\u001f]/g, "_");
}

/** `ImageBatchDownload.toNumberedFiles`' file name, three digits per the web layout. */
export function downloadImageName(index: number): string {
  return `${String(Math.trunc(index) + 1).padStart(3, "0")}.jpg`;
}

function downloadRowKey(sourceId: string, toonId: string, wrId: string): string {
  return `${sourceId}\u0000${toonId}\u0000${wrId}`;
}

function toonHeaderKey(sourceId: string, toonId: string): string {
  return `${sourceId}\u0000${toonId}`;
}

/** `WorkId.DEFAULT_SOURCE`; the same fallback the history store uses for a blank source id. */
function normaliseSourceId(sourceId: string | null | undefined): string {
  const value = String(sourceId ?? "").trim();
  return value === "" ? "local" : value;
}

function normaliseText(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function normaliseCount(value: unknown): number {
  const number = typeof value === "number" ? value : Number(value);
  if (!Number.isFinite(number) || number < 0) return 0;
  return Math.trunc(number);
}

function isImageName(name: string): boolean {
  return IMAGE_FILE_PATTERN.test(name);
}

// ---------------------------------------------------------------------------------------------
// IndexedDB handle persistence
// ---------------------------------------------------------------------------------------------

function idbFactory(): IDBFactory | null {
  return typeof indexedDB !== "undefined" ? indexedDB : null;
}

function openHandleDb(): Promise<IDBDatabase | null> {
  const factory = idbFactory();
  if (!factory) return Promise.resolve(null);
  return new Promise((resolve) => {
    let request: IDBOpenDBRequest;
    try {
      request = factory.open(DOWNLOAD_HANDLE_DB, 1);
    } catch {
      resolve(null);
      return;
    }
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(DOWNLOAD_HANDLE_STORE)) {
        db.createObjectStore(DOWNLOAD_HANDLE_STORE);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
    request.onblocked = () => resolve(null);
  });
}

async function readStoredHandle(): Promise<FileSystemDirectoryHandle | null> {
  const db = await openHandleDb();
  if (!db) return null;
  try {
    return await new Promise<FileSystemDirectoryHandle | null>((resolve) => {
      const tx = db.transaction(DOWNLOAD_HANDLE_STORE, "readonly");
      const request = tx.objectStore(DOWNLOAD_HANDLE_STORE).get(DOWNLOAD_HANDLE_KEY);
      request.onsuccess = () =>
        resolve((request.result as FileSystemDirectoryHandle | undefined) ?? null);
      request.onerror = () => resolve(null);
      tx.onabort = () => resolve(null);
    });
  } catch {
    return null;
  } finally {
    db.close();
  }
}

async function writeStoredHandle(handle: FileSystemDirectoryHandle): Promise<void> {
  const db = await openHandleDb();
  if (!db) return;
  try {
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(DOWNLOAD_HANDLE_STORE, "readwrite");
      tx.objectStore(DOWNLOAD_HANDLE_STORE).put(handle, DOWNLOAD_HANDLE_KEY);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error ?? new Error("IndexedDB write failed"));
      tx.onabort = () => reject(tx.error ?? new Error("IndexedDB write aborted"));
    });
  } finally {
    db.close();
  }
}

async function deleteStoredHandle(): Promise<void> {
  const db = await openHandleDb();
  if (!db) return;
  try {
    await new Promise<void>((resolve) => {
      const tx = db.transaction(DOWNLOAD_HANDLE_STORE, "readwrite");
      tx.objectStore(DOWNLOAD_HANDLE_STORE).delete(DOWNLOAD_HANDLE_KEY);
      tx.oncomplete = () => resolve();
      tx.onerror = () => resolve();
      tx.onabort = () => resolve();
    });
  } finally {
    db.close();
  }
}

function chromeStorageArea(): chrome.storage.StorageArea | null {
  if (typeof chrome === "undefined" || !chrome.storage?.local) return null;
  return chrome.storage.local;
}

// ---------------------------------------------------------------------------------------------
// payload parsing
// ---------------------------------------------------------------------------------------------

/** Keeps only well-formed rows, deduped by the SQLite key and ordered newest first. */
export function parseDownloadedEpisodes(value: unknown): DownloadedEpisodeRecord[] {
  if (!Array.isArray(value)) return [];
  const out: DownloadedEpisodeRecord[] = [];
  const seen = new Set<string>();
  for (const entry of value) {
    if (typeof entry !== "object" || entry === null) continue;
    const record = entry as Partial<DownloadedEpisodeRecord>;
    const toonId = normaliseText(record.toonId).trim();
    const wrId = normaliseText(record.wrId).trim();
    if (!toonId || !wrId) continue;
    const sourceId = normaliseSourceId(record.sourceId);
    const key = downloadRowKey(sourceId, toonId, wrId);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({
      sourceId,
      toonId,
      wrId,
      episodeTitle: normaliseText(record.episodeTitle),
      episodeHref: normaliseText(record.episodeHref),
      pageCount: normaliseCount(record.pageCount),
      downloadedAt:
        typeof record.downloadedAt === "number" && Number.isFinite(record.downloadedAt)
          ? Math.trunc(record.downloadedAt)
          : 0,
    });
  }
  return out.sort((a, b) => b.downloadedAt - a.downloadedAt);
}

function parseDownloadedToons(value: unknown): Record<string, DownloadedToonHeader> {
  if (typeof value !== "object" || value === null) return {};
  const out: Record<string, DownloadedToonHeader> = {};
  for (const [key, header] of Object.entries(value)) {
    if (typeof header !== "object" || header === null) continue;
    const candidate = header as Partial<DownloadedToonHeader>;
    out[key] = {
      title: normaliseText(candidate.title),
      thumbUrl: normaliseText(candidate.thumbUrl),
    };
  }
  return out;
}

// ---------------------------------------------------------------------------------------------
// the store
// ---------------------------------------------------------------------------------------------

export class DownloadsStore {
  // --- reactive state (DesktopUiState.downloadedWrIds / downloadProgress) -----------------------

  /** Every finished episode, newest first (the `downloaded_episodes` table). */
  records = $state<DownloadedEpisodeRecord[]>([]);

  /** `DesktopUiState.downloadedWrIds` — `wrId`s of every downloaded chapter. */
  downloadedWrIds = $derived([
    ...new Set(this.records.map((record) => record.wrId)),
  ]);

  /** `DesktopUiState.downloadProgress` (`DownloadProgressState`). */
  progress = $state<DownloadProgressState>({ ...EMPTY_DOWNLOAD_PROGRESS });

  /** Last per-episode failure (a failed chapter does not stop the queue). */
  lastError = $state<string | null>(null);

  /** Name of the folder selected for downloads, or null if not yet selected. */
  downloadFolderName = $state<string | null>(null);

  // --- private -----------------------------------------------------------------------------------

  readonly #options: DownloadsStoreOptions;
  readonly #folderProvider: DownloadFolderProvider | null;
  readonly #fetchBytes: (url: string) => Promise<Uint8Array>;
  readonly #objectUrls: { create(blob: Blob): string; revoke(url: string): void };

  #initPromise: Promise<void> | null = null;
  #writeChain: Promise<void> = Promise.resolve();

  /** Persisted handle cache: `undefined` = not read yet, `null` = no handle stored. */
  #storedHandle: FileSystemDirectoryHandle | null | undefined = undefined;
  #storedHandleRead: Promise<FileSystemDirectoryHandle | null> | null = null;

  #toonHeaders: Record<string, DownloadedToonHeader> = {};

  #pending: DownloadTask[] = [];
  #run: Promise<void> | null = null;

  /** `blob:` URLs per episode, so re-reading a chapter does not leak object URLs. */
  readonly #localUrls = new Map<string, string[]>();

  constructor(options: DownloadsStoreOptions = {}) {
    this.#options = options;
    this.#folderProvider = options.folder ?? null;
    this.#fetchBytes =
      options.fetchBytes ??
      (async (url: string) => {
        const response = await backgroundFetchBytes({ url });
        if (!response.ok) {
          throw new Error(`Comics8: image download failed (${response.status}) for ${url}`);
        }
        return response.bytes;
      });
    this.#objectUrls = options.objectUrls ?? {
      create: (blob) => URL.createObjectURL(blob),
      revoke: (url) => URL.revokeObjectURL(url),
    };
  }

  // --- metadata ---------------------------------------------------------------------------------

  /** Reads the persisted rows once; safe to call from several components. */
  init(): Promise<void> {
    if (!this.#initPromise) this.#initPromise = this.#load();
    return this.#initPromise;
  }

  /** True when the chapter has a metadata row (the desktop `isEpisodeDownloaded`). */
  isDownloaded(sourceId: string, toonId: string, wrId: string): boolean {
    const row = this.getDownloadedEpisode(sourceId, toonId, wrId);
    return row !== null;
  }

  /** The metadata row of one chapter, or `null` (`getDownloadedEpisode`). */
  getDownloadedEpisode(
    sourceId: string,
    toonId: string,
    wrId: string,
  ): DownloadedEpisodeRecord | null {
    const source = normaliseSourceId(sourceId);
    const toon = String(toonId ?? "").trim();
    const chapter = String(wrId ?? "").trim();
    if (!toon || !chapter) return null;
    return (
      this.records.find(
        (record) =>
          record.sourceId === source && record.toonId === toon && record.wrId === chapter,
      ) ?? null
    );
  }

  /** `getDownloadedEpisodesByToon(workId)`: the chapters of one work, newest first. */
  getDownloadedEpisodes(sourceId: string, toonId: string): DownloadedEpisodeRecord[] {
    const source = normaliseSourceId(sourceId);
    const toon = String(toonId ?? "").trim();
    if (!toon) return [];
    return this.records
      .filter((record) => record.sourceId === source && record.toonId === toon)
      .sort((a, b) => b.downloadedAt - a.downloadedAt);
  }

  /** `getDownloadedEpisodesBySource(sourceId)`. */
  getDownloadedEpisodesBySource(sourceId: string): DownloadedEpisodeRecord[] {
    const source = normaliseSourceId(sourceId);
    return this.records
      .filter((record) => record.sourceId === source)
      .sort((a, b) => b.downloadedAt - a.downloadedAt);
  }

  /**
   * `DesktopDownloadManager.getDownloadedToonSummaries(sourceId)`.
   *
   * Groups the rows per work and re-attaches the toon header the reduced row type does not carry.
   * The desktop additionally sums `totalBytes`; the web does not map byte counts (the folder owns
   * them), so that field is intentionally absent from {@link DownloadedToonSummary}.
   */
  getDownloadedToonSummaries(sourceId?: string): DownloadedToonSummary[] {
    const rows =
      sourceId === undefined ? [...this.records] : this.getDownloadedEpisodesBySource(sourceId);
    const groups = new Map<string, DownloadedToonSummary>();
    for (const row of rows) {
      const key = toonHeaderKey(row.sourceId, row.toonId);
      const header = this.#toonHeaders[key];
      const existing = groups.get(key);
      if (existing) {
        existing.episodeCount += 1;
        existing.latestDownloadedAt = Math.max(existing.latestDownloadedAt, row.downloadedAt);
        existing.lastDownloadedAt = existing.latestDownloadedAt;
        if (existing.toonTitle.trim() === "" && header?.title) {
          existing.toonTitle = header.title;
          existing.title = header.title;
        }
        if (existing.toonThumbUrl.trim() === "" && header?.thumbUrl) {
          existing.toonThumbUrl = header.thumbUrl;
          existing.thumbUrl = header.thumbUrl;
        }
        continue;
      }
      const title = header?.title ?? "";
      const thumbUrl = header?.thumbUrl ?? "";
      groups.set(key, {
        sourceId: row.sourceId,
        toonId: row.toonId,
        toonTitle: title,
        title,
        toonThumbUrl: thumbUrl,
        thumbUrl,
        toonHref: header?.href ?? row.episodeHref ?? "",
        episodeCount: 1,
        totalBytes: 0,
        latestDownloadedAt: row.downloadedAt,
        lastDownloadedAt: row.downloadedAt,
      });
    }
    return [...groups.values()].sort((a, b) => b.latestDownloadedAt - a.latestDownloadedAt);
  }

  getSummaries(sourceId?: string): DownloadedToonSummary[] {
    return this.getDownloadedToonSummaries(sourceId);
  }

  getDownloadedWrIds(workKey: string): Set<string> {
    const [sourceId, ...rest] = workKey.split(":");
    const toonId = rest.join(":");
    const episodes = this.getDownloadedEpisodes(sourceId, toonId);
    return new Set(episodes.map((e) => e.wrId));
  }

  async deleteToonDownloads(workKey: string): Promise<void> {
    const [sourceId, ...rest] = workKey.split(":");
    const toonId = rest.join(":");
    await this.deleteDownloadedToon(sourceId, toonId);
  }

  /** `deleteDownloadedEpisode`: drops the row and the on-disk chapter directory. */
  async deleteDownloadedEpisode(sourceId: string, toonId: string, wrId: string): Promise<void> {
    await this.init();
    const source = normaliseSourceId(sourceId);
    const toon = String(toonId ?? "").trim();
    const chapter = String(wrId ?? "").trim();
    if (!toon || !chapter) return;

    const before = this.records.length;
    this.records = this.records.filter(
      (record) =>
        !(record.sourceId === source && record.toonId === toon && record.wrId === chapter),
    );
    if (this.records.length !== before) await this.#persistRecords();
    this.#releaseLocalUrls(downloadRowKey(source, toon, chapter));

    const handle = await this.getDownloadFolder();
    if (!handle) return;
    try {
      const parent = await resolveDirectory(
        handle,
        downloadEpisodePath(source, toon, chapter).slice(0, -1),
      );
      await parent?.removeEntry(downloadPathSegment(chapter), { recursive: true });
    } catch {
      // Already gone, or the user revoked the permission: the metadata is what the UI reads.
    }
  }

  /** `deleteDownloadedEpisodesByToon`: drops every chapter of one work and its directory. */
  async deleteDownloadedToon(sourceId: string, toonId: string): Promise<void> {
    await this.init();
    const source = normaliseSourceId(sourceId);
    const toon = String(toonId ?? "").trim();
    if (!toon) return;

    this.records = this.records.filter(
      (record) => !(record.sourceId === source && record.toonId === toon),
    );
    delete this.#toonHeaders[toonHeaderKey(source, toon)];
    await this.#writeNow();

    const handle = await this.getDownloadFolder();
    if (!handle) return;
    try {
      const parent = await resolveDirectory(
        handle,
        downloadEpisodePath(source, toon, "").slice(0, -1),
      );
      await parent?.removeEntry(downloadPathSegment(toon), { recursive: true });
    } catch {
      // Nothing on disk (or no permission); the metadata is already gone.
    }
  }

  // --- folder -----------------------------------------------------------------------------------

  /**
   * Asks the user for a download directory and remembers the handle.
   *
   * Calls `window.showDirectoryPicker({ mode: "readwrite" })`; a cancelled picker (or a browser
   * without the API) resolves `false` instead of throwing.
   */
  async promptDownloadFolder(): Promise<boolean> {
    const handle = await this.#promptHandle();
    if (!handle) return false;
    this.#storedHandle = handle;
    this.downloadFolderName = handle.name;
    await this.#storeHandle(handle);
    return true;
  }

  /**
   * The remembered directory, or `null` when nothing was picked / the permission was revoked.
   *
   * `queryPermission({ mode: "readwrite" })` is the authority: a persisted handle does not imply
   * write access after a browser restart.
   */
  async getDownloadFolder(): Promise<FileSystemDirectoryHandle | null> {
    if (this.#folderProvider) return this.#folderProvider.get();
    const handle = await this.#readHandle();
    if (!handle) return null;
    const permission = await queryWritePermission(handle);
    const valid = permission === "granted" ? handle : null;
    this.downloadFolderName = valid ? valid.name : null;
    return valid;
  }

  /** Forgets the picked directory (metadata rows are kept). */
  async clearDownloadFolder(): Promise<void> {
    this.#storedHandle = null;
    this.downloadFolderName = null;
    await this.#deleteHandle();
  }

  // --- queue ------------------------------------------------------------------------------------

  /**
   * `DesktopDownloadManager.enqueueEpisodes`: queue chapters that are not downloaded yet.
   *
   * Already-recorded chapters and tasks already waiting are skipped, exactly like the Kotlin
   * manager filters `getDownloadedEpisodesByToon(workId)` before enqueueing. The returned promise
   * resolves once this batch (and anything queued behind it) drained, so a caller can await the
   * files without polling `progress`.
   */
  async enqueueEpisodes(
    series: DesktopToonItem,
    episodes: readonly DesktopEpisodeItem[] | DesktopEpisodeItem[],
    resolveImagesOrSourceId?:
      | ((episode: DesktopEpisodeItem, series: DesktopToonItem) => Promise<string[]>)
      | string,
  ): Promise<void> {
    await this.init();
    if (!Array.isArray(episodes) || episodes.length === 0) return;

    const sourceId = normaliseSourceId(
      typeof resolveImagesOrSourceId === "string" ? resolveImagesOrSourceId : series.sourceId,
    );
    const toonId = String(series.id ?? "").trim();
    if (!toonId) return;

    const resolver =
      typeof resolveImagesOrSourceId === "function"
        ? resolveImagesOrSourceId
        : (ep: DesktopEpisodeItem, s: DesktopToonItem) =>
            this.#options.resolveImages
              ? this.#options.resolveImages(sourceId, ep, s)
              : Promise.resolve([]);

    const queued = new Set(
      this.#pending.map((task) => downloadRowKey(task.sourceId, task.toonId, task.episode.wrId)),
    );
    const tasks: DownloadTask[] = [];
    for (const episode of episodes) {
      const wrId = String(episode.wrId ?? "").trim();
      if (!wrId) continue;
      const key = downloadRowKey(sourceId, toonId, wrId);
      if (queued.has(key) || this.isDownloaded(sourceId, toonId, wrId)) continue;
      queued.add(key);
      tasks.push({ sourceId, toonId, series, episode });
    }
    if (tasks.length === 0) return;

    this.#pending.push(...tasks);
    this.#syncQueueSize();
    await this.#drain(resolver);
  }

  /** Drops every waiting task and resets progress (`cancelAll`; the running chapter finishes). */
  cancelAll(): void {
    this.#pending = [];
    this.#updateProgress({ ...EMPTY_DOWNLOAD_PROGRESS });
  }

  // --- local reading ----------------------------------------------------------------------------

  /**
   * `DesktopDownloadManager.getLocalEpisodeImages`: the chapter's image files as `blob:` URLs.
   *
   * Returns `null` when no folder is available, the directory is missing, or the chapter has no
   * readable image file — the caller then falls back to the plugin's remote URLs.
   */
  async getLocalEpisodeImages(
    sourceId: string,
    toonId: string,
    wrId: string,
  ): Promise<string[] | null> {
    const handle = await this.getDownloadFolder();
    if (!handle) return null;
    const source = normaliseSourceId(sourceId);
    const toon = String(toonId ?? "").trim();
    const chapter = String(wrId ?? "").trim();
    if (!toon || !chapter) return null;

    let directory: FileSystemDirectoryHandle | null = null;
    const record = this.getDownloadedEpisode(source, toon, chapter);
    const header = this.#toonHeaders[toonHeaderKey(source, toon)];
    const candidatePaths = [
      downloadEpisodePath(source, toon, chapter, header?.title, record?.episodeTitle),
      [DOWNLOAD_ROOT, DOWNLOAD_SUBDIR, downloadPathSegment(source), downloadPathSegment(toon), downloadPathSegment(chapter)],
    ];
    for (const p of candidatePaths) {
      try {
        directory = await resolveDirectory(handle, p);
        if (directory) break;
      } catch {
        // try legacy or title path
      }
    }
    if (!directory) return null;

    const names: string[] = [];
    try {
      for await (const [name, entry] of iterateEntries(directory)) {
        if (entry.kind === "file" && isImageName(name)) names.push(name);
      }
    } catch {
      return null;
    }
    // Files are written zero-padded (`001.jpg`, `002.jpg`), so plain name order is page order.
    names.sort();

    const key = downloadRowKey(source, toon, chapter);
    const urls: string[] = [];
    for (const name of names) {
      try {
        const fileHandle = await directory.getFileHandle(name);
        const file = await fileHandle.getFile();
        if (!file || file.size === 0) continue;
        urls.push(this.#objectUrls.create(file));
      } catch {
        // A file the user deleted behind our back: skip it rather than failing the chapter.
      }
    }
    if (urls.length === 0) return null;

    this.#releaseLocalUrls(key);
    this.#localUrls.set(key, urls);
    return [...urls];
  }

  /** Revokes every cached local `blob:` URL (shell teardown). */
  releaseAllLocalImages(): void {
    for (const key of [...this.#localUrls.keys()]) this.#releaseLocalUrls(key);
  }

  // --- internals: persistence -------------------------------------------------------------------

  async #load(): Promise<void> {
    const area = chromeStorageArea();
    if (area) {
      try {
        const stored = await area.get([DOWNLOADED_EPISODES_KEY, DOWNLOADED_TOONS_KEY]);
        this.records = parseDownloadedEpisodes(stored?.[DOWNLOADED_EPISODES_KEY]);
        this.#toonHeaders = parseDownloadedToons(stored?.[DOWNLOADED_TOONS_KEY]);
      } catch {
        this.records = [];
        this.#toonHeaders = {};
      }
    }
    await this.#readHandle().catch(() => null);
  }

  async #persistRecords(): Promise<void> {
    this.#writeChain = this.#writeChain.then(
      () => this.#writeNow(),
      () => this.#writeNow(),
    );
    return this.#writeChain;
  }

  async #writeNow(): Promise<void> {
    const area = chromeStorageArea();
    if (!area) return;
    await area.set({
      [DOWNLOADED_EPISODES_KEY]: sanitizeCloneable<DownloadedEpisodeRecord[]>(this.records),
      [DOWNLOADED_TOONS_KEY]: sanitizeCloneable<Record<string, DownloadedToonHeader>>(
        this.#toonHeaders,
      ),
    });
  }

  // --- internals: handle ------------------------------------------------------------------------

  async #readHandle(): Promise<FileSystemDirectoryHandle | null> {
    if (this.#storedHandle !== undefined) {
      this.downloadFolderName = this.#storedHandle?.name ?? null;
      return this.#storedHandle;
    }
    if (!this.#storedHandleRead) {
      this.#storedHandleRead = readStoredHandle().then((handle) => {
        this.#storedHandle = handle;
        this.downloadFolderName = handle?.name ?? null;
        this.#storedHandleRead = null;
        return handle;
      });
    }
    return this.#storedHandleRead;
  }

  async #storeHandle(handle: FileSystemDirectoryHandle): Promise<void> {
    try {
      await writeStoredHandle(handle);
    } catch {
      // IndexedDB may be unavailable (private mode); the in-memory handle still works this session.
    }
  }

  async #deleteHandle(): Promise<void> {
    try {
      await deleteStoredHandle();
    } catch {
      // Nothing persisted, or IndexedDB is unavailable.
    }
  }

  async #promptHandle(): Promise<FileSystemDirectoryHandle | null> {
    if (this.#folderProvider) return this.#folderProvider.prompt();
    const target = typeof window !== "undefined" ? (window as DirectoryPickerWindow) : undefined;
    const picker = target?.showDirectoryPicker;
    if (typeof picker !== "function") return null;
    try {
      return await picker.call(target, { id: "comics8-downloads", mode: "readwrite" });
    } catch {
      return null;
    }
  }

  // --- internals: queue -------------------------------------------------------------------------

  #updateProgress(next: DownloadProgressState): void {
    this.progress = next;
    this.#options.onProgress?.(next);
  }

  #syncQueueSize(): void {
    this.#updateProgress({
      ...this.progress,
      queueSize: this.#pending.length,
    });
  }

  async #drain(
    resolveImages: (
      episode: DesktopEpisodeItem,
      series: DesktopToonItem,
    ) => Promise<string[]>,
  ): Promise<void> {
    const run =
      this.#run ??
      (this.#run = this.#runQueue(resolveImages).finally(() => {
        this.#run = null;
      }));
    await run;
    // A batch enqueued after the loop's last emptiness check still has to run.
    if (this.#pending.length > 0) await this.#drain(resolveImages);
  }

  async #runQueue(
    resolveImages: (
      episode: DesktopEpisodeItem,
      series: DesktopToonItem,
    ) => Promise<string[]>,
  ): Promise<void> {
    while (this.#pending.length > 0) {
      const task = this.#pending.shift();
      if (!task) break;
      this.lastError = null;
      this.#updateProgress({
        isRunning: true,
        currentTask: {
          episodeTitle: task.episode.title ?? "",
          progress: 0,
          total: 0,
          episode: task.episode,
        },
        currentImage: 0,
        totalImages: 0,
        queueSize: this.#pending.length,
        activeToonTitle: task.series.title ?? "",
      });
      try {
        await this.#processTask(task, resolveImages);
      } catch (cause) {
        this.lastError = describeError(cause, "다운로드에 실패했습니다.");
      }
    }
    this.#updateProgress({ ...EMPTY_DOWNLOAD_PROGRESS });
  }

  async #processTask(
    task: DownloadTask,
    resolveImages: (
      episode: DesktopEpisodeItem,
      series: DesktopToonItem,
    ) => Promise<string[]>,
  ): Promise<void> {
    const { sourceId, toonId, series, episode } = task;
    const wrId = String(episode.wrId ?? "").trim();
    if (!wrId) return;

    let handle = await this.getDownloadFolder();
    if (!handle) {
      const ok = await this.promptDownloadFolder();
      if (ok) handle = await this.getDownloadFolder();
    }
    if (!handle) throw new Error("다운로드 폴더가 선택되지 않았습니다.");

    const urls = await resolveImages(episode, series);
    if (!Array.isArray(urls) || urls.length === 0) return;

    this.#reportProgress(urls.length, 0, task);

    const directory = await resolveDirectory(
      handle,
      downloadEpisodePath(sourceId, toonId, wrId, series.title, episode.title),
      { create: true },
    );
    if (!directory) throw new Error("다운로드 폴더를 만들지 못했습니다.");

    let written = 0;
    for (let index = 0; index < urls.length; index += 1) {
      const url = urls[index];
      if (typeof url !== "string" || url.trim() === "") continue;
      const bytes = await this.#fetchBytes(url);
      await writeFile(directory, downloadImageName(index), bytes);
      written += 1;
      this.#reportProgress(urls.length, written, task);
    }
    if (written === 0) return;

    const record: DownloadedEpisodeRecord = {
      sourceId,
      toonId,
      wrId,
      episodeTitle: episode.title ?? "",
      episodeHref: episode.href ?? "",
      pageCount: written,
      downloadedAt: Date.now(),
    };
    this.records = [
      record,
      ...this.records.filter(
        (row) =>
          !(row.sourceId === sourceId && row.toonId === toonId && row.wrId === wrId),
      ),
    ];
    this.#toonHeaders[toonHeaderKey(sourceId, toonId)] = {
      title: series.title ?? "",
      thumbUrl: series.thumbUrl ?? "",
      href: series.href ?? "",
    };
    this.#releaseLocalUrls(downloadRowKey(sourceId, toonId, wrId));
    await this.#writeNow();
    this.#options.onEpisodeDownloaded?.(toonHeaderKey(sourceId, toonId), wrId);
  }

  #reportProgress(total: number, progress: number, task?: DownloadTask): void {
    this.#updateProgress({
      isRunning: true,
      currentTask: {
        episodeTitle: task?.episode.title ?? this.progress.currentTask?.episodeTitle ?? "",
        progress,
        total,
        episode: task?.episode ?? this.progress.currentTask?.episode,
      },
      currentImage: progress,
      totalImages: total,
      queueSize: this.progress.queueSize,
      activeToonTitle: task?.series.title ?? this.progress.activeToonTitle,
    });
  }

  #releaseLocalUrls(key: string): void {
    const urls = this.#localUrls.get(key);
    if (!urls) return;
    this.#localUrls.delete(key);
    for (const url of urls) {
      try {
        this.#objectUrls.revoke(url);
      } catch {
        // Already revoked.
      }
    }
  }
}

// ---------------------------------------------------------------------------------------------
// File System Access helpers
// ---------------------------------------------------------------------------------------------

async function queryWritePermission(handle: FileSystemDirectoryHandle): Promise<PermissionState> {
  const capable = handle as PermissionCapableHandle;
  if (typeof capable.queryPermission !== "function") {
    // Not a real handle (or an older implementation without the permission API): trust it.
    return "granted";
  }
  try {
    return await capable.queryPermission({ mode: "readwrite" });
  } catch {
    return "denied";
  }
}

/** Walks (and optionally creates) `segments` below `root`. Missing paths resolve `null`. */
async function resolveDirectory(
  root: FileSystemDirectoryHandle,
  segments: string[],
  options: { create?: boolean } = {},
): Promise<FileSystemDirectoryHandle | null> {
  let directory = root;
  for (const segment of segments) {
    try {
      directory = await directory.getDirectoryHandle(segment, { create: options.create === true });
    } catch {
      return null;
    }
  }
  return directory;
}

function iterateEntries(
  directory: FileSystemDirectoryHandle,
): AsyncIterableIterator<[string, FileSystemHandle]> {
  const iterable = directory as IterableDirectoryHandle;
  if (typeof iterable.entries === "function") return iterable.entries();
  // Older implementations expose no iterator; the caller degrades to `null` (remote fallback).
  return (async function* () {})();
}

async function writeFile(
  directory: FileSystemDirectoryHandle,
  name: string,
  bytes: Uint8Array,
): Promise<void> {
  const fileHandle = await directory.getFileHandle(name, { create: true });
  const writable = await fileHandle.createWritable();
  try {
    await writable.write(bytes as unknown as BufferSource);
  } finally {
    await writable.close();
  }
}

function describeError(cause: unknown, fallback: string): string {
  const message =
    cause instanceof Error ? cause.message : typeof cause === "string" ? cause : "";
  const trimmed = message.trim();
  return trimmed === "" ? fallback : trimmed;
}

/** Process-wide store used by the extension's single reader surface. */
export const downloadsStore = new DownloadsStore();
export { DownloadsStore as DownloadStore };
