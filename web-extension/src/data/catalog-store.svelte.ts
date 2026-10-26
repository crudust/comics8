/**
 * Counted episode totals per work, mirrored into `chrome.storage.local`.
 *
 * This is the web twin of the desktop `toon_catalog` table
 * (`desktop/src/main/kotlin/data/DesktopDatabase.kt`, `upsertCatalog` / `getCatalogTotalsByToonIds`):
 * one row per work, keyed by `sourceId:toonId` (`WorkId.storageKey()`), holding the counted
 * `count` together with the listing *shape* it was counted from (`lastPage`, `pageSize`) and the
 * learned correction `delta`.
 *
 * The shape is the cache validity token — `EpisodeListingTotal.describes()` — so a stored row is
 * only reused while the listing still has the same page count and page size, and the correction is
 * what lets a single tail-page read refresh the count without sweeping every page again:
 *
 *   delta = count − ((lastPage − 1) * pageSize + itemsOnTailAtCount)
 *   total = (lastPage − 1) * pageSize + itemsOnTailNow + delta
 *
 * The row type is `EpisodeListingTotal` (`core/src/main/kotlin/model/EpisodeListingTotal.kt`)
 * plus `updatedAt`, matching the `SyncCatalogRow` wire shape the server already stores.
 *
 * Unlike the history/favorites stores this class deliberately holds **no runes**: the totals are
 * consumed by plain logic (the read model, the sync bridge, plugin hosts) that also runs outside
 * the Svelte compiler, and reactivity is provided where it is actually rendered by
 * `DesktopStore.catalogTotals`, which mirrors this store.
 */

import { sanitizeCloneable } from "../sources/serialize";

/** `chrome.storage.local` key holding every counted total. */
export const CATALOG_STORAGE_KEY = "comics8:catalogs";

/**
 * `com.comics8.core.model.EpisodeListingTotal` + `updatedAt`.
 *
 * `episodePageSize`-sized pages, `lastPage` pages: the count only exists because the listing was
 * counted end to end, never because `lastPage * pageSize` looked plausible.
 */
export type EpisodeListingTotal = {
  count: number;
  lastPage: number;
  pageSize: number;
  /** `count − ((lastPage − 1) * pageSize + tailItems)`; negative when a middle page is short. */
  delta: number;
  /** Epoch millis of the write, like `toon_catalog.updatedAt` / `SyncCatalogRow.updatedAt`. */
  updatedAt?: number;
};

/** `WorkId.storageKey()` — the identity of a catalog row. */
export function catalogKey(sourceId: string, toonId: string): string {
  return `${sourceId}:${toonId}`;
}

/**
 * `EpisodeListingTotal.isValid`: a row without a positive count (or without a shape) is not a
 * count, so it can neither be displayed nor used as a cache token.
 */
export function isValidEpisodeListingTotal(
  total: EpisodeListingTotal | null | undefined,
): total is EpisodeListingTotal {
  return (
    total !== null &&
    total !== undefined &&
    Number.isFinite(total.count) &&
    Number.isFinite(total.lastPage) &&
    Number.isFinite(total.pageSize) &&
    Math.trunc(total.count) > 0 &&
    Math.trunc(total.lastPage) >= 1 &&
    Math.trunc(total.pageSize) >= 1
  );
}

/**
 * `EpisodeListingTotal.describes(lastPage, pageSize)`.
 *
 * A changed page count means episodes were added or removed somewhere; a changed page size
 * invalidates the arithmetic the count was validated against. Either way the row must be dropped
 * and the listing counted again.
 */
export function describesListingShape(
  total: EpisodeListingTotal | null | undefined,
  lastPage: number,
  pageSize: number,
): boolean {
  if (!isValidEpisodeListingTotal(total)) return false;
  return (
    Math.trunc(total.lastPage) === Math.max(1, Math.trunc(lastPage)) &&
    Math.trunc(total.pageSize) === Math.max(1, Math.trunc(pageSize))
  );
}

export class CatalogStore {
  /** In-memory copy, so readers never wait on `chrome.storage.local`. */
  #catalogs: Record<string, EpisodeListingTotal> = {};

  #initPromise: Promise<void> | null = null;
  #writeChain: Promise<void> = Promise.resolve();

  /** Reads the stored rows once; safe to call from several surfaces. */
  init(): Promise<void> {
    if (!this.#initPromise) {
      this.#initPromise = this.#load();
    }
    return this.#initPromise;
  }

  /** The counted total of one work, or `null` when it has never been counted. */
  getCatalog(sourceId: string, toonId: string): EpisodeListingTotal | null {
    return this.#catalogs[catalogKey(sourceId, toonId)] ?? null;
  }

  /**
   * Batched form of {@link getCatalog}, keyed by `sourceId:toonId`.
   *
   * Mirrors `DesktopDatabase.getCatalogTotalsByToonIds`, including its filter: rows that do not
   * carry a valid count + shape are dropped instead of being adopted as a total.
   */
  getCatalogsByToonIds(
    sourceId: string,
    toonIds: readonly string[],
  ): Record<string, EpisodeListingTotal> {
    const out: Record<string, EpisodeListingTotal> = {};
    for (const toonId of toonIds) {
      const key = catalogKey(sourceId, toonId);
      const total = this.#catalogs[key];
      if (isValidEpisodeListingTotal(total)) out[key] = total;
    }
    return out;
  }

  /** Every stored row, keyed by `sourceId:toonId` (used to hydrate the read model). */
  getAll(): Record<string, EpisodeListingTotal> {
    return { ...this.#catalogs };
  }

  /**
   * Persists multiple counted totals (e.g. from sync pull or batch metadata).
   */
  async saveAllCatalog(
    items: Array<{
      sourceId?: string;
      toonId: string;
      count?: number;
      total?: EpisodeListingTotal | { count: number; lastPage?: number; pageSize?: number; delta?: number };
    }>,
  ): Promise<number> {
    await this.init();
    if (!Array.isArray(items) || items.length === 0) return 0;
    let changed = 0;
    const next = { ...this.#catalogs };
    for (const item of items) {
      if (!item || !item.toonId) continue;
      const sourceId = String(item.sourceId || "local").trim();
      const key = catalogKey(sourceId, item.toonId);
      const count =
        typeof item.total?.count === "number"
          ? item.total.count
          : typeof item.count === "number"
            ? item.count
            : 0;
      if (count <= 0) continue;
      const previous = next[key];
      if (!previous || previous.count !== count) {
        next[key] = {
          count,
          lastPage: item.total && "lastPage" in item.total && typeof item.total.lastPage === "number" ? item.total.lastPage : 1,
          pageSize: item.total && "pageSize" in item.total && typeof item.total.pageSize === "number" ? item.total.pageSize : count,
          delta: item.total && "delta" in item.total && typeof item.total.delta === "number" ? item.total.delta : 0,
        };
        changed++;
      }
    }
    if (changed > 0) {
      this.#catalogs = next;
      await this.#persist();
    }
    return changed;
  }

  /**
   * Persists a counted total.
   *
   * `DesktopDatabase.recordCatalogTotal` only accepts rows that are a real count (`isValid`) and
   * skips writes that change nothing; both rules are kept here, so an uncounted observation
   * (`null`) never erases a count that was actually taken.
   */
  async saveCatalog(
    sourceId: string,
    toonId: string,
    total: EpisodeListingTotal,
  ): Promise<void> {
    await this.init();
    const id = String(toonId ?? "").trim();
    if (!id) return;
    const record = normaliseTotal(sourceId, total);
    if (!record) return;

    const key = catalogKey(sourceId, id);
    const previous = this.#catalogs[key];
    if (
      previous &&
      previous.count === record.count &&
      previous.lastPage === record.lastPage &&
      previous.pageSize === record.pageSize &&
      previous.delta === record.delta
    ) {
      return;
    }

    this.#catalogs = { ...this.#catalogs, [key]: record };
    await this.#persist();
  }

  /** Forgets every row (test isolation and "clear cache" surfaces). */
  async clear(): Promise<void> {
    this.#catalogs = {};
    await this.#persist();
  }

  async #load(): Promise<void> {
    const area = chromeStorageArea();
    if (!area) return;
    try {
      const stored = await area.get(CATALOG_STORAGE_KEY);
      this.#catalogs = parseCatalogs(stored?.[CATALOG_STORAGE_KEY]);
    } catch {
      this.#catalogs = {};
    }
  }

  async #persist(): Promise<void> {
    // Two page loads may finish close together; a serialised write cannot land out of order.
    this.#writeChain = this.#writeChain.then(
      () => this.#writeNow(),
      () => this.#writeNow(),
    );
    return this.#writeChain;
  }

  async #writeNow(): Promise<void> {
    const area = chromeStorageArea();
    if (!area) return;
    // `chrome.storage` requires cloneable data; the rows are plain numbers but be explicit.
    await area.set({ [CATALOG_STORAGE_KEY]: sanitizeCloneable<Record<string, EpisodeListingTotal>>(this.#catalogs) });
  }
}

function chromeStorageArea(): chrome.storage.StorageArea | null {
  if (typeof chrome === "undefined" || !chrome.storage?.local) return null;
  return chrome.storage.local;
}

/** Coerces one row into a valid record, or `null` when it is not a count. */
function normaliseTotal(
  sourceId: string,
  total: EpisodeListingTotal,
): EpisodeListingTotal | null {
  if (typeof sourceId !== "string") return null;
  if (!total || typeof total !== "object") return null;
  const record: EpisodeListingTotal = {
    count: Math.trunc(Number(total.count)),
    lastPage: Math.trunc(Number(total.lastPage)),
    pageSize: Math.trunc(Number(total.pageSize)),
    delta: Math.trunc(Number(total.delta ?? 0)),
    updatedAt:
      typeof total.updatedAt === "number" && Number.isFinite(total.updatedAt)
        ? Math.trunc(total.updatedAt)
        : Date.now(),
  };
  return isValidEpisodeListingTotal(record) ? record : null;
}

/** Keeps only well-formed rows, so a corrupted/older payload cannot poison the totals. */
function parseCatalogs(value: unknown): Record<string, EpisodeListingTotal> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return {};
  const out: Record<string, EpisodeListingTotal> = {};
  for (const [key, entry] of Object.entries(value as Record<string, unknown>)) {
    if (typeof entry !== "object" || entry === null) continue;
    const candidate = entry as Partial<EpisodeListingTotal>;
    const [sourceId, ...rest] = key.split(":");
    const toonId = rest.join(":");
    if (!sourceId || !toonId) continue;
    const record = normaliseTotal(sourceId, {
      count: Number(candidate.count),
      lastPage: Number(candidate.lastPage),
      pageSize: Number(candidate.pageSize),
      delta: Number(candidate.delta ?? 0),
      updatedAt:
        typeof candidate.updatedAt === "number" && Number.isFinite(candidate.updatedAt)
          ? candidate.updatedAt
          : undefined,
    });
    if (!record) continue;
    out[catalogKey(sourceId, toonId)] = record;
  }
  return out;
}

/** Process-wide store used by the extension's single shell. */
export const catalogStore = new CatalogStore();
