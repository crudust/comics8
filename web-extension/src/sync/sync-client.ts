/**
 * Comics8 sync client.
 *
 * Wire contract owner: `server/app/sync.py` (routes) with the field list in
 * `server/app/sync.py::import_snapshot_for_user` / `emit_favorite` / `emit_history` /
 * `emit_read_episode` / `emit_setting`. Auth accepts either `Authorization: Bearer <key>` or
 * `X-Sync-Key: <key>` (`get_sync_user`); this client sends both, as `SyncWire.addComics8SyncHeaders`
 * does on the native clients.
 *
 * Endpoints used here (the API root is whatever `server.json`'s `serverUrl` points at, with the
 * trailing `/sync` stripped):
 * - `POST {root}/pair/request` — `get_existing_sync_user`; mints a 6-digit code (TTL 300s).
 * - `POST {root}/pair/confirm` — body `{ code }`; returns `{ success, syncKey }`.
 * - `GET  {root}/sync`          — full snapshot pull.
 * - `POST {root}/sync`          — snapshot push `{ favorites, history, readEpisodes, readerSettings }`.
 * - `POST {root}/sync/delta`    — incremental push + pull since a timestamp.
 * - `POST {root}/catalog/batch` — counted totals of up to 200 works (public, IP rate-limited).
 *
 * There is no bare `/pair` route: pairing is always `request` (on the device that already has the
 * account) followed by `confirm` (on the device that needs it). {@link SyncClient.pair} chains them
 * for callers that want one call.
 *
 * `/pair/request` requires an *existing* account, which is why {@link SyncClient.requestPairingCode}
 * can push the local snapshot first (`pushBeforePairing`).
 */

import { backgroundFetch, type FetchInit } from "../background-fetch";
import { describeError } from "../messaging";

export const SYNC_STORAGE_KEY = "comics8.sync.state";

/** Mirrors `server/app/config.py` defaults. */
export const PAIR_CODE_TTL_SECONDS = 300;

/** Server-side guard rails (`MAX_SYNC_ITEMS_PER_REQUEST`). */
export const MAX_SYNC_ITEMS_PER_REQUEST = 10_000;

/** `POST /catalog/batch` reads at most this many rows per request (`req.items[:200]`). */
export const MAX_CATALOG_BATCH_ITEMS = 200;

/**
 * Where the sync API lives. Matches `core/src/main/kotlin/sync/SyncConstants.kt::DEFAULT_SERVER_URL`
 * (`server.json`: `https://comics8.lazylabo.com/api/comics8/sync`) — pass `server.json`'s
 * `serverUrl` straight in.
 */
export const DEFAULT_SERVER_URL = "https://comics8.lazylabo.com/api/comics8/sync";

// -------------------------------------------------------------------------------------------------
// wire models (field names are the JSON keys the server reads/writes)
// -------------------------------------------------------------------------------------------------

export type SyncFavorite = {
  id: string;
  sourceId?: string;
  title?: string;
  thumbUrl?: string;
  href?: string;
  genre?: string;
  updatedAt?: string | null;
  savedAt?: number;
};

export type SyncHistory = {
  toonId: string;
  sourceId?: string;
  toonTitle?: string;
  toonThumbUrl?: string;
  toonHref?: string;
  lastWrId?: string;
  lastEpisodeTitle?: string;
  lastEpisodeHref?: string;
  lastReadOrder?: number;
  totalEpisodes?: number;
  lastReadAt?: number;
  nextWrId?: string | null;
  nextEpisodeTitle?: string | null;
  nextEpisodeHref?: string | null;
  hasNew?: boolean;
};

export type SyncReadEpisode = {
  toonId: string;
  wrId: string;
  sourceId?: string;
  readAt?: number;
  lastPage?: number;
};

export type SyncReaderSetting = {
  toonId: string;
  sourceId?: string;
  viewMode?: string;
  readDirection?: string;
  splitMode?: string;
  updatedAt?: number;
};

export type SyncTombstone = {
  entityType: "FAVORITE" | "HISTORY" | "EPISODE" | string;
  entityId: string;
  deletedAt: number;
};

export type SyncCatalogRow = {
  sourceId: string;
  toonId: string;
  totalEpisodes: number;
  updatedAt?: number;
  lastPage?: number;
  pageSize?: number;
  delta?: number;
};

/** The four buckets the task's `/sync` payload owns. */
export type SyncUpdates = {
  favorites: SyncFavorite[];
  history: SyncHistory[];
  readEpisodes: SyncReadEpisode[];
  readerSettings: SyncReaderSetting[];
};

/** Buckets returned by `GET /sync` and `POST /sync/delta`. */
export type SyncRemoteChanges = SyncUpdates & {
  tombstones: SyncTombstone[];
  catalog: SyncCatalogRow[];
};

export type SyncSnapshot = {
  version: number;
  appName: string;
  exportedAt: number;
  isPro: boolean;
};

export type PushResult = {
  status: string;
  savedAt: number;
  userId: string;
  sizeBytes: number;
  favorites: number;
  history: number;
  readEpisodes: number;
  isPro: boolean;
};

export type PullResult = SyncRemoteChanges & {
  version: number;
  exportedAt: number;
  isPro: boolean;
};

export type DeltaResult = {
  serverTime: number;
  userId: string;
  changes: SyncRemoteChanges;
  isPro: boolean;
};

export type PairRequestResult = {
  success: true;
  code: string;
  expiresInSeconds: number;
};

export type PairConfirmResult = {
  syncKey: string;
};

/** Persisted sync state (`chrome.storage.local`). */
export type SyncStatus = {
  syncKey: string;
  serverUrl: string;
  lastSyncedAt: number;
  lastResult: "idle" | "success" | "error";
  lastError: string | null;
  isPro: boolean;
  autoSyncEnabled: boolean;
};

export const EMPTY_STATUS: SyncStatus = {
  syncKey: "",
  serverUrl: DEFAULT_SERVER_URL,
  lastSyncedAt: 0,
  lastResult: "idle",
  lastError: null,
  isPro: false,
  autoSyncEnabled: true,
};

export type SyncClientOptions = {
  /** API root; `server.json`'s `serverUrl` can be passed directly. */
  serverUrl?: string;
  /** Existing sync key; otherwise the persisted/generated one is used. */
  syncKey?: string;
  /** Bypasses `chrome.storage.local` (tests, service-worker startup paths). */
  persist?: boolean;
  /** Request timeout; defaults to 30s (server allows 16 MB bodies). */
  timeoutMs?: number;
  /** Overrides the transport (tests). */
  fetchImpl?: (init: FetchInit) => Promise<{
    ok: boolean;
    status: number;
    headers: Record<string, string>;
    body: string;
    error?: string;
  }>;
};

export class SyncError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(message: string, status: number, code = "SyncError") {
    super(message);
    this.name = "SyncError";
    this.status = status;
    this.code = code;
  }
}

export class SyncClient {
  private status: SyncStatus;
  private readonly persist: boolean;
  private readonly timeoutMs: number;
  private readonly fetchImpl: NonNullable<SyncClientOptions["fetchImpl"]>;
  /** True when the caller pinned a server URL; a stored value must not override it. */
  private readonly serverUrlPinned: boolean;
  private initialized = false;

  constructor(options: SyncClientOptions = {}) {
    this.persist = options.persist ?? true;
    this.timeoutMs = options.timeoutMs ?? 30_000;
    this.fetchImpl = options.fetchImpl ?? ((init) => backgroundFetch(init));
    this.serverUrlPinned = typeof options.serverUrl === "string" && options.serverUrl.trim() !== "";
    this.status = {
      ...EMPTY_STATUS,
      serverUrl: normalizeApiRoot(options.serverUrl ?? EMPTY_STATUS.serverUrl),
      syncKey: options.syncKey?.trim() ?? "",
    };
  }

  /** Loads persisted credentials. Call once before use. */
  async init(): Promise<SyncStatus> {
    if (this.initialized) return this.getStatus();
    this.initialized = true;

    const saved = await this.readPersisted();
    if (saved) {
      this.status = {
        ...this.status,
        ...saved,
        // An explicitly supplied serverUrl/syncKey wins over the stored ones.
        serverUrl: this.serverUrlPinned ? this.status.serverUrl : (saved.serverUrl ?? this.status.serverUrl),
        syncKey: this.status.syncKey || (saved.syncKey ?? ""),
      };
    }
    if (!this.status.syncKey) {
      this.status.syncKey = generateSyncKey();
      await this.persistStatus();
    }
    return this.getStatus();
  }

  getStatus(): SyncStatus {
    return { ...this.status };
  }

  get syncKey(): string {
    return this.status.syncKey;
  }

  get serverUrl(): string {
    return this.status.serverUrl;
  }

  // -----------------------------------------------------------------------------------------------
  // configuration
  // -----------------------------------------------------------------------------------------------

  /** Switches server. `lastSyncedAt` resets so the next delta starts from scratch on the new host. */
  async setServerUrl(url: string): Promise<SyncStatus> {
    const normalized = normalizeApiRoot(url);
    if (!/^https?:\/\//i.test(normalized)) {
      throw new Error("Sync server URL must start with http:// or https://");
    }
    this.status = { ...this.status, serverUrl: normalized, lastSyncedAt: 0 };
    await this.persistStatus();
    return this.getStatus();
  }

  /** Installs a sync key (e.g. after pairing or restoring a master key). */
  async setSyncKey(key: string): Promise<SyncStatus> {
    const trimmed = key.trim();
    if (!trimmed) throw new Error("Sync key must not be empty");
    this.status = { ...this.status, syncKey: trimmed, lastSyncedAt: 0 };
    await this.persistStatus();
    return this.getStatus();
  }

  /** Issues a fresh local key. The server materialises it on the first successful write. */
  async generateNewKey(): Promise<SyncStatus> {
    this.status = { ...this.status, syncKey: generateSyncKey(), lastSyncedAt: 0 };
    await this.persistStatus();
    return this.getStatus();
  }

  async setAutoSyncEnabled(enabled: boolean): Promise<SyncStatus> {
    this.status = { ...this.status, autoSyncEnabled: enabled };
    await this.persistStatus();
    return this.getStatus();
  }

  // -----------------------------------------------------------------------------------------------
  // pairing
  // -----------------------------------------------------------------------------------------------

  /**
   * Requests a 6-digit pairing PIN for the current account.
   *
   * `POST /pair/request` is authenticated with an existing key and requires the account to exist
   * ("Sync account has no stored data" otherwise). Pass `pushBeforePairing: true` (default) to push
   * the local snapshot first, which materialises the account.
   */
  async requestPairingCode(
    options: { pushBeforePairing?: boolean; snapshot?: SyncUpdates } = {},
  ): Promise<PairRequestResult> {
    if (options.pushBeforePairing !== false) {
      try {
        await this.push(options.snapshot ?? emptyUpdates());
      } catch (error) {
        if (error instanceof SyncError && error.status === 401) throw error;
        // A failed pre-push must not block pairing when the account already exists server-side.
      }
    }

    const body = await this.request<{ success?: boolean; code?: string; expiresInSeconds?: number }>(
      "/pair/request",
      { method: "POST", body: "{}", authenticated: true },
    );

    const code = (body.code ?? "").trim();
    if (!code) throw new SyncError("Server did not return a pairing code", 200, "PairingCodeMissing");
    return {
      success: true,
      code,
      expiresInSeconds: body.expiresInSeconds ?? PAIR_CODE_TTL_SECONDS,
    };
  }

  /**
   * Redeems a 6-digit PIN. On success the account's sync key replaces the local one.
   *
   * The PIN is consumed atomically server-side, so a retry after success cannot match again.
   */
  async confirmPairingCode(code: string): Promise<PairConfirmResult> {
    const digits = (code ?? "").replace(/[\s-]/g, "");
    if (!/^\d{6}$/.test(digits)) {
      throw new SyncError("Enter the 6-digit pairing code", 0, "PairingCodeInvalid");
    }

    const body = await this.request<{ success?: boolean; syncKey?: string; message?: string }>(
      "/pair/confirm",
      { method: "POST", body: JSON.stringify({ code: digits }), authenticated: false },
    );

    const syncKey = (body.syncKey ?? "").trim();
    if (!syncKey) {
      throw new SyncError(body.message ?? "Pairing failed", 200, "PairingFailed");
    }

    this.status = { ...this.status, syncKey, lastSyncedAt: 0 };
    await this.persistStatus();
    return { syncKey };
  }

  /**
   * Requests a PIN and immediately redeems it on the same account.
   *
   * This is the "link a second device using my current account" flow, but it only proves the server
   * round trip; the useful direction is {@link requestPairingCode} on the existing device and
   * {@link confirmPairingCode} on the new one. Kept for callers that treat pairing as one step.
   */
  async pair(): Promise<{ code: string; expiresInSeconds: number; syncKey: string }> {
    const requested = await this.requestPairingCode();
    const confirmed = await this.confirmPairingCode(requested.code);
    return {
      code: requested.code,
      expiresInSeconds: requested.expiresInSeconds,
      syncKey: confirmed.syncKey,
    };
  }

  // -----------------------------------------------------------------------------------------------
  // sync
  // -----------------------------------------------------------------------------------------------

  /**
   * `POST /sync` — replaces the server-side snapshot with `updates`.
   *
   * Only the four buckets in {@link SyncUpdates} are sent; the server upserts per row, so omitted
   * rows survive. Use {@link pull} to merge the server state back.
   */
  async push(updates: SyncUpdates): Promise<PushResult> {
    assertBucketSizes(updates);
    const payload = {
      favorites: updates.favorites,
      history: updates.history,
      readEpisodes: updates.readEpisodes,
      readerSettings: updates.readerSettings,
    };

    const body = await this.request<Partial<PushResult> & { status?: string }>("/sync", {
      method: "POST",
      body: JSON.stringify(payload),
      authenticated: true,
    });

    if ((body.status ?? "") !== "success") {
      throw new SyncError("Sync push was not acknowledged", 200, "PushNotAcknowledged");
    }

    const result: PushResult = {
      status: "success",
      savedAt: body.savedAt ?? Date.now(),
      userId: body.userId ?? "",
      sizeBytes: body.sizeBytes ?? 0,
      favorites: body.favorites ?? payload.favorites.length,
      history: body.history ?? payload.history.length,
      readEpisodes: body.readEpisodes ?? payload.readEpisodes.length,
      isPro: body.isPro ?? false,
    };

    this.status = {
      ...this.status,
      lastSyncedAt: result.savedAt,
      lastResult: "success",
      lastError: null,
      isPro: result.isPro,
    };
    await this.persistStatus();
    return result;
  }

  /** `GET /sync` — full snapshot pull (`exportSnapshotForUser`). */
  async pull(): Promise<PullResult> {
    const body = await this.request<Record<string, unknown>>("/sync", {
      method: "GET",
      authenticated: true,
    });

    const result: PullResult = {
      favorites: asArray<SyncFavorite>(body.favorites),
      history: asArray<SyncHistory>(body.history),
      readEpisodes: asArray<SyncReadEpisode>(body.readEpisodes),
      readerSettings: asArray<SyncReaderSetting>(body.readerSettings),
      tombstones: asArray<SyncTombstone>(body.tombstones),
      catalog: asArray<SyncCatalogRow>(body.catalog),
      version: typeof body.version === "number" ? body.version : 2,
      exportedAt: typeof body.exportedAt === "number" ? body.exportedAt : Date.now(),
      isPro: body.isPro === true,
    };

    this.status = {
      ...this.status,
      lastSyncedAt: result.exportedAt,
      lastResult: "success",
      lastError: null,
      isPro: result.isPro,
    };
    await this.persistStatus();
    return result;
  }

  /**
   * `POST /sync/delta` — sends local changes and returns everything changed server-side since
   * `since` (defaults to the last successful sync time).
   */
  async delta(changes: Partial<SyncUpdates> = {}, since?: number): Promise<DeltaResult> {
    const payload = {
      since: since ?? this.status.lastSyncedAt,
      clientTime: Date.now(),
      version: 2,
      changes: {
        favorites: changes.favorites ?? [],
        history: changes.history ?? [],
        readEpisodes: changes.readEpisodes ?? [],
        readerSettings: changes.readerSettings ?? [],
      },
    };

    const body = await this.request<{
      status?: string;
      serverTime?: number;
      userId?: string;
      changes?: Record<string, unknown>;
      isPro?: boolean;
    }>("/sync/delta", {
      method: "POST",
      body: JSON.stringify(payload),
      authenticated: true,
    });

    const raw = body.changes ?? {};
    const result: DeltaResult = {
      serverTime: body.serverTime ?? Date.now(),
      userId: body.userId ?? "",
      isPro: body.isPro === true,
      changes: {
        favorites: asArray<SyncFavorite>(raw.favorites),
        history: asArray<SyncHistory>(raw.history),
        readEpisodes: asArray<SyncReadEpisode>(raw.readEpisodes),
        readerSettings: asArray<SyncReaderSetting>(raw.readerSettings),
        tombstones: asArray<SyncTombstone>(raw.tombstones),
        catalog: asArray<SyncCatalogRow>(raw.catalog),
      },
    };

    this.status = {
      ...this.status,
      lastSyncedAt: result.serverTime,
      lastResult: "success",
      lastError: null,
      isPro: result.isPro,
    };
    await this.persistStatus();
    return result;
  }

  /**
   * Pushes the local snapshot and pulls the merged one back.
   *
   * Push-then-pull is the ordered pair the server expects: `POST /sync` upserts per row (LWW on
   * `savedAt` / `lastReadAt` / `readAt` / `updatedAt`), so pulling afterwards yields the survivors
   * of both sides.
   */
  async syncNow(
    updates: SyncUpdates,
    options: { pullBack?: boolean } = {},
  ): Promise<{ pushed: PushResult; pulled?: PullResult }> {
    const pushed = await this.push(updates);
    if (options.pullBack === false) return { pushed };
    return { pushed, pulled: await this.pull() };
  }

  // -----------------------------------------------------------------------------------------------
  // catalog
  // -----------------------------------------------------------------------------------------------

  /**
   * `POST /catalog/batch` — counted episode totals of up to {@link MAX_CATALOG_BATCH_ITEMS} works.
   *
   * This is `BaseSyncManager.fetchCatalogBatch` and is what lets a browse card print its total
   * ("64화" / "52/64") without opening the series: the server already stores every total another
   * client counted and crowdsourced, so the grid only asks for the rows it is missing.
   *
   * The route is public (IP rate-limited), so the sync key is only sent as a convenience — a client
   * that has never paired can still fill its badges. Server-side this is
   * `get_catalog_batch`, which answers `{ catalog: [{ sourceId, toonId, totalEpisodes, ... }] }` and
   * only emits rows that carry a listing shape. Network failures answer `[]`, exactly like the
   * Kotlin `catch (_: Exception) { emptyList() }`: a missing badge must never fail a page load.
   */
  async fetchCatalogBatch(
    items: readonly { sourceId: string; toonId: string }[],
  ): Promise<SyncCatalogRow[]> {
    const queries = items
      .slice(0, MAX_CATALOG_BATCH_ITEMS)
      .map((item) => ({ sourceId: item.sourceId, toonId: item.toonId }))
      .filter((item) => item.toonId !== "");
    if (queries.length === 0) return [];

    const url = `${normalizeApiRoot(this.status.serverUrl)}/catalog/batch`;
    const headers: Record<string, string> = {
      Accept: "application/json",
      "Content-Type": "application/json; charset=utf-8",
    };
    if (this.status.syncKey) {
      // Same pair of headers as {@link request}; the endpoint does not require them.
      headers["Authorization"] = `Bearer ${this.status.syncKey}`;
      headers["X-Sync-Key"] = this.status.syncKey;
    }

    try {
      const response = await this.fetchImpl({
        url,
        method: "POST",
        headers,
        body: JSON.stringify({ items: queries }),
        timeoutMs: this.timeoutMs,
      });
      if (response.error || !response.ok || !response.body) return [];
      const body = JSON.parse(response.body) as { catalog?: unknown };
      return asArray<SyncCatalogRow>(body.catalog)
        .filter((row) => row && typeof row === "object")
        // A row without a work identity cannot be matched back to a card.
        .map((row) => ({
          ...row,
          sourceId: String(row.sourceId ?? ""),
          toonId: String(row.toonId ?? ""),
        }))
        .filter((row) => row.sourceId !== "" && row.toonId !== "");
    } catch {
      // Unknown server, offline, malformed JSON: the caller keeps its local totals and retries on
      // the next listing load.
      return [];
    }
  }

  // -----------------------------------------------------------------------------------------------
  // transport
  // -----------------------------------------------------------------------------------------------

  private async request<T>(
    path: string,
    options: { method: "GET" | "POST"; body?: string; authenticated: boolean },
  ): Promise<T> {
    const url = `${this.status.serverUrl}${path}`;
    const headers: Record<string, string> = { Accept: "application/json" };
    if (options.method === "POST") headers["Content-Type"] = "application/json; charset=utf-8";
    if (options.authenticated) {
      // Sent exactly like `SyncWire.addComics8SyncHeaders`: both headers, never a query param.
      headers["Authorization"] = `Bearer ${this.status.syncKey}`;
      headers["X-Sync-Key"] = this.status.syncKey;
    }

    let response: Awaited<ReturnType<NonNullable<SyncClientOptions["fetchImpl"]>>>;
    try {
      response = await this.fetchImpl({
        url,
        method: options.method,
        headers,
        body: options.body,
        timeoutMs: this.timeoutMs,
      });
    } catch (error) {
      await this.recordFailure(describeError(error));
      throw new SyncError(`Sync request failed: ${describeError(error)}`, 0, "NetworkError");
    }

    if (response.error) {
      await this.recordFailure(response.error);
      throw new SyncError(`Sync request failed: ${response.error}`, 0, "NetworkError");
    }

    if (!response.ok) {
      const message = extractServerMessage(response.body) ?? `HTTP ${response.status}`;
      await this.recordFailure(message);
      throw new SyncError(message, response.status, errorCodeFor(response.status));
    }

    if (!response.body) return {} as T;
    try {
      return JSON.parse(response.body) as T;
    } catch {
      const message = "Sync server returned malformed JSON";
      await this.recordFailure(message);
      throw new SyncError(message, response.status, "MalformedResponse");
    }
  }

  private async recordFailure(message: string): Promise<void> {
    this.status = { ...this.status, lastResult: "error", lastError: message };
    await this.persistStatus();
  }

  // -----------------------------------------------------------------------------------------------
  // persistence
  // -----------------------------------------------------------------------------------------------

  private async readPersisted(): Promise<Partial<SyncStatus> | null> {
    if (!this.persist) return null;
    const area = chromeStorageArea();
    if (!area) return null;
    try {
      const stored = await area.get(SYNC_STORAGE_KEY);
      return parseStatus(stored?.[SYNC_STORAGE_KEY]);
    } catch {
      return null;
    }
  }

  private async persistStatus(): Promise<void> {
    if (!this.persist) return;
    const area = chromeStorageArea();
    if (!area) return;
    await area.set({ [SYNC_STORAGE_KEY]: this.status });
  }
}

// -------------------------------------------------------------------------------------------------
// helpers
// -------------------------------------------------------------------------------------------------

function chromeStorageArea(): chrome.storage.StorageArea | null {
  if (typeof chrome === "undefined" || !chrome.storage?.local) return null;
  return chrome.storage.local;
}

function parseStatus(value: unknown): Partial<SyncStatus> | null {
  if (typeof value !== "object" || value === null) return null;
  const raw = value as Partial<SyncStatus>;
  const out: Partial<SyncStatus> = {};
  if (typeof raw.syncKey === "string") out.syncKey = raw.syncKey;
  if (typeof raw.serverUrl === "string" && raw.serverUrl.trim()) {
    out.serverUrl = normalizeApiRoot(raw.serverUrl);
  }
  if (typeof raw.lastSyncedAt === "number") out.lastSyncedAt = raw.lastSyncedAt;
  if (raw.lastResult === "idle" || raw.lastResult === "success" || raw.lastResult === "error") {
    out.lastResult = raw.lastResult;
  }
  if (typeof raw.lastError === "string" || raw.lastError === null) out.lastError = raw.lastError;
  if (typeof raw.isPro === "boolean") out.isPro = raw.isPro;
  if (typeof raw.autoSyncEnabled === "boolean") out.autoSyncEnabled = raw.autoSyncEnabled;
  return Object.keys(out).length > 0 ? out : null;
}

/** Strips known endpoint suffixes so `server.json`'s `/api/comics8/sync` works as a root. */
export function normalizeApiRoot(serverUrl: string): string {
  let base = (serverUrl ?? "").trim().replace(/\/+$/, "");
  for (const suffix of ["/sync", "/pair", "/proxy", "/version", "/health", "/pair/request", "/pair/confirm"]) {
    if (base.endsWith(suffix)) {
      base = base.slice(0, -suffix.length);
      break;
    }
  }
  return base.replace(/\/+$/, "");
}

/** Same alphabet and shape as `SyncConstants.generateSyncKey()`: `C8-XXXX-XXXX-XXXX`. */
export function generateSyncKey(): string {
  const alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
  const part = (): string => {
    const bytes = new Uint8Array(4);
    crypto.getRandomValues(bytes);
    let out = "";
    for (const byte of bytes) out += alphabet[byte % alphabet.length];
    return out;
  };
  return `C8-${part()}-${part()}-${part()}`;
}

export function emptyUpdates(): SyncUpdates {
  return { favorites: [], history: [], readEpisodes: [], readerSettings: [] };
}

function assertBucketSizes(updates: SyncUpdates): void {
  const total =
    updates.favorites.length +
    updates.history.length +
    updates.readEpisodes.length +
    updates.readerSettings.length;
  if (total > MAX_SYNC_ITEMS_PER_REQUEST) {
    throw new SyncError(
      `Sync payload has ${total} items; the server accepts at most ${MAX_SYNC_ITEMS_PER_REQUEST}`,
      413,
      "PayloadTooLarge",
    );
  }
}

function asArray<T>(value: unknown): T[] {
  return Array.isArray(value) ? (value as T[]) : [];
}

/** FastAPI puts `detail` either as a string or `{ error, message }`. */
function extractServerMessage(body: string): string | null {
  if (!body) return null;
  try {
    const parsed = JSON.parse(body) as { detail?: unknown; message?: unknown };
    const detail = parsed.detail;
    if (typeof detail === "string") return detail;
    if (detail && typeof detail === "object") {
      const record = detail as { message?: unknown; error?: unknown };
      if (typeof record.message === "string") return record.message;
      if (typeof record.error === "string") return record.error;
    }
    if (typeof parsed.message === "string") return parsed.message;
    return null;
  } catch {
    return body.slice(0, 200);
  }
}

function errorCodeFor(status: number): string {
  if (status === 400) return "BadRequest";
  if (status === 401) return "Unauthorized";
  if (status === 404) return "NotFound";
  if (status === 413) return "PayloadTooLarge";
  if (status === 429) return "RateLimited";
  if (status >= 500) return "ServerError";
  return "HttpError";
}
