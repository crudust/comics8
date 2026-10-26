/**
 * `src/data/catalog-store.svelte.ts` — the persisted twin of the desktop `toon_catalog` table.
 *
 * The store owns two rules that the rest of the app depends on, so they are asserted here rather
 * than through the read model:
 *
 * 1. Only a real count is stored. `EpisodeListingTotal.isValid` (`count > 0`, `lastPage >= 1`,
 *    `pageSize >= 1`) is the gate, exactly like `DesktopDatabase.recordCatalogTotal`.
 * 2. The shape the count was taken from is the cache token. `describesListingShape` decides whether
 *    a stored row may refresh itself from a single tail page or has to be recounted.
 */

import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  CATALOG_STORAGE_KEY,
  CatalogStore,
  catalogKey,
  describesListingShape,
  isValidEpisodeListingTotal,
  type EpisodeListingTotal,
} from "../src/data/catalog-store.svelte";

/** In-memory `chrome.storage.local`, shaped like the API the store uses. */
function stubStorage(initial: Record<string, unknown> = {}): Record<string, unknown> {
  const stored: Record<string, unknown> = { ...initial };
  (globalThis as unknown as { chrome: unknown }).chrome = {
    storage: {
      local: {
        get: async (key: string) => ({ [key]: stored[key] }),
        set: async (values: Record<string, unknown>) => {
          Object.assign(stored, values);
        },
      },
    },
  };
  return stored;
}

const row = (overrides: Partial<EpisodeListingTotal> = {}): EpisodeListingTotal => ({
  count: 64,
  lastPage: 1,
  pageSize: 100,
  delta: 0,
  ...overrides,
});

afterEach(() => {
  delete (globalThis as unknown as { chrome?: unknown }).chrome;
});

describe("catalog rows (EpisodeListingTotal)", () => {
  it("accepts only a real count", () => {
    expect(isValidEpisodeListingTotal(row())).toBe(true);
    expect(isValidEpisodeListingTotal(row({ count: 0 }))).toBe(false);
    expect(isValidEpisodeListingTotal(row({ lastPage: 0 }))).toBe(false);
    expect(isValidEpisodeListingTotal(row({ pageSize: 0 }))).toBe(false);
    expect(isValidEpisodeListingTotal(null)).toBe(false);
    expect(isValidEpisodeListingTotal(undefined)).toBe(false);
  });

  it("treats the listing shape as the cache token", () => {
    const counted = row({ count: 23, lastPage: 3, pageSize: 10, delta: -2 });
    expect(describesListingShape(counted, 3, 10)).toBe(true);
    // A changed page count means episodes were added/removed: the correction is stale.
    expect(describesListingShape(counted, 4, 10)).toBe(false);
    // A changed page size invalidates the arithmetic the count was validated against.
    expect(describesListingShape(counted, 3, 20)).toBe(false);
    expect(describesListingShape(row({ count: 0 }), 1, 100)).toBe(false);
  });

  it("keys rows by `sourceId:toonId` without losing colons in the toon id", async () => {
    stubStorage();
    const store = new CatalogStore();
    await store.init();
    await store.saveCatalog("src", "a:b", row({ count: 7 }));
    expect(catalogKey("src", "a:b")).toBe("src:a:b");
    expect(store.getCatalog("src", "a:b")?.count).toBe(7);
    expect(store.getCatalog("src", "a")?.count).toBeUndefined();
  });
});

describe("CatalogStore persistence", () => {
  it("does nothing without `chrome.storage.local` (in-memory only)", async () => {
    const store = new CatalogStore();
    await store.init();
    await store.saveCatalog("src", "42", row());
    // No storage area: the row is still readable in memory, and nothing throws.
    expect(store.getCatalog("src", "42")).toEqual(row({ updatedAt: expect.any(Number) }));
  });

  it("writes rows to `comics8:catalogs` and reads them back", async () => {
    const storage = stubStorage();
    const store = new CatalogStore();
    await store.init();
    await store.saveCatalog("src", "42", row());

    const persisted = storage[CATALOG_STORAGE_KEY] as Record<string, EpisodeListingTotal>;
    expect(persisted["src:42"]).toMatchObject({ count: 64, lastPage: 1, pageSize: 100, delta: 0 });
    expect(typeof persisted["src:42"]!.updatedAt).toBe("number");

    // A second instance (a new session) hydrates from storage.
    const reopened = new CatalogStore();
    await reopened.init();
    expect(reopened.getCatalog("src", "42")).toMatchObject({ count: 64, lastPage: 1, pageSize: 100 });
  });

  it("skips a write when the row is unchanged, so the timestamp is stable", async () => {
    stubStorage();
    const store = new CatalogStore();
    await store.init();
    await store.saveCatalog("src", "42", row());
    const first = store.getCatalog("src", "42");
    await store.saveCatalog("src", "42", row());
    // `recordCatalogTotal` returns early when `getCatalog(workId)?.total() == total`.
    expect(store.getCatalog("src", "42")).toBe(first);
  });

  it("refuses a row that is not a count, and never erases a real one", async () => {
    stubStorage();
    const store = new CatalogStore();
    await store.init();
    await store.saveCatalog("src", "42", row());
    await store.saveCatalog("src", "42", row({ count: 0, lastPage: 0, pageSize: 0 }));
    expect(store.getCatalog("src", "42")?.count).toBe(64);
  });

  it("answers a batch lookup with only the valid rows", async () => {
    stubStorage();
    const store = new CatalogStore();
    await store.init();
    await store.saveCatalog("src", "a", row({ count: 10 }));
    await store.saveCatalog("src", "b", row({ count: 0 }));

    const batch = store.getCatalogsByToonIds("src", ["a", "b", "c"]);
    expect(Object.keys(batch)).toEqual(["src:a"]);
    expect(batch["src:a"]?.count).toBe(10);
    // The invalid row never entered the store at all: `recordCatalogTotal` rejects it up front.
    expect(Object.keys(store.getAll())).toEqual(["src:a"]);
  });

  it("drops corrupted or shape-less rows while loading", async () => {
    stubStorage({
      [CATALOG_STORAGE_KEY]: {
        "src:ok": { count: 12, lastPage: 1, pageSize: 50, delta: 0, updatedAt: 5 },
        // The pre-catalog clients stored an unverified guess without a shape.
        "src:guessed": { count: 200, lastPage: 0, pageSize: 0, delta: 0 },
        "src:junk": { count: "abc", lastPage: 1, pageSize: 10, delta: 0 },
        ":missing-source": { count: 3, lastPage: 1, pageSize: 10, delta: 0 },
        "src:": { count: 3, lastPage: 1, pageSize: 10, delta: 0 },
        notARow: 7,
      },
    });
    const store = new CatalogStore();
    await store.init();

    expect(store.getAll()).toEqual({
      "src:ok": { count: 12, lastPage: 1, pageSize: 50, delta: 0, updatedAt: 5 },
    });
  });

  it("survives an unreadable storage payload", async () => {
    (globalThis as unknown as { chrome: unknown }).chrome = {
      storage: {
        local: {
          get: async () => {
            throw new Error("quota");
          },
          set: async () => {},
        },
      },
    };
    const store = new CatalogStore();
    await store.init();
    expect(store.getCatalog("src", "42")).toBeNull();
    expect(store.getAll()).toEqual({});
  });

  it("serialises concurrent writes so the last one is never lost", async () => {
    const storage = stubStorage();
    const store = new CatalogStore();
    await store.init();
    await Promise.all([
      store.saveCatalog("src", "a", row({ count: 1 })),
      store.saveCatalog("src", "b", row({ count: 2 })),
      store.saveCatalog("src", "c", row({ count: 3 })),
    ]);
    const persisted = storage[CATALOG_STORAGE_KEY] as Record<string, EpisodeListingTotal>;
    expect(persisted["src:a"]?.count).toBe(1);
    expect(persisted["src:b"]?.count).toBe(2);
    expect(persisted["src:c"]?.count).toBe(3);
  });

  it("clears every row", async () => {
    const storage = stubStorage();
    const store = new CatalogStore();
    await store.init();
    await store.saveCatalog("src", "a", row());
    await store.clear();
    expect(store.getAll()).toEqual({});
    expect(storage[CATALOG_STORAGE_KEY]).toEqual({});
  });

  it("normalises the numbers it stores", async () => {
    stubStorage();
    const store = new CatalogStore();
    await store.init();
    await store.saveCatalog("src", "42", {
      count: 12.9,
      lastPage: 2.7,
      pageSize: 5.2,
      delta: -1.8,
    });
    expect(store.getCatalog("src", "42")).toMatchObject({
      count: 12,
      lastPage: 2,
      pageSize: 5,
      delta: -1,
    });
  });
});
