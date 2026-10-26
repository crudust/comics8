/**
 * Favorites store: the user's saved series, mirrored into `chrome.storage.local`.
 *
 * The record shape matches the sync wire model (`SyncFavorite` in `../sync/sync-client`), so a
 * favorite can be pushed to `/sync` without a second mapping layer. `sourceId` + `id` is the identity
 * pair: the same toon id can exist in several sources.
 *
 * The state is a Svelte 5 rune array, so components read `favoritesStore.favorites` (or call
 * `isFavorite()`) and re-render when it changes. Storage is the source of truth across reloads;
 * {@link FavoritesStore.init} hydrates from it once at startup.
 */

import { sanitizeCloneable } from "../sources/serialize";
import type { ToonItem } from "../sources/types";

/** `chrome.storage.local` key holding every favorite. */
export const FAVORITES_STORAGE_KEY = "comics8.favorites";

/** One favorite; field names follow the backend's `/sync` payload. */
export type FavoriteRecord = {
  sourceId: string;
  /** `ToonItem.id` inside `sourceId`. */
  id: string;
  title: string;
  thumbUrl: string;
  href: string;
  genre: string;
  updatedAt: string | null;
  /** Epoch milliseconds when the user starred it. */
  savedAt: number;
};

export class FavoritesStore {
  favorites = $state<FavoriteRecord[]>([]);

  #initPromise: Promise<void> | null = null;

  /** Reads `comics8.favorites` once; safe to call from several components. */
  init(): Promise<void> {
    if (!this.#initPromise) {
      this.#initPromise = this.#load();
    }
    return this.#initPromise;
  }

  /** Star/unstar a series. Resolves to `true` when it is a favorite afterwards. */
  async toggleFavorite(item: ToonItem, sourceId: string): Promise<boolean> {
    await this.init();

    const source = normaliseSourceId(sourceId);
    const toonId = String(item?.id ?? "").trim();
    if (!toonId) throw new Error("toggleFavorite requires a ToonItem with an id");

    if (this.isFavorite(source, toonId)) {
      await this.removeFavorite(source, toonId);
      return false;
    }

    const record: FavoriteRecord = {
      sourceId: source,
      id: toonId,
      title: item.title ?? "",
      thumbUrl: item.thumbUrl ?? "",
      href: item.href ?? "",
      genre: item.genre ?? "",
      updatedAt: item.updatedAt ?? null,
      savedAt: Date.now(),
    };

    // Newest first, mirroring the sort `getFavorites()` guarantees.
    this.favorites = [record, ...this.favorites];
    await this.#persist();
    return true;
  }

  /** True when `sourceId` + `toonId` is starred. */
  isFavorite(sourceId: string, toonId: string): boolean {
    const source = normaliseSourceId(sourceId);
    const id = String(toonId ?? "").trim();
    if (!id) return false;
    return this.favorites.some((entry) => entry.sourceId === source && entry.id === id);
  }

  /** Every favorite, most recently starred first. */
  getFavorites(): FavoriteRecord[] {
    return [...this.favorites].sort((a, b) => b.savedAt - a.savedAt);
  }

  /**
   * Applies remote favorites from a sync pull or backup restore.
   * Merges with existing favorites: preserves local favorites and adds any missing remote ones,
   * updating metadata if the remote record is newer.
   */
  async applyRemoteFavorites(
    remote: Array<Partial<FavoriteRecord> & { toonId?: string }>,
  ): Promise<number> {
    await this.init();
    if (!Array.isArray(remote) || remote.length === 0) return 0;

    let changed = 0;
    const existingMap = new Map<string, FavoriteRecord>();
    for (const fav of this.favorites) {
      existingMap.set(`${fav.sourceId}\u0000${fav.id}`, fav);
    }

    for (const item of remote) {
      if (!item || typeof item !== "object") continue;
      const sourceId = normaliseSourceId(item.sourceId ?? "");
      const toonId = String(item.id ?? item.toonId ?? "").trim();
      if (!toonId) continue;

      const key = `${sourceId}\u0000${toonId}`;
      const existing = existingMap.get(key);
      const savedAt =
        typeof item.savedAt === "number" && Number.isFinite(item.savedAt)
          ? item.savedAt
          : Date.now();

      if (!existing) {
        const record: FavoriteRecord = {
          sourceId,
          id: toonId,
          title: item.title ?? "",
          thumbUrl: item.thumbUrl ?? "",
          href: item.href ?? "",
          genre: item.genre ?? "",
          updatedAt: item.updatedAt ?? null,
          savedAt,
        };
        existingMap.set(key, record);
        changed++;
      } else if (savedAt > existing.savedAt) {
        existing.title = item.title || existing.title;
        existing.thumbUrl = item.thumbUrl || existing.thumbUrl;
        existing.href = item.href || existing.href;
        existing.genre = item.genre || existing.genre;
        existing.updatedAt = item.updatedAt !== undefined ? item.updatedAt : existing.updatedAt;
        existing.savedAt = savedAt;
        changed++;
      }
    }

    if (changed > 0) {
      this.favorites = Array.from(existingMap.values()).sort((a, b) => b.savedAt - a.savedAt);
      await this.#persist();
    }
    return changed;
  }

  /** Removes one favorite; a no-op when it was not starred. */
  async removeFavorite(sourceId: string, toonId: string): Promise<void> {
    await this.init();

    const source = normaliseSourceId(sourceId);
    const id = String(toonId ?? "").trim();
    if (!this.isFavorite(source, id)) return;

    this.favorites = this.favorites.filter(
      (entry) => !(entry.sourceId === source && entry.id === id),
    );
    await this.#persist();
  }

  async #load(): Promise<void> {
    const area = chromeStorageArea();
    if (!area) return;
    try {
      const stored = await area.get(FAVORITES_STORAGE_KEY);
      this.favorites = parseFavorites(stored?.[FAVORITES_STORAGE_KEY]);
    } catch {
      // Storage may be unavailable (service-worker startup race, quota); an empty list is usable.
      this.favorites = [];
    }
  }

  async #persist(): Promise<void> {
    const area = chromeStorageArea();
    if (!area) return;
    // `chrome.storage` needs cloneable data; plugin-sourced strings are plain, but be explicit.
    const payload = sanitizeCloneable<FavoriteRecord[]>(this.getFavorites());
    await area.set({ [FAVORITES_STORAGE_KEY]: payload });
  }
}

/** `"local"` is reserved for library files and is rejected by the sync backend. */
function normaliseSourceId(sourceId: string): string {
  const value = String(sourceId ?? "").trim();
  return value === "" ? "local" : value;
}

function chromeStorageArea(): chrome.storage.StorageArea | null {
  if (typeof chrome === "undefined" || !chrome.storage?.local) return null;
  return chrome.storage.local;
}

/** Keeps only well-formed records, so a corrupted/older payload cannot poison the store. */
function parseFavorites(value: unknown): FavoriteRecord[] {
  if (!Array.isArray(value)) return [];
  const out: FavoriteRecord[] = [];
  const seen = new Set<string>();
  for (const entry of value) {
    if (typeof entry !== "object" || entry === null) continue;
    const record = entry as Partial<FavoriteRecord>;
    const id = typeof record.id === "string" ? record.id.trim() : "";
    if (!id) continue;
    const sourceId = normaliseSourceId(typeof record.sourceId === "string" ? record.sourceId : "");
    const key = `${sourceId}\u0000${id}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({
      sourceId,
      id,
      title: typeof record.title === "string" ? record.title : "",
      thumbUrl: typeof record.thumbUrl === "string" ? record.thumbUrl : "",
      href: typeof record.href === "string" ? record.href : "",
      genre: typeof record.genre === "string" ? record.genre : "",
      updatedAt: typeof record.updatedAt === "string" ? record.updatedAt : null,
      savedAt: typeof record.savedAt === "number" && Number.isFinite(record.savedAt) ? record.savedAt : 0,
    });
  }
  return out;
}

/** Process-wide store used by the extension's single reader surface. */
export const favoritesStore = new FavoritesStore();
