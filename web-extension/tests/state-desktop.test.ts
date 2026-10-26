/**
 * Ported desktop rules behind `src/state/desktop-state.svelte.ts`.
 *
 * The store mirrors `DesktopUiState.kt` / `DesktopViewModel.kt`, whose navigation and ordering
 * decisions are delegated to `core/src/main/kotlin/model/ReaderDomain.kt` and `EpisodeSortOrder.kt`.
 * Those are pure functions, so they are asserted here against the Kotlin cases instead of being
 * re-derived in a component.
 */

import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  afterSourceChange,
  browseTabsForSource,
  clampPage,
  countEpisodeListing,
  createDesktopStore,
  decodeReaderPage,
  defaultProgressDisplayMode,
  episodePosition,
  formatProgressBadge,
  isAscendingOrder,
  nextEpisodeNavigation,
  normalisePageInfo,
  parseUpdateScore,
  prevEpisodeNavigation,
  sortEpisodes,
  startPageOnOpen,
  toCatalogTotal,
  toonItemFromHistory,
  totalFromPage,
  workKey,
  type CatalogTotal,
  type EpisodeSortOrder,
} from "../src/state/desktop-state.svelte";
import { catalogStore, type EpisodeListingTotal } from "../src/data/catalog-store.svelte";
import { favoritesStore } from "../src/data/favorites-store.svelte";
import { historyStore } from "../src/data/history-store.svelte";
import type { SourceRegistry } from "../src/sources/registry";
import type { SyncClient } from "../src/sync/sync-client";
import type { EpisodeItem, ToonItem } from "../src/sources/types";

const episodes = (titles: string[]): EpisodeItem[] =>
  titles.map((title, index) => ({ wrId: `e${index}`, title, href: `https://src.test/e${index}` }));

describe("episode navigation (ReaderDomain.nextEpisode / prevEpisode)", () => {
  it("walks newest-first lists towards older chapters", () => {
    // Index 0 is the newest chapter, so "next" moves towards the older end (index + 1 → index 1).
    expect(nextEpisodeNavigation(2, 3, 1, "DEFAULT")).toEqual({
      kind: "in-current-page",
      index: 1,
    });
    // Already on the newest chapter: nothing newer to open on this page.
    expect(nextEpisodeNavigation(0, 3, 1, "DEFAULT")).toEqual({ kind: "none" });
    expect(prevEpisodeNavigation(0, 3, 1, 1, "DEFAULT")).toEqual({
      kind: "in-current-page",
      index: 1,
    });
    // Already on the oldest chapter of a single-page listing: nothing older to open.
    expect(prevEpisodeNavigation(2, 3, 1, 1, "DEFAULT")).toEqual({ kind: "none" });
  });

  it("walks ascending lists towards newer chapters", () => {
    expect(isAscendingOrder("NAME_ASC")).toBe(true);
    expect(isAscendingOrder("DATE_ASC")).toBe(true);
    expect(isAscendingOrder("DEFAULT")).toBe(false);
    expect(nextEpisodeNavigation(1, 3, 1, "NAME_ASC")).toEqual({
      kind: "in-current-page",
      index: 2,
    });
    expect(nextEpisodeNavigation(2, 3, 1, "NAME_ASC")).toEqual({ kind: "none" });
    expect(prevEpisodeNavigation(0, 3, 1, 1, "DATE_ASC")).toEqual({ kind: "none" });
  });

  it("loads the neighbouring episode page at a listing edge", () => {
    // Newest-first, first item of page 2: "next" needs the tail of page 1.
    expect(nextEpisodeNavigation(0, 30, 2, "DEFAULT")).toEqual({
      kind: "load-page",
      page: 1,
      edge: "LAST",
    });
    // Newest-first, last item of page 2: "prev" needs the head of page 3.
    expect(prevEpisodeNavigation(29, 30, 2, 3, "DEFAULT")).toEqual({
      kind: "load-page",
      page: 3,
      edge: "FIRST",
    });
  });

  it("ignores an unknown current episode", () => {
    expect(nextEpisodeNavigation(-1, 3, 1, "DEFAULT")).toEqual({ kind: "none" });
    expect(prevEpisodeNavigation(-1, 3, 1, 1, "DEFAULT")).toEqual({ kind: "none" });
    expect(nextEpisodeNavigation(0, 0, 1, "DEFAULT")).toEqual({ kind: "none" });
  });
});

describe("episode sorting (EpisodeSortOrder.sortedWithOrder)", () => {
  const list = episodes(["10화", "2화", "1화"]);

  it("keeps source order for DEFAULT and never mutates its input", () => {
    const sorted = sortEpisodes(list, "DEFAULT");
    expect(sorted.map((episode) => episode.title)).toEqual(["10화", "2화", "1화"]);
    expect(sorted).not.toBe(list);
  });

  it("sorts naturally by name, ignoring zero padding and case", () => {
    const sorted = sortEpisodes(episodes(["10화", "2화", "01화", "1화"]), "NAME_ASC");
    // Kotlin ties equal digit values by raw length, so "1" < "01" < "001".
    expect(sorted.map((episode) => episode.title)).toEqual(["1화", "01화", "2화", "10화"]);
    const descending = sortEpisodes(episodes(["Chapter 2", "chapter 10"]), "NAME_DESC");
    expect(descending.map((episode) => episode.title)).toEqual(["chapter 10", "Chapter 2"]);
  });

  it("orders by date with undated chapters last when ascending", () => {
    const dated: EpisodeItem[] = [
      { wrId: "a", title: "a", href: "", date: "2026.10.03" },
      { wrId: "b", title: "b", href: "", date: "2026.10.01" },
      { wrId: "c", title: "c", href: "" },
    ];
    expect(sortEpisodes(dated, "DATE_DESC").map((episode) => episode.wrId)).toEqual([
      "a",
      "b",
      "c",
    ]);
    expect(sortEpisodes(dated, "DATE_ASC").map((episode) => episode.wrId)).toEqual([
      "b",
      "a",
      "c",
    ]);
  });

  it("accepts every declared order", () => {
    const orders: EpisodeSortOrder[] = ["DEFAULT", "NAME_ASC", "NAME_DESC", "DATE_DESC", "DATE_ASC"];
    for (const order of orders) {
      expect(sortEpisodes(list, order)).toHaveLength(list.length);
    }
  });
});

describe("date scores (UpdateDates.parseScore)", () => {
  const today = new Date(2026, 9, 19); // 2026-10-19

  it("reads YY.MM.DD and MM.DD, inferring the year for the short form", () => {
    expect(parseUpdateScore("26.10.03", today)).toBe(261003);
    // 10.03 is in the past of 2026-10-19, so it belongs to the current year.
    expect(parseUpdateScore("10.03", today)).toBe(261003);
    // 12.31 is still ahead, so the short form refers to last year.
    expect(parseUpdateScore("12.31", today)).toBe(251231);
  });

  it("returns 0 for blank or unparsable input", () => {
    expect(parseUpdateScore(null, today)).toBe(0);
    expect(parseUpdateScore("  ", today)).toBe(0);
    expect(parseUpdateScore("어제", today)).toBe(0);
  });
});

describe("pagination and reader page encoding", () => {
  it("clamps pages into 1..lastPage", () => {
    expect(clampPage(0, 5)).toBe(1);
    expect(clampPage(9, 5)).toBe(5);
    expect(clampPage(3, 0)).toBe(1);
  });

  it("decodes the completion sign the desktop persists", () => {
    expect(decodeReaderPage(-1)).toBe(0); // completed page 0
    expect(decodeReaderPage(-4)).toBe(3);
    expect(decodeReaderPage(2)).toBe(2);
    expect(startPageOnOpen(-4)).toBe(0);
    expect(startPageOnOpen(3)).toBe(3);
  });
});

describe("browse tabs (BrowseTab.forSource / afterSourceChange)", () => {
  const source = {
    id: "src",
    catalogs: [
      { id: "LATEST", label: "최신", paginated: true },
      { id: "POPULAR", label: "인기", paginated: false },
    ],
  };

  it("places the favorite tab right after LATEST", () => {
    expect(browseTabsForSource(source).map((tab) => tab.catalogId)).toEqual([
      "LATEST",
      "FAVORITE",
      "POPULAR",
    ]);
    expect(browseTabsForSource(source).map((tab) => tab.id)).toEqual([
      "src:LATEST",
      "src:FAVORITE",
      "src:POPULAR",
    ]);
  });

  it("appends the favorite tab when the source has no LATEST catalog", () => {
    const tabs = browseTabsForSource({
      id: "src",
      catalogs: [{ id: "ALL", label: "전체", paginated: false }],
    });
    expect(tabs.map((tab) => tab.catalogId)).toEqual(["ALL", "FAVORITE"]);
  });

  it("keeps the current catalog across a source change when it still exists", () => {
    const previous = browseTabsForSource(source).find((tab) => tab.catalogId === "POPULAR");
    const next = browseTabsForSource({ id: "other", catalogs: source.catalogs });
    expect(afterSourceChange(previous ?? null, next)?.catalogId).toBe("POPULAR");
    expect(afterSourceChange(previous ?? null, browseTabsForSource({ id: "x", catalogs: [] })))
      .toMatchObject({ catalogId: "FAVORITE" });
    expect(afterSourceChange(null, [])).toBeNull();
  });
});

describe("history rows map back to series cards", () => {
  it("keeps identity and display fields", () => {
    const toon = toonItemFromHistory({
      sourceId: "src",
      toonId: "42",
      title: "테스트 작품",
      thumbUrl: "https://src.test/42.jpg",
      lastWrId: "e3",
      lastEpisodeTitle: "3화",
      pageIndex: 4,
      totalPages: 20,
      updatedAt: 1,
    });
    expect(toon).toMatchObject({
      id: "42",
      title: "테스트 작품",
      thumbUrl: "https://src.test/42.jpg",
      sourceId: "src",
    });
    expect(workKey("src", "42")).toBe("src:42");
  });
});

// ---------------------------------------------------------------------------------------------
// store behaviour (the actions, not the pure rules)
// ---------------------------------------------------------------------------------------------

describe("DesktopStore wiring", () => {
  // `catalogStore` is process-wide (like `historyStore`/`favoritesStore`), so every case starts from
  // a clean slate: otherwise a total counted by an earlier case would be reused as a stored shape.
  beforeEach(async () => {
    await catalogStore.clear();
    await historyStore.init();
    historyStore.history = [];
    historyStore.readEpisodes = [];
  });

  const item: ToonItem = {
    id: "42",
    title: "테스트 작품",
    thumbUrl: "https://src.test/42.jpg",
    href: "https://src.test/series/42",
  };
  const episodeList: EpisodeItem[] = [
    { wrId: "e3", title: "3화", href: "https://src.test/e3", date: "2026.10.03" },
    { wrId: "e2", title: "2화", href: "https://src.test/e2", date: "2026.10.02" },
    { wrId: "e1", title: "1화", href: "https://src.test/e1", date: "2026.10.01" },
  ];

  const calls = { listing: [] as Array<[string, number]>, episodes: [] as number[], images: 0 };

  /** Minimal stand-in for `AnyLoadedSource`; only the entry points the store calls. */
  function fakeSource(overrides: Record<string, unknown> = {}) {
    return {
      id: "src",
      displayName: "Test Source",
      meta: { origin: "https://src.test", episodePageSize: 100, searchPlaceholder: "검색" },
      catalogs: [
        { id: "LATEST", label: "최신", paginated: true },
        { id: "POPULAR", label: "인기", paginated: false },
      ],
      async loadListing(catalogId: string, page: number) {
        calls.listing.push([catalogId, page]);
        return { items: [item], pageInfo: { currentPage: page, lastPage: 2 } };
      },
      async loadEpisodes(_item: ToonItem, page: number) {
        calls.episodes.push(page);
        return { items: episodeList, pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async resolveImages() {
        calls.images += 1;
        return ["https://img.test/1.jpg", "https://img.test/2.jpg"];
      },
      async search() {
        return [item];
      },
      ...overrides,
    };
  }

  function fakeRegistry(source: unknown) {
    return {
      init: async () => {},
      list: () => [{ id: "src", displayName: "Test Source", origin: "", catalogs: [], builtin: true }],
      get: async () => source,
    } as unknown as SourceRegistry;
  }

  const reset = () => {
    calls.listing.length = 0;
    calls.episodes.length = 0;
    calls.images = 0;
  };

  it("loads a source, its tabs and the first listing, and remembers the active tab", async () => {
    reset();
    const store = createDesktopStore({
      registry: fakeRegistry(fakeSource()),
      persistPreferences: false,
    });
    await store.init();

    expect(store.screen).toBe("Browse");
    expect(store.activeSourceId).toBe("src");
    expect(store.browseTabs.map((tab) => tab.catalogId)).toEqual([
      "LATEST",
      "FAVORITE",
      "POPULAR",
    ]);
    expect(store.activeTab?.catalogId).toBe("LATEST");
    expect(calls.listing).toEqual([["LATEST", 1]]);
    expect(store.items.map((row) => row.id)).toEqual(["42"]);
    expect(store.items[0]?.sourceId).toBe("src");
    expect(store.page).toBe(1);
    expect(store.lastPage).toBe(2);
    expect(store.loading).toBe(false);
  });

  it("opens a series, sorts episodes, favorites it and opens a chapter", async () => {
    reset();
    const store = createDesktopStore({
      registry: fakeRegistry(fakeSource()),
      persistPreferences: false,
    });
    await store.init();
    await store.selectTab("src:POPULAR");
    expect(store.activeTab?.catalogId).toBe("POPULAR");

    await store.openSeries(store.items[0]!);
    expect(store.screen).toBe("Series");
    expect(store.series?.id).toBe("42");
    expect(store.episodes.map((episode) => episode.wrId)).toEqual(["e3", "e2", "e1"]);
    // One-page listing: the page itself is the counted total (no estimate).
    expect(store.catalogTotals["src:42"]).toEqual({
      count: 3,
      lastPage: 1,
      pageSize: 100,
      delta: 0,
    });

    store.setEpisodeSortOrder("NAME_ASC");
    expect(store.episodes.map((episode) => episode.title)).toEqual(["1화", "2화", "3화"]);

    await store.toggleFavorite();
    expect(store.seriesFavorited).toBe(true);

    // NAME_ASC makes index 0 the *oldest* chapter, so nextEpisode moves to index 1 ("2화").
    await store.openEpisode(store.episodes[0]!);
    expect(store.screen).toBe("Reader");
    expect(store.currentEpisodeIndex).toBe(0);
    expect(store.readerImages).toHaveLength(2);
    expect(store.readerLoading).toBe(false);
    expect(store.hasNextEpisode).toBe(true);
    expect(store.hasPrevEpisode).toBe(false);

    await store.nextEpisode();
    expect(store.currentEpisode?.wrId).toBe("e2");
    await store.prevEpisode();
    expect(store.currentEpisode?.wrId).toBe("e1");

    store.closeReader();
    expect(store.screen).toBe("Series");
    expect(store.currentEpisode).toBeNull();
  });

  it("searches, then restores the selected catalog on clearSearch", async () => {
    reset();
    const store = createDesktopStore({
      registry: fakeRegistry(fakeSource()),
      persistPreferences: false,
    });
    await store.init();
    await store.selectTab("src:POPULAR");
    calls.listing.length = 0;

    store.toggleSearchBar();
    expect(store.searchExpanded).toBe(true);
    await store.search("마법");
    expect(store.searchQuery).toBe("마법");
    expect(store.searchInput).toBe("마법");
    expect(store.isSearch).toBe(true);
    expect(store.items.map((row) => row.id)).toEqual(["42"]);

    await store.clearSearch();
    expect(store.searchQuery).toBeNull();
    expect(store.isSearch).toBe(false);
    expect(store.activeTab?.catalogId).toBe("POPULAR");
    // The previously loaded catalog page is reused from the cache (desktop `catalogCache`), so the
    // grid is restored without another plugin round trip.
    expect(calls.listing).toEqual([]);
    expect(store.items.map((row) => row.id)).toEqual(["42"]);
  });

  it("resumes a series from history and returns to the History screen", async () => {
    reset();
    const store = createDesktopStore({
      registry: fakeRegistry(fakeSource()),
      persistPreferences: false,
    });
    await store.init();
    await store.openHistory();
    expect(store.screen).toBe("History");
    // Only the active source's rows are listed (the history store itself is process-wide).
    expect(store.historyItems.every((row) => row.sourceId === "src")).toBe(true);

    await store.openSeriesFromHistory({
      sourceId: "src",
      toonId: "42",
      title: "테스트 작품",
      thumbUrl: "",
      lastWrId: "e2",
      lastEpisodeTitle: "2화",
      pageIndex: 3,
      totalPages: 10,
      updatedAt: Date.now(),
    });
    expect(store.screen).toBe("Series");
    expect(store.series).toMatchObject({ id: "42", title: "테스트 작품", sourceId: "src" });

    await store.closeSeries();
    expect(store.screen).toBe("History");
  });

  it("keeps viewer settings per series in memory when preferences are not persisted", async () => {
    reset();
    const store = createDesktopStore({
      registry: fakeRegistry(fakeSource()),
      persistPreferences: false,
    });
    await store.init();
    await store.openSeries(store.items[0]!);

    store.setViewMode("SCROLL");
    store.setReadDirection("LEFT_TO_RIGHT");
    store.setSplitMode("SLICE");
    expect([store.viewMode, store.readDirection, store.splitMode]).toEqual([
      "SCROLL",
      "LEFT_TO_RIGHT",
      "SLICE",
    ]);

    // Every setter also rewrites the per-series override (`saveReaderSetting`), so re-opening the
    // chapter restores the latest values rather than the first ones.
    store.setViewMode("PAGE");
    await store.openEpisode(store.episodes[0]!);
    expect(store.viewMode).toBe("PAGE");
    expect(store.readDirection).toBe("LEFT_TO_RIGHT");
    expect(store.splitMode).toBe("SLICE");

    // A different series has no override yet, so the mode in effect is kept when it is opened.
    store.viewMode = "DUAL";
    await store.openSeries({ ...item, id: "99" });
    await store.openEpisode(store.episodes[0]!);
    expect(store.viewMode).toBe("DUAL");
    store.destroy();
  });

  it("reports a listing failure without wiping the page already shown", async () => {
    reset();
    let fail = false;
    const store = createDesktopStore({
      registry: fakeRegistry(
        fakeSource({
          async loadListing(catalogId: string, page: number) {
            calls.listing.push([catalogId, page]);
            if (fail) throw new Error("boom");
            return { items: [item], pageInfo: { currentPage: page, lastPage: 2 } };
          },
        }),
      ),
      persistPreferences: false,
    });
    await store.init();
    expect(store.items).toHaveLength(1);

    fail = true;
    await store.refresh();
    expect(store.loading).toBe(false);
    expect(store.refreshing).toBe(false);
    expect(store.error).toBe("boom");
    // A failed refresh keeps the cards on screen instead of blanking the grid.
    expect(store.items).toHaveLength(1);
  });

  it("serves the favorite tab from the favorites store, not from the plugin", async () => {
    reset();
    const store = createDesktopStore({
      registry: fakeRegistry(fakeSource()),
      persistPreferences: false,
    });
    await store.init();
    // The favorites store is process-wide, so start from a known state.
    await favoritesStore.init();
    await favoritesStore.removeFavorite("src", "42");

    await store.toggleFavorite(store.items[0]!);
    await store.selectTab("src:FAVORITE");
    expect(store.items.map((row) => row.id)).toEqual(["42"]);

    // Un-starring from inside the favorite tab drops the row from the visible grid.
    await store.toggleFavorite(store.items[0]!);
    expect(store.items).toEqual([]);
  });

  it("counts a paginated listing page by page and learns the short-page delta", async () => {
    reset();
    // 3 pages x pageSize 5, but the middle page only has 3 items: naive arithmetic would claim 13.
    const pageItems: Record<number, EpisodeItem[]> = {
      1: Array.from({ length: 5 }, (_, i) => ({ wrId: `a${i}`, title: `a${i}`, href: "" })),
      2: Array.from({ length: 3 }, (_, i) => ({ wrId: `b${i}`, title: `b${i}`, href: "" })),
      3: Array.from({ length: 5 }, (_, i) => ({ wrId: `c${i}`, title: `c${i}`, href: "" })),
    };
    const store = createDesktopStore({
      registry: fakeRegistry(
        fakeSource({
          meta: { origin: "https://src.test", episodePageSize: 5, searchPlaceholder: "검색" },
          async loadEpisodes(_item: ToonItem, page: number) {
            calls.episodes.push(page);
            return { items: pageItems[page] ?? [], pageInfo: { currentPage: page, lastPage: 3 } };
          },
        }),
      ),
      persistPreferences: false,
    });
    await store.init();
    await store.openSeries(store.items[0]!);

    const total = store.catalogTotals["src:42"];
    expect(total?.count).toBe(13);
    expect(total?.lastPage).toBe(3);
    expect(total?.pageSize).toBe(5);
    // naive = (3 - 1) * 5 + 5 = 15, counted = 13, so the learned correction is -2.
    expect(total?.delta).toBe(-2);
    // The loaded page is reused during the sweep instead of being fetched twice.
    expect(calls.episodes.filter((page) => page === 1)).toHaveLength(1);
  });

  it("leaves a work uncounted when a sweep page cannot be read", async () => {
    reset();
    const store = createDesktopStore({
      registry: fakeRegistry(
        fakeSource({
          meta: { origin: "https://src.test", episodePageSize: 2, searchPlaceholder: "검색" },
          async loadEpisodes(_item: ToonItem, page: number) {
            if (page === 2) throw new Error("page 2 down");
            return {
              items: [
                { wrId: `p${page}a`, title: `${page}a`, href: "" },
                { wrId: `p${page}b`, title: `${page}b`, href: "" },
              ],
              pageInfo: { currentPage: page, lastPage: 3 },
            };
          },
        }),
      ),
      persistPreferences: false,
    });
    await store.init();
    await store.openSeries(store.items[0]!);

    // A partial sweep must never become a number in `catalogTotals`; the estimate is what the
    // header can still show, and it is ranked below any real count.
    expect(store.catalogTotals["src:42"]).toBeUndefined();
    expect(store.episodes).toHaveLength(2);
    expect(store.episodeError).toBeNull();
    expect(store.totalEpisodesOf("src:42")).toBe(6);
  });

  it("stores a total the plugin reported and lets it outrank the page sweep", async () => {
    reset();
    const store = createDesktopStore({
      registry: fakeRegistry(
        fakeSource({
          async loadEpisodes(_item: ToonItem, page: number) {
            calls.episodes.push(page);
            return {
              items: episodeList.map((episode) => ({ ...episode, wrId: `${episode.wrId}-p${page}` })),
              // `itemCount` inside `pageInfo` is one of the documented loose spellings.
              pageInfo: { currentPage: page, lastPage: 2, itemCount: 250 },
            };
          },
        }),
      ),
      persistPreferences: false,
    });
    await store.init();
    await store.openSeries(store.items[0]!);

    // `reportedEpisodeTotal` reads the number; the counted sweep (6 items) must not overwrite it.
    expect(store.totalEpisodesByWork["src:42"]).toBe(250);
    expect(store.totalEpisodesOf("src:42")).toBe(250);
  });

  it("estimates lastPage * pageSize when nothing reports a total, then corrects it with a sweep", async () => {
    reset();
    let failSweep = true;
    const store = createDesktopStore({
      registry: fakeRegistry(
        fakeSource({
          meta: { origin: "https://src.test", episodePageSize: 5, searchPlaceholder: "검색" },
          async loadEpisodes(_item: ToonItem, page: number) {
            calls.episodes.push(page);
            if (page !== 1 && failSweep) throw new Error("sweep down");
            return {
              items: episodeList.map((episode) => ({ ...episode, wrId: `${episode.wrId}-p${page}` })),
              pageInfo: { currentPage: page, lastPage: 3 },
            };
          },
        }),
      ),
      persistPreferences: false,
    });
    await store.init();
    await store.openSeries(store.items[0]!);

    // No reported total and an unreadable sweep: the page shape is the only evidence left.
    expect(store.catalogTotals["src:42"]).toBeUndefined();
    expect(store.totalEpisodesOf("src:42")).toBe(9);

    // A working sweep replaces the estimate with the real count.
    failSweep = false;
    await store.loadEpisodes(1);
    expect(store.catalogTotals["src:42"]?.count).toBe(9);
    expect(store.totalEpisodesOf("src:42")).toBe(9);
  });

  it("uses the loaded page length as the total of a single-page listing", async () => {
    reset();
    const store = createDesktopStore({
      registry: fakeRegistry(fakeSource()),
      persistPreferences: false,
    });
    await store.init();
    await store.openSeries(store.items[0]!);

    expect(store.episodeLastPage).toBe(1);
    expect(store.totalEpisodesOf("src:42")).toBe(3);
    // Unknown works answer `null` rather than `0`, so callers can tell "none" from "not loaded".
    expect(store.totalEpisodesOf("src:999")).toBeNull();
  });

  it("opens the oldest chapter, on the last page of a paginated listing (openFirstEpisode)", async () => {
    reset();
    const paged = fakeSource({
      async loadEpisodes(_item: ToonItem, page: number) {
        calls.episodes.push(page);
        // Two pages, newest first: page 2 holds the two oldest chapters.
        return page === 1
          ? { items: [episodeList[0]!], pageInfo: { currentPage: 1, lastPage: 2 } }
          : { items: episodeList.slice(1), pageInfo: { currentPage: 2, lastPage: 2 } };
      },
    });
    const store = createDesktopStore({
      registry: fakeRegistry(paged),
      persistPreferences: false,
    });
    await store.init();
    await store.openSeries(store.items[0]!);
    expect(store.episodeLastPage).toBe(2);

    await store.openFirstEpisode();
    // `lastOrNull()` of page 2: the oldest chapter, never the newest one on page 1.
    expect(store.currentEpisode?.wrId).toBe("e1");
    expect(store.screen).toBe("Reader");
    expect(calls.episodes.at(-1)).toBe(2);
  });

  it("resumes the recorded chapter, or starts from the first when nothing is recorded", async () => {
    reset();
    const store = createDesktopStore({
      registry: fakeRegistry(fakeSource()),
      persistPreferences: false,
    });
    await store.init();
    // The history store is process-wide, so a previous case may have left this work's row behind.
    await historyStore.deleteHistory("src", "42");
    await store.openSeries(store.items[0]!);

    // No history yet: `resumeSeries` delegates to `openFirstEpisode`.
    expect(store.seriesHistory).toBeNull();
    await store.resumeSeries();
    expect(store.currentEpisode?.wrId).toBe("e1");

    // A recorded chapter reopens at its saved page; the row is read from `seriesHistory`.
    store.closeReader();
    await historyStore.recordProgress(
      "src",
      "42",
      "테스트 작품",
      "",
      { wrId: "e2", title: "2화", href: "https://src.test/e2" },
      1,
      3,
    );
    store.seriesHistory = historyStore.getProgress("src", "42");
    expect(store.seriesHistory?.lastWrId).toBe("e2");
    await store.resumeSeries();
    expect(store.currentEpisode?.wrId).toBe("e2");
    // `ReaderProgress.startPageOnOpen`: reopen where the user stopped.
    expect(store.currentEpisode?.lastReadPage).toBe(1);
    store.destroy();
    await new Promise((resolve) => setTimeout(resolve, 20));
  });

  it("routes to settings and back to browse (openSettings / closeSettings)", () => {
    const store = createDesktopStore({
      registry: fakeRegistry(fakeSource()),
      persistPreferences: false,
    });
    store.openSettings();
    expect(store.screen).toBe("Settings");
    store.closeSettings();
    expect(store.screen).toBe("Browse");
  });

  it("debounces savePage into the history store and records progress on open", async () => {
    reset();
    const store = createDesktopStore({
      registry: fakeRegistry(fakeSource()),
      persistPreferences: false,
    });
    await store.init();
    await store.openSeries(store.items[0]!);
    await store.openEpisode(store.episodes[0]!);

    expect(store.currentEpisode?.lastReadPage).toBe(0);
    store.savePage(1);
    expect(store.currentEpisode?.lastReadPage).toBe(1);

    const progress = () => historyStore.getProgress("src", "42");
    // Opening the chapter already recorded page 0; the debounced save moves the row to page 1.
    expect(progress()?.lastWrId).toBe("e3");
    await new Promise((resolve) => setTimeout(resolve, 500));
    expect(progress()?.pageIndex).toBe(1);
    store.destroy();
    await new Promise((resolve) => setTimeout(resolve, 20));
  });
});

describe("DesktopStore source switching and teardown", () => {
  it("exposes activeSource and clears everything when no source is available", async () => {
    const store = createDesktopStore({
      registry: {
        init: async () => {},
        list: () => [],
        get: async () => null,
      } as unknown as SourceRegistry,
      persistPreferences: false,
    });
    await store.init();
    expect(store.activeSourceId).toBeNull();
    expect(store.activeSource).toBeNull();
    expect(store.browseTabs).toEqual([]);
    expect(store.activeTab).toBeNull();
    expect(store.screen).toBe("Browse");
    expect(store.items).toEqual([]);
  });
});

describe("progress badges (ProgressDisplayMode.format)", () => {
  const counted: CatalogTotal = { count: 64, lastPage: 1, pageSize: 100, delta: 0 };

  it("prints the chapter order or the read count against the counted total", () => {
    expect(formatProgressBadge("LATEST_EPISODE", 52, counted, 3)).toBe("52/64");
    expect(formatProgressBadge("READ_COUNT", 52, counted, 3)).toBe("3/64");
    // Nothing read yet: the total itself is the only fact ("104화"), like the Kotlin `else` branch.
    expect(formatProgressBadge("LATEST_EPISODE", 0, counted, 0)).toBe("64화");
    expect(formatProgressBadge("READ_COUNT", 0, counted, 0)).toBe("64화");
  });

  it("renders no badge at all for an uncounted work", () => {
    // `EpisodeListingTotal.kt`: a guessed denominator is displayed as fact, so it is never rendered.
    expect(formatProgressBadge("LATEST_EPISODE", 52, undefined, 3)).toBeNull();
    expect(formatProgressBadge("READ_COUNT", 52, undefined, 3)).toBeNull();
    expect(formatProgressBadge("LATEST_EPISODE", 52, { ...counted, count: 0 }, 3)).toBeNull();
  });

  it("never inflates the denominator with the read order or read count", () => {
    // Regression: `maxOf(total, order, readCount)` turned "read 199 of 199" into "200/200".
    expect(formatProgressBadge("LATEST_EPISODE", 55, { ...counted, count: 50 }, 0)).toBe("55/50");
    expect(formatProgressBadge("READ_COUNT", 0, { ...counted, count: 50 }, 55)).toBe("55/50");
  });

  it("defaults storage sources to read counts and remote ones to the chapter order", () => {
    expect(defaultProgressDisplayMode("local")).toBe("READ_COUNT");
    expect(defaultProgressDisplayMode("network-webdav")).toBe("READ_COUNT");
    expect(defaultProgressDisplayMode("hitomi")).toBe("LATEST_EPISODE");
  });
});

describe("episode position (ReaderDomain.episodePosition)", () => {
  const ids = ["e5", "e4", "e3", "e2", "e1"];

  it("counts from the oldest chapter on a single page", () => {
    // Newest-first listing: index 0 is "5화", so the order is reversed against the page length.
    expect(episodePosition(ids, "e5", 1, 1, undefined).readOrder).toBe(5);
    expect(episodePosition(ids, "e1", 1, 1, undefined).readOrder).toBe(1);
    expect(episodePosition(ids, "e3", 1, 1, undefined).nextEpisodeIndex).toBe(1);
  });

  it("needs a counted total to read an order off a partially loaded page", () => {
    const counted: CatalogTotal = { count: 20, lastPage: 4, pageSize: 5, delta: 0 };
    // Page 2 holds e5..e1 of a 20-chapter work. Kotlin: newer = (2 - 1) * 5 + index, order = 20 - newer.
    expect(episodePosition(ids, "e5", 2, 4, counted, 5).readOrder).toBe(15);
    expect(episodePosition(ids, "e1", 2, 4, counted, 5).readOrder).toBe(11);
    // No total: the page is a slice, so the position cannot be trusted and stays unknown.
    expect(episodePosition(ids, "e5", 2, 4, undefined, 5).readOrder).toBeNull();
  });

  it("has no order and no next chapter for an unknown or newest chapter", () => {
    expect(episodePosition(ids, "missing", 1, 1, undefined)).toEqual({
      readOrder: null,
      nextEpisodeIndex: null,
    });
    expect(episodePosition(ids, "e5", 1, 1, undefined).nextEpisodeIndex).toBeNull();
  });
});

describe("DesktopStore history actions", () => {
  const record = {
    sourceId: "src",
    toonId: "42",
    title: "테스트 작품",
    thumbUrl: "",
    lastWrId: "e2",
    lastEpisodeTitle: "2화",
    pageIndex: 2,
    totalPages: 10,
    updatedAt: Date.now(),
  };

  it("deletes a history row from every surface at once", async () => {
    const store = createDesktopStore({ persistPreferences: false });
    await historyStore.init();
    await historyStore.recordProgress("src", "42", "테스트 작품", "", null, 2, 10);

    store.historyItems = [record];
    store.items = [{ id: "42", title: "테스트 작품", thumbUrl: "", href: "", sourceId: "src", readProgress: "2/10" }];
    store.readCounts = { "src:42": 1 };
    store.series = { id: "42", title: "테스트 작품", thumbUrl: "", href: "", sourceId: "src" };
    store.seriesHistory = record;

    await store.deleteHistory(record);

    expect(store.historyItems).toEqual([]);
    expect(store.items[0]?.readProgress).toBeNull();
    expect(store.readCounts["src:42"]).toBeUndefined();
    expect(store.seriesHistory).toBeNull();
    expect(historyStore.getProgress("src", "42")).toBeNull();
  });

  it("reports an empty progress label for a work that was never counted", async () => {
    const store = createDesktopStore({ persistPreferences: false });
    await historyStore.init();
    expect(store.progressLabel(record)).toBe("");
  });
});

// ---------------------------------------------------------------------------------------------
// total pages: `normalisePageInfo` conventions and the page-jump dialog state
// ---------------------------------------------------------------------------------------------

// ---------------------------------------------------------------------------------------------
// listing totals: the ported `RepositoryTransforms` pure functions
// ---------------------------------------------------------------------------------------------

describe("totalFromPage / countEpisodeListing (RepositoryTransforms)", () => {
  const page = (items: number, currentPage: number, lastPage: number) => ({
    items: Array.from({ length: items }, (_, index) => ({ wrId: `e${index}` })),
    currentPage,
    lastPage,
  });

  it("takes a one-page listing as its own verified total", () => {
    expect(totalFromPage(page(17, 1, 1), 100, null)).toEqual({
      count: 17,
      lastPage: 1,
      pageSize: 100,
      delta: 0,
    });
    // An empty page is not a count: `isValid` requires `count > 0`.
    expect(totalFromPage(page(0, 1, 1), 100, null)).toBeNull();
  });

  it("refuses a page that is not the listing's tail", () => {
    const known: EpisodeListingTotal = { count: 23, lastPage: 3, pageSize: 10, delta: 3 };
    // Page 1 of 3 cannot refresh the total; only the tail page carries the live tail count.
    expect(totalFromPage(page(10, 1, 3), 10, known)).toBeNull();
    // naive = (3 - 1) * 10 + 10 = 30, plus the learned delta 3 → 33.
    expect(totalFromPage(page(10, 3, 3), 10, known)).toEqual({
      count: 33,
      lastPage: 3,
      pageSize: 10,
      delta: 3,
    });
  });

  it("refuses a stored shape the listing no longer has", () => {
    const known: EpisodeListingTotal = { count: 23, lastPage: 3, pageSize: 10, delta: 3 };
    // A changed page count means episodes were added or removed: the correction no longer applies.
    expect(totalFromPage(page(10, 4, 4), 10, known)).toBeNull();
    // A changed page size invalidates the arithmetic the count was validated against.
    expect(totalFromPage(page(10, 3, 3), 20, known)).toBeNull();
    expect(totalFromPage(page(10, 3, 3), 10, null)).toBeNull();
  });

  it("keeps the tail page live so an added newest episode moves the total", () => {
    // naive = (3 - 1) * 10 + 5 = 25, counted = 23, so delta = -2 (a short middle page).
    const known: EpisodeListingTotal = { count: 23, lastPage: 3, pageSize: 10, delta: -2 };
    expect(totalFromPage(page(5, 3, 3), 10, known)?.count).toBe(23);
    // One episode added to the tail page (`lastPage` unchanged) raises the total by exactly one.
    expect(totalFromPage(page(6, 3, 3), 10, known)?.count).toBe(24);
  });

  it("counts every page and learns the short-page correction", async () => {
    const sizes: Record<number, number> = { 1: 10, 2: 4, 3: 10 };
    const counted = await countEpisodeListing(10, async (number) => page(sizes[number]!, number, 3));
    // naive = (3 - 1) * 10 + 10 = 30, counted = 24, so the correction is -6.
    expect(counted).toEqual({ count: 24, lastPage: 3, pageSize: 10, delta: -6 });
  });

  it("never turns a partial sweep into a total", async () => {
    // A middle page that cannot be read leaves the work uncounted instead of guessing its size.
    expect(
      await countEpisodeListing(10, async (number) => {
        if (number === 2) throw new Error("page 2 down");
        return page(10, number, 3);
      }),
    ).toBeNull();
    // A middle page that comes back empty is equally not a count.
    expect(
      await countEpisodeListing(10, async (number) => page(number === 2 ? 0 : 10, number, 3)),
    ).toBeNull();
    // An empty first page is the documented "nothing here" answer.
    expect(await countEpisodeListing(10, async (number) => page(0, number, 1))).toBeNull();
  });

  it("maps a persisted row onto the render-only total", () => {
    // `updatedAt` is storage metadata; the reactive map must not carry it.
    expect(
      toCatalogTotal({ count: 64, lastPage: 1, pageSize: 100, delta: 0, updatedAt: 1_700_000 }),
    ).toEqual({ count: 64, lastPage: 1, pageSize: 100, delta: 0 });
  });
});

// ---------------------------------------------------------------------------------------------
// listing totals: `fetchEpisodeMetadata` and the browse badges
// ---------------------------------------------------------------------------------------------

describe("DesktopStore.fetchEpisodeMetadata (DesktopToonRepository parity)", () => {
  const item: ToonItem = {
    id: "42",
    title: "테스트 작품",
    thumbUrl: "https://src.test/42.jpg",
    href: "https://src.test/series/42",
  };
  /** 3 pages x pageSize 5 with a short middle page: only a sweep can produce 13. */
  const pageItems: Record<number, EpisodeItem[]> = {
    1: Array.from({ length: 5 }, (_, i) => ({ wrId: `a${i}`, title: `a${i}`, href: "" })),
    2: Array.from({ length: 3 }, (_, i) => ({ wrId: `b${i}`, title: `b${i}`, href: "" })),
    3: Array.from({ length: 5 }, (_, i) => ({ wrId: `c${i}`, title: `c${i}`, href: "" })),
  };

  beforeEach(async () => {
    await catalogStore.clear();
    await historyStore.init();
    historyStore.history = [];
    historyStore.readEpisodes = [];
  });

  function storeWith(
    loadEpisodes: (toon: ToonItem, page: number) => Promise<Record<string, unknown>>,
    options: { pageSize?: number; title?: string } = {},
  ) {
    const calls: number[] = [];
    const source = {
      id: "src",
      displayName: "Test Source",
      meta: {
        origin: "https://src.test",
        episodePageSize: options.pageSize ?? 5,
        searchPlaceholder: "검색",
      },
      catalogs: [{ id: "LATEST", label: "최신", paginated: true }],
      async loadListing(_catalogId: string, page: number) {
        return {
          items: [{ ...item, title: options.title ?? item.title }],
          pageInfo: { currentPage: page, lastPage: 1 },
        };
      },
      async loadEpisodes(toon: ToonItem, page: number) {
        calls.push(page);
        return loadEpisodes(toon, page);
      },
      async resolveImages() {
        return ["https://img.test/1.jpg"];
      },
      async search() {
        return [item];
      },
    };
    const store = createDesktopStore({
      registry: {
        init: async () => {},
        list: () => [
          { id: "src", displayName: "Test Source", origin: "", catalogs: [], builtin: true },
        ],
        get: async () => source,
      } as unknown as SourceRegistry,
      persistPreferences: false,
    });
    return { store, calls };
  }

  const countingPages = async (_toon: ToonItem, page: number) => ({
    items: pageItems[page] ?? [],
    pageInfo: { currentPage: page, lastPage: 3 },
  });

  it("counts an uncounted multi-page listing and persists the row", async () => {
    const { store, calls } = storeWith(countingPages);
    await store.init();
    const toon = store.items[0]!;

    const metadata = await store.fetchEpisodeMetadata(toon);
    expect(metadata.total).toEqual({ count: 13, lastPage: 3, pageSize: 5, delta: -2 });
    expect(store.catalogTotals["src:42"]).toEqual({
      count: 13,
      lastPage: 3,
      pageSize: 5,
      delta: -2,
    });
    expect(store.totalEpisodesOf("src:42")).toBe(13);
    // Every page was read exactly once on the first count.
    expect(calls).toEqual([1, 2, 3]);
    // The persisted row can be read back for another surface/session.
    const stored = catalogStore.getCatalog("src", "42");
    expect(stored).toMatchObject({ count: 13, lastPage: 3, pageSize: 5, delta: -2 });
    expect(typeof stored?.updatedAt).toBe("number");
  });

  it("reuses a stored shape and only reads the tail page", async () => {
    const { store, calls } = storeWith(countingPages);
    await store.init();
    const toon = store.items[0]!;
    await store.fetchEpisodeMetadata(toon);
    calls.length = 0;

    // Same page count and page size: the correction is still valid, so the tail page alone refreshes
    // the total instead of sweeping pages 1 and 2 again.
    const second = await store.fetchEpisodeMetadata(toon);
    expect(second.total?.count).toBe(13);
    expect(calls).toEqual([3]);
  });

  it("moves the total when the tail page gains an episode without changing the page count", async () => {
    let tail = pageItems[3]!;
    const { store } = storeWith(async (_toon, page) => ({
      items: page === 3 ? tail : (pageItems[page] ?? []),
      pageInfo: { currentPage: page, lastPage: 3 },
    }));
    await store.init();
    const toon = store.items[0]!;
    await store.fetchEpisodeMetadata(toon);
    expect(store.totalEpisodesOf("src:42")).toBe(13);

    // A newest episode lands on the tail page: `lastPage` is unchanged, so only the live tail count
    // can reveal it (this is exactly why the stored `delta` is not the whole answer).
    tail = [...pageItems[3]!, { wrId: "c9", title: "c9", href: "" }];
    const refreshed = await store.fetchEpisodeMetadata(toon);
    expect(refreshed.total?.count).toBe(14);
    expect(store.totalEpisodesOf("src:42")).toBe(14);
  });

  it("recounts when the stored shape no longer describes the listing", async () => {
    const { store, calls } = storeWith(countingPages);
    await store.init();
    const toon = store.items[0]!;
    await store.fetchEpisodeMetadata(toon);
    calls.length = 0;

    // The listing grew to 4 pages: the stored `lastPage` 3 must not be trusted.
    const grown = storeWith(async (_toon, page) => ({
      items: pageItems[page] ?? [{ wrId: `d${page}`, title: `d${page}`, href: "" }],
      pageInfo: { currentPage: page, lastPage: 4 },
    }));
    const recounted = await grown.store.fetchEpisodeMetadata(toon, {
      items: pageItems[1]!,
      currentPage: 1,
      lastPage: 4,
    });
    // The handed-over page 1 is reused (`loaded[knownPage.currentPage] = knownPage`), so the sweep
    // fetches exactly the pages it does not already hold.
    expect(grown.calls).toEqual([2, 3, 4]);
    expect(recounted.total?.lastPage).toBe(4);
    expect(recounted.total?.count).toBe(14);
    // The original store's row was replaced by the recount.
    expect(catalogStore.getCatalog("src", "42")?.count).toBe(14);
    expect(calls).toEqual([]);
  });

  it("takes a one-page listing straight from the handed-over page", async () => {
    const { store, calls } = storeWith(async (_toon, page) => ({
      items: pageItems[1]!,
      pageInfo: { currentPage: page, lastPage: 1 },
    }));
    await store.init();

    const metadata = await store.fetchEpisodeMetadata(store.items[0]!, {
      items: pageItems[1]!,
      currentPage: 1,
      lastPage: 1,
    });
    expect(metadata.total).toEqual({ count: 5, lastPage: 1, pageSize: 5, delta: 0 });
    // The page was handed over, so nothing was fetched to learn the total.
    expect(calls).toEqual([]);
    expect(catalogStore.getCatalog("src", "42")?.count).toBe(5);
  });

  it("prefers a total the plugin reported and never sweeps for it", async () => {
    const { store, calls } = storeWith(async (_toon, page) => ({
      items: pageItems[page] ?? [],
      pageInfo: { currentPage: page, lastPage: 3 },
      totalEpisodes: 250,
    }));
    await store.init();

    const metadata = await store.fetchEpisodeMetadata(store.items[0]!, {
      items: pageItems[1]!,
      currentPage: 1,
      lastPage: 3,
      reported: 250,
    });
    // No sweep: the reported number is stored with a correction derived from the tail page only.
    expect(metadata.total?.count).toBe(250);
    expect(metadata.total).toMatchObject({ lastPage: 3, pageSize: 5, delta: 250 - (2 * 5 + 5) });
    expect(calls).toEqual([3]);
    expect(store.totalEpisodesOf("src:42")).toBe(250);
    expect(catalogStore.getCatalog("src", "42")?.count).toBe(250);
  });

  it("returns a thumbnail the episode listing supplied when the card has none", async () => {
    const { store } = storeWith(async (_toon, page) => ({
      items: [{ wrId: `t${page}`, title: "t", href: "", thumbUrl: "https://src.test/cover.jpg" }],
      pageInfo: { currentPage: page, lastPage: 1 },
    }));
    await store.init();
    const blank = { ...store.items[0]!, thumbUrl: "" };

    const metadata = await store.fetchEpisodeMetadata(blank, {
      items: [{ wrId: "t1", title: "t", href: "", thumbUrl: "https://src.test/cover.jpg" }],
      currentPage: 1,
      lastPage: 1,
    });
    expect(metadata.thumbUrl).toBe("https://src.test/cover.jpg");
    // A card that already has a cover costs no extra page read.
    expect((await store.fetchEpisodeMetadata(store.items[0]!)).thumbUrl).toBeNull();
  });
});

describe("DesktopStore browse progress (applyListingFlags)", () => {
  const cards: ToonItem[] = [
    { id: "a", title: "A", thumbUrl: "", href: "" },
    { id: "b", title: "B", thumbUrl: "", href: "" },
    { id: "c", title: "C", thumbUrl: "", href: "" },
  ];

  beforeEach(async () => {
    await catalogStore.clear();
    await historyStore.init();
    historyStore.history = [];
    historyStore.readEpisodes = [];
  });

  function storeWithListing(list: ToonItem[]) {
    const source = {
      id: "src",
      displayName: "Test Source",
      meta: { origin: "https://src.test", episodePageSize: 100, searchPlaceholder: "검색" },
      catalogs: [{ id: "LATEST", label: "최신", paginated: false }],
      async loadListing(_catalogId: string, page: number) {
        return { items: list, pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async loadEpisodes(_toon: ToonItem, page: number) {
        return { items: [], pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async resolveImages() {
        return [];
      },
      async search() {
        return list;
      },
    };
    return createDesktopStore({
      registry: {
        init: async () => {},
        list: () => [
          { id: "src", displayName: "Test Source", origin: "", catalogs: [], builtin: true },
        ],
        get: async () => source,
      } as unknown as SourceRegistry,
      persistPreferences: false,
    });
  }

  it("prints the counted total on a card, and the chapter order once read", async () => {
    await catalogStore.saveCatalog("src", "a", {
      count: 64,
      lastPage: 1,
      pageSize: 100,
      delta: 0,
    });
    await catalogStore.saveCatalog("src", "b", {
      count: 12,
      lastPage: 1,
      pageSize: 100,
      delta: 0,
    });

    const store = storeWithListing(cards);
    await store.init();
    // Nothing read yet: the total itself is the only fact ("64화").
    expect(store.items.find((row) => row.id === "a")?.readProgress).toBe("64화");
    expect(store.items.find((row) => row.id === "b")?.readProgress).toBe("12화");
    // An uncounted work shows no badge instead of a guess.
    expect(store.items.find((row) => row.id === "c")?.readProgress).toBeNull();
    expect(store.totalEpisodesOf("src:a")).toBe(64);
    expect(store.totalEpisodesOf("src:b")).toBe(12);
    expect(store.totalEpisodesOf("src:c")).toBeNull();
  });

  it("shows order/total on the card once a chapter has been read", async () => {
    // A 64-chapter listing (newest first), so chapter 52 is index 12.
    const listing: EpisodeItem[] = Array.from({ length: 64 }, (_, index) => ({
      wrId: `e${64 - index}`,
      title: `${64 - index}화`,
      href: "",
    }));
    const source = {
      id: "src",
      displayName: "Test Source",
      meta: { origin: "https://src.test", episodePageSize: 100, searchPlaceholder: "검색" },
      catalogs: [{ id: "LATEST", label: "최신", paginated: false }],
      async loadListing(_catalogId: string, page: number) {
        return { items: cards, pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async loadEpisodes(_toon: ToonItem, page: number) {
        return { items: listing, pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async resolveImages() {
        return ["https://img.test/1.jpg"];
      },
      async search() {
        return cards;
      },
    };
    const store = createDesktopStore({
      registry: {
        init: async () => {},
        list: () => [
          { id: "src", displayName: "Test Source", origin: "", catalogs: [], builtin: true },
        ],
        get: async () => source,
      } as unknown as SourceRegistry,
      persistPreferences: false,
    });
    await store.init();

    await store.openSeries(store.items[0]!);
    expect(store.totalEpisodesOf("src:a")).toBe(64);
    // Newest first: index 12 is chapter 52, so the read order is 52 of 64.
    await store.openEpisode(store.episodes[12]!);
    store.closeReader();
    await store.closeSeries();

    expect(store.items.find((row) => row.id === "a")?.readProgress).toBe("52/64");
    // The counted total is persisted, so a later session shows "64화" before anything is read.
    expect(catalogStore.getCatalog("src", "a")).toMatchObject({
      count: 64,
      lastPage: 1,
      pageSize: 100,
      delta: 0,
    });
  });

  it("counts chapters for a storage source (READ_COUNT default)", async () => {
    const store = createDesktopStore({ persistPreferences: false });
    // `local` is the storage source id, and its default badge mode counts read chapters.
    expect(defaultProgressDisplayMode("local")).toBe("READ_COUNT");
    await store.init();
    expect(store.totalEpisodesOf("local:missing")).toBeNull();
  });
});

describe("catalog pagination (normalisePageInfo / goToPage / togglePageJump)", () => {
  const card: ToonItem = {
    id: "42",
    title: "테스트 작품",
    thumbUrl: "https://src.test/42.jpg",
    href: "https://src.test/series/42",
  };

  /**
   * Store over a source whose `loadListing` answers whatever `listing(page)` spreads into the
   * result — that is how each plugin pagination convention is exercised.
   */
  async function storeWithListing(
    listing: (page: number) => Record<string, unknown>,
    calls: { listing: Array<[string, number]> } = { listing: [] },
  ) {
    const source = {
      id: "src",
      displayName: "Test Source",
      meta: { origin: "https://src.test", episodePageSize: 100, searchPlaceholder: "검색" },
      catalogs: [
        { id: "LATEST", label: "최신", paginated: true },
        { id: "POPULAR", label: "인기", paginated: false },
      ],
      async loadListing(catalogId: string, page: number) {
        calls.listing.push([catalogId, page]);
        return { items: [{ ...card, id: `${page}` }], ...listing(page) };
      },
      async loadEpisodes(_item: ToonItem, page: number) {
        return { items: [], pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async resolveImages() {
        return [];
      },
      async search() {
        return [card];
      },
    };
    const store = createDesktopStore({
      registry: {
        init: async () => {},
        list: () => [
          { id: "src", displayName: "Test Source", origin: "", catalogs: [], builtin: true },
        ],
        get: async () => source,
      } as unknown as SourceRegistry,
      persistPreferences: false,
    });
    await store.init();
    return { store, calls };
  }

  it("reads the documented nested pageInfo", async () => {
    const { store } = await storeWithListing(() => ({
      pageInfo: { currentPage: 2, lastPage: 7 },
    }));
    expect([store.page, store.lastPage, store.lastPageKnown]).toEqual([2, 7, true]);
    const info = normalisePageInfo({ pageInfo: { currentPage: 2, lastPage: 7 } }, 1);
    expect(info).toEqual({ currentPage: 2, lastPage: 7, lastPageKnown: true });
  });

  it("reads maxPage instead of lastPage", async () => {
    const { store } = await storeWithListing(() => ({
      pageInfo: { currentPage: 1, maxPage: 9 },
    }));
    expect(store.lastPage).toBe(9);
    expect(store.lastPageKnown).toBe(true);
    expect(normalisePageInfo({ pageInfo: { currentPage: 3, maxPage: 9 } }, 1)).toEqual({
      currentPage: 3,
      lastPage: 9,
      lastPageKnown: true,
    });
  });

  it("reads pageInfo.page as the current page", async () => {
    const { store } = await storeWithListing(() => ({
      pageInfo: { page: 4, lastPage: 6 },
    }));
    expect(store.page).toBe(4);
    expect(store.lastPage).toBe(6);
  });

  it("reads the page keys from the listing result when pageInfo is missing", async () => {
    const { store } = await storeWithListing(() => ({ lastPage: 5, currentPage: 3 }));
    expect([store.page, store.lastPage]).toEqual([3, 5]);

    expect(normalisePageInfo({ result: undefined, lastPage: 5, page: 2 }, 1)).toEqual({
      currentPage: 2,
      lastPage: 5,
      lastPageKnown: true,
    });
    expect(normalisePageInfo({ maxPage: 8, currentPage: 1 }, 1).lastPage).toBe(8);
  });

  it("reads result.total_pages, the snake_case spelling some plugins use", async () => {
    const { store } = await storeWithListing(() => ({ total_pages: 12, currentPage: 1 }));
    expect([store.page, store.lastPage]).toEqual([1, 12]);
    expect(normalisePageInfo({ total_pages: 12, page: 2 }, 1)).toEqual({
      currentPage: 2,
      lastPage: 12,
      lastPageKnown: true,
    });
  });

  it("prefers the nested pageInfo over the result keys, and the result keys over the fallback", () => {
    // Both spellings present: the nested total wins.
    expect(
      normalisePageInfo({ lastPage: 3, pageInfo: { currentPage: 1, lastPage: 7 } }, 1).lastPage,
    ).toBe(7);
    // A partial pageInfo falls through to the result-level keys.
    expect(
      normalisePageInfo({ maxPage: 5, pageInfo: { currentPage: 2 } }, 1),
    ).toMatchObject({ currentPage: 2, lastPage: 5 });
    // Nothing reported: the requested page is the current page and the total is unknown.
    expect(normalisePageInfo(undefined, 3)).toEqual({
      currentPage: 3,
      lastPage: 3,
      lastPageKnown: false,
    });
    // String spellings and junk values (`0`, `-1`, `abc`) are normalised away.
    expect(normalisePageInfo({ currentPage: "2", lastPage: "10" }, 1)).toEqual({
      currentPage: 2,
      lastPage: 10,
      lastPageKnown: true,
    });
    expect(normalisePageInfo({ currentPage: 0, lastPage: -4 }, 1)).toEqual({
      currentPage: 1,
      lastPage: 1,
      lastPageKnown: false,
    });
    // Never report a total below the current page (`lastPage.coerceAtLeast(currentPage)`).
    expect(normalisePageInfo({ currentPage: 9, lastPage: 2 }, 1)).toEqual({
      currentPage: 9,
      lastPage: 9,
      lastPageKnown: true,
    });
  });

  it("goToPage clamps against a known total and opens the dialog state", async () => {
    const { store, calls } = await storeWithListing((page) => ({
      pageInfo: { currentPage: page, lastPage: 4 },
    }));
    store.togglePageJump(true);
    expect(store.showPageJump).toBe(true);

    await store.goToPage(3);
    expect(store.page).toBe(3);
    expect(calls.listing.at(-1)).toEqual(["LATEST", 3]);
    // `goToPage` dismisses the dialog it was opened from (`showPageJump = false`).
    expect(store.showPageJump).toBe(false);

    await store.goToPage(99);
    expect(store.page).toBe(4);
    await store.goToPage(0);
    expect(store.page).toBe(1);
  });

  it("goToPage only clamps the lower edge when the total is unknown", async () => {
    const { store, calls } = await storeWithListing((page) => ({
      pageInfo: { currentPage: page },
    }));
    expect(store.lastPageKnown).toBe(false);
    await store.goToPage(6);
    expect(store.page).toBe(6);
    expect(calls.listing.at(-1)).toEqual(["LATEST", 6]);
  });

  it("goToPage returns early for a non-paginated tab (`tab?.paginated != true`)", async () => {
    const { store, calls } = await storeWithListing((page) => ({
      pageInfo: { currentPage: page, lastPage: 4 },
    }));
    await store.selectTab("src:POPULAR");
    calls.listing.length = 0;
    await store.goToPage(3);
    expect(calls.listing).toEqual([]);
    expect(store.page).toBe(1);
  });

  it("nextPage / prevPage walk a known total and stop at the edges", async () => {
    const { store } = await storeWithListing((page) => ({
      pageInfo: { currentPage: page, lastPage: 2 },
    }));
    await store.nextPage();
    expect(store.page).toBe(2);
    await store.nextPage();
    expect(store.page).toBe(2);
    await store.prevPage();
    expect(store.page).toBe(1);
    await store.prevPage();
    expect(store.page).toBe(1);
  });

  it("togglePageJump flips without an argument and honours an explicit value", async () => {
    const store = createDesktopStore({ persistPreferences: false });
    expect(store.showPageJump).toBe(false);
    store.togglePageJump();
    expect(store.showPageJump).toBe(true);
    store.togglePageJump();
    expect(store.showPageJump).toBe(false);
    store.togglePageJump(true);
    expect(store.showPageJump).toBe(true);
    store.togglePageJump(true);
    expect(store.showPageJump).toBe(true);
    store.togglePageJump(false);
    expect(store.showPageJump).toBe(false);
  });
});

describe("sync dialog visibility (DesktopUiState.showSyncDialog)", () => {
  it("opens and closes through the view-model actions", () => {
    const store = createDesktopStore({ persistPreferences: false });
    expect(store.showSyncDialog).toBe(false);

    store.openSyncDialog();
    expect(store.showSyncDialog).toBe(true);

    // Opening twice is idempotent, exactly like `it.copy(showSyncDialog = true)`.
    store.openSyncDialog();
    expect(store.showSyncDialog).toBe(true);

    store.closeSyncDialog();
    expect(store.showSyncDialog).toBe(false);
    store.closeSyncDialog();
    expect(store.showSyncDialog).toBe(false);
  });

  it("does not disturb the screen the dialog was opened from", () => {
    const store = createDesktopStore({ persistPreferences: false });
    store.screen = "Series";
    store.openSyncDialog();
    expect(store.screen).toBe("Series");
    store.closeSyncDialog();
    expect(store.screen).toBe("Series");
  });
});

describe("DesktopStore episode thumbnail repair (updateThumbnailIfBlank)", () => {
  beforeEach(async () => {
    await catalogStore.clear();
    await historyStore.init();
    historyStore.history = [];
    historyStore.readEpisodes = [];
  });

  it("fills a blank cover from the first episode page", async () => {
    const card: ToonItem = { id: "42", title: "테스트 작품", thumbUrl: "", href: "" };
    const source = {
      id: "src",
      displayName: "Test Source",
      meta: { origin: "https://src.test", episodePageSize: 100, searchPlaceholder: "검색" },
      catalogs: [{ id: "LATEST", label: "최신", paginated: false }],
      async loadListing(_catalogId: string, page: number) {
        return { items: [card], pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async loadEpisodes(_toon: ToonItem, page: number) {
        return {
          items: [
            { wrId: "e2", title: "2화", href: "", thumbUrl: "https://src.test/cover.jpg" },
            { wrId: "e1", title: "1화", href: "" },
          ],
          pageInfo: { currentPage: page, lastPage: 1 },
        };
      },
      async resolveImages() {
        return [];
      },
      async search() {
        return [card];
      },
    };
    const store = createDesktopStore({
      registry: {
        init: async () => {},
        list: () => [
          { id: "src", displayName: "Test Source", origin: "", catalogs: [], builtin: true },
        ],
        get: async () => source,
      } as unknown as SourceRegistry,
      persistPreferences: false,
    });
    await store.init();
    expect(store.items[0]?.thumbUrl).toBe("");

    await store.openSeries(store.items[0]!);
    // The card and the open series both learn the cover the episode listing carried.
    expect(store.series?.thumbUrl).toBe("https://src.test/cover.jpg");
    expect(store.items[0]?.thumbUrl).toBe("https://src.test/cover.jpg");
  });

  it("leaves an existing cover alone", async () => {
    const card: ToonItem = {
      id: "42",
      title: "테스트 작품",
      thumbUrl: "https://src.test/card.jpg",
      href: "",
    };
    const source = {
      id: "src",
      displayName: "Test Source",
      meta: { origin: "https://src.test", episodePageSize: 100, searchPlaceholder: "검색" },
      catalogs: [{ id: "LATEST", label: "최신", paginated: false }],
      async loadListing(_catalogId: string, page: number) {
        return { items: [card], pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async loadEpisodes(_toon: ToonItem, page: number) {
        return {
          items: [{ wrId: "e1", title: "1화", href: "", thumbUrl: "https://src.test/ep.jpg" }],
          pageInfo: { currentPage: page, lastPage: 1 },
        };
      },
      async resolveImages() {
        return [];
      },
      async search() {
        return [card];
      },
    };
    const store = createDesktopStore({
      registry: {
        init: async () => {},
        list: () => [
          { id: "src", displayName: "Test Source", origin: "", catalogs: [], builtin: true },
        ],
        get: async () => source,
      } as unknown as SourceRegistry,
      persistPreferences: false,
    });
    await store.init();
    await store.openSeries(store.items[0]!);
    expect(store.series?.thumbUrl).toBe("https://src.test/card.jpg");
  });
});

// ---------------------------------------------------------------------------------------------
// server-first catalog batch: `DesktopToonRepository.syncEpisodeCounts` (step 1)
// ---------------------------------------------------------------------------------------------

describe("DesktopStore.syncEpisodeMetadata (server-first catalog batch)", () => {
  beforeEach(async () => {
    await catalogStore.clear();
    await historyStore.init();
    historyStore.history = [];
    historyStore.readEpisodes = [];
  });

  const card = (id: string, title = `작품 ${id}`): ToonItem => ({
    id,
    title,
    thumbUrl: `https://src.test/${id}.jpg`,
    href: `https://src.test/series/${id}`,
  });

  type CatalogCall = { sourceId: string; toonId: string };

  /**
   * Store over a listing of `cards` whose `SyncClient.fetchCatalogBatch` answers from `rows`
   * (a `Map` from toonId to the solved catalog row) and records every query.
   */
  function storeWithBatch(
    cards: ToonItem[],
    rows: Map<string, Record<string, unknown> | undefined>,
    options: { sourceId?: string } = {},
  ) {
    const sourceId = options.sourceId ?? "src";
    const calls: CatalogCall[][] = [];
    const source = {
      id: sourceId,
      displayName: "Test Source",
      meta: { origin: "https://src.test", episodePageSize: 100, searchPlaceholder: "검색" },
      catalogs: [{ id: "LATEST", label: "최신", paginated: false }],
      async loadListing(_catalogId: string, page: number) {
        return { items: cards, pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async loadEpisodes(_toon: ToonItem, page: number) {
        return { items: [], pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async resolveImages() {
        return [];
      },
      async search() {
        return cards;
      },
    };
    const syncClient = {
      fetchCatalogBatch: async (items: readonly CatalogCall[]) => {
        calls.push(items.map((entry) => ({ ...entry })));
        return items
          .map((entry) => rows.get(entry.toonId))
          .filter((row): row is Record<string, unknown> => row !== undefined);
      },
    } as unknown as SyncClient;
    const store = createDesktopStore({
      registry: {
        init: async () => {},
        list: () => [
          { id: sourceId, displayName: "Test Source", origin: "", catalogs: [], builtin: true },
        ],
        get: async () => source,
      } as unknown as SourceRegistry,
      syncClient,
      persistPreferences: false,
    });
    return { store, calls };
  }

  /** Waits for the fire-and-forget `void this.syncEpisodeMetadata(...)` of a listing load. */
  const settle = async (): Promise<void> => {
    for (let index = 0; index < 10; index += 1) await Promise.resolve();
    await new Promise((resolve) => setTimeout(resolve, 0));
  };

  it("asks the server for the works it never counted and paints their badges", async () => {
    const { store, calls } = storeWithBatch(
      [card("a"), card("b")],
      new Map([
        ["a", { sourceId: "src", toonId: "a", totalEpisodes: 104, lastPage: 2, pageSize: 100, delta: 4 }],
        ["b", { sourceId: "src", toonId: "b", totalEpisodes: 12, lastPage: 1, pageSize: 100, delta: 0 }],
      ]),
    );

    await store.init();
    expect(calls).toEqual([[{ sourceId: "src", toonId: "a" }, { sourceId: "src", toonId: "b" }]]);
    await settle();

    // Nothing read yet: the total itself is what the card prints, exactly like the desktop.
    expect(store.items.find((row) => row.id === "a")?.readProgress).toBe("104화");
    expect(store.items.find((row) => row.id === "b")?.readProgress).toBe("12화");
    expect(store.catalogTotals["src:a"]).toEqual({
      count: 104,
      lastPage: 2,
      pageSize: 100,
      delta: 4,
    });
    expect(store.totalEpisodesOf("src:a")).toBe(104);
    // The row is persisted, so the next session renders the badge without a request.
    expect(catalogStore.getCatalog("src", "a")).toMatchObject({
      count: 104,
      lastPage: 2,
      pageSize: 100,
      delta: 4,
    });
  });

  it("prints the read order against the server total once a chapter has been read", async () => {
    const listing: EpisodeItem[] = Array.from({ length: 64 }, (_, index) => ({
      wrId: `e${64 - index}`,
      title: `${64 - index}화`,
      href: "",
    }));
    const cards = [card("a")];
    const source = {
      id: "src",
      displayName: "Test Source",
      meta: { origin: "https://src.test", episodePageSize: 100, searchPlaceholder: "검색" },
      catalogs: [{ id: "LATEST", label: "최신", paginated: false }],
      async loadListing(_catalogId: string, page: number) {
        return { items: cards, pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async loadEpisodes(_toon: ToonItem, page: number) {
        return { items: listing, pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async resolveImages() {
        return ["https://img.test/1.jpg"];
      },
      async search() {
        return cards;
      },
    };
    const syncClient = {
      fetchCatalogBatch: async (items: readonly CatalogCall[]) =>
        items.map((entry) => ({
          sourceId: "src",
          toonId: entry.toonId,
          totalEpisodes: 64,
          lastPage: 1,
          pageSize: 100,
          delta: 0,
        })),
    } as unknown as SyncClient;
    const store = createDesktopStore({
      registry: {
        init: async () => {},
        list: () => [
          { id: "src", displayName: "Test Source", origin: "", catalogs: [], builtin: true },
        ],
        get: async () => source,
      } as unknown as SourceRegistry,
      syncClient,
      persistPreferences: false,
    });
    await store.init();
    await settle();

    // Chapter 52 of the newest-first 64-chapter listing is index 12.
    await store.openSeries(store.items[0]!);
    await store.openEpisode(store.episodes[12]!);
    store.closeReader();
    await store.closeSeries();
    await settle();

    expect(store.items[0]?.readProgress).toBe("52/64");
  });

  it("asks nothing when every card already has a counted total", async () => {
    await catalogStore.saveCatalog("src", "a", {
      count: 64,
      lastPage: 1,
      pageSize: 100,
      delta: 0,
    });
    const { store, calls } = storeWithBatch([card("a")], new Map());

    await store.init();
    await settle();

    expect(calls).toEqual([]);
    expect(store.items[0]?.readProgress).toBe("64화");
  });

  it("asks only for the rows that are missing locally", async () => {
    await catalogStore.saveCatalog("src", "a", {
      count: 64,
      lastPage: 1,
      pageSize: 100,
      delta: 0,
    });
    const { store, calls } = storeWithBatch(
      [card("a"), card("b")],
      new Map([["b", { sourceId: "src", toonId: "b", totalEpisodes: 9 }]]),
    );

    await store.init();
    await settle();

    expect(calls).toEqual([[{ sourceId: "src", toonId: "b" }]]);
    expect(store.items.map((row) => row.readProgress)).toEqual(["64화", "9화"]);
  });

  it("keeps the cards badge-less when the server has never counted them", async () => {
    const { store, calls } = storeWithBatch([card("a")], new Map());

    await store.init();
    await settle();

    expect(calls).toHaveLength(1);
    expect(store.items[0]?.readProgress).toBeNull();
    expect(store.catalogTotals["src:a"]).toBeUndefined();
    expect(catalogStore.getCatalog("src", "a")).toBeNull();
  });

  it("drops rows the server could not shape into a count", async () => {
    const { store } = storeWithBatch(
      [card("a"), card("b")],
      new Map([
        ["a", { sourceId: "src", toonId: "a", totalEpisodes: 0 }],
        ["b", { sourceId: "src", toonId: "b", totalEpisodes: 7, lastPage: 0, pageSize: 0 }],
      ]),
    );

    await store.init();
    await settle();

    // `totalEpisodes: 0` means "never counted"; `lastPage: 0` is an impossible shape, so both rows
    // are dropped instead of being adopted as a total (`EpisodeListingTotal.isValid`).
    expect(store.catalogTotals["src:a"]).toBeUndefined();
    expect(store.catalogTotals["src:b"]).toBeUndefined();
    expect(store.items.map((row) => row.readProgress)).toEqual([null, null]);
  });

  it("fills an omitted shape with the single-page defaults", async () => {
    const { store } = storeWithBatch(
      [card("a")],
      new Map([["a", { sourceId: "src", toonId: "a", totalEpisodes: 7, delta: -1 }]]),
    );

    await store.init();
    await settle();

    expect(store.catalogTotals["src:a"]).toEqual({
      count: 7,
      lastPage: 1,
      pageSize: 100,
      delta: -1,
    });
  });

  it("never asks the server about the local library", async () => {
    const { store, calls } = storeWithBatch([card("a")], new Map(), { sourceId: "local" });

    await store.init();
    await settle();

    expect(calls).toEqual([]);
  });

  it("surfaces a failing catalog lookup as a missing badge, not an error", async () => {
    const source = {
      id: "src",
      displayName: "Test Source",
      meta: { origin: "https://src.test", episodePageSize: 100, searchPlaceholder: "검색" },
      catalogs: [{ id: "LATEST", label: "최신", paginated: false }],
      async loadListing(_catalogId: string, page: number) {
        return { items: [card("a")], pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async loadEpisodes(_toon: ToonItem, page: number) {
        return { items: [], pageInfo: { currentPage: page, lastPage: 1 } };
      },
      async resolveImages() {
        return [];
      },
      async search() {
        return [card("a")];
      },
    };
    const syncClient = {
      fetchCatalogBatch: async () => {
        throw new Error("offline");
      },
    } as unknown as SyncClient;
    const store = createDesktopStore({
      registry: {
        init: async () => {},
        list: () => [
          { id: "src", displayName: "Test Source", origin: "", catalogs: [], builtin: true },
        ],
        get: async () => source,
      } as unknown as SourceRegistry,
      syncClient,
      persistPreferences: false,
    });

    await store.init();
    await settle();

    expect(store.error).toBeNull();
    expect(store.items[0]?.readProgress).toBeNull();
  });

  it("re-decorates search results from the server cache too", async () => {
    const cards = [card("a")];
    const { store, calls } = storeWithBatch(
      cards,
      new Map([
        ["a", { sourceId: "src", toonId: "a", totalEpisodes: 8 }],
        ["z", { sourceId: "src", toonId: "z", totalEpisodes: 31 }],
      ]),
    );
    await store.init();
    await settle();
    calls.length = 0;

    // A card the grid never showed (a search hit this session has not counted) is asked for.
    cards.push(card("z"));
    await store.search("작품");
    await settle();

    expect(calls).toEqual([[{ sourceId: "src", toonId: "z" }]]);
    expect(store.items.find((row) => row.id === "z")?.readProgress).toBe("31화");
  });

  it("debounces automatic background sync pushes", async () => {
    vi.useFakeTimers();
    try {
      const store = createDesktopStore({ persistPreferences: false });
      let syncCalls = 0;
      store.syncNow = vi.fn().mockImplementation(async () => {
        syncCalls += 1;
        return { ok: true, message: "OK", favorites: 0, history: 0, readEpisodes: 0 };
      });
      store.syncState = { ...store.syncState, autoSyncEnabled: true, syncKey: "test-key" };

      store.triggerDebouncedPush();
      store.triggerDebouncedPush();
      store.triggerDebouncedPush();

      expect(syncCalls).toBe(0);
      await vi.advanceTimersByTimeAsync(2600);
      expect(syncCalls).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it("compares versions correctly (isNewerVersion parity)", async () => {
    const { isNewerVersion } = await import("../src/state/desktop-state.svelte");
    expect(isNewerVersion("0.2.49", 54, "0.2.48", 53)).toBe(true);
    expect(isNewerVersion("0.2.48", 53, "0.2.48", 53)).toBe(false);
    expect(isNewerVersion("0.2.47", 52, "0.2.48", 53)).toBe(false);
    expect(isNewerVersion("v0.3.0", 0, "0.2.48", 0)).toBe(true);
  });

  it("fetches update info and reports status", async () => {
    const store = createDesktopStore({ persistPreferences: false });
    const originalFetch = globalThis.fetch;
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        desktop: {
          versionName: "0.2.50",
          versionCode: 55,
          downloadUrl: "https://example.com/download.zip",
          releaseNotes: "New features",
        },
        history: [
          { versionName: "0.2.50", versionCode: 55, date: "2026-10-27", releaseNotes: "New features" },
        ],
      }),
    } as any);

    try {
      const msg = await store.checkForUpdate(true);
      expect(msg).toContain("새 버전(v0.2.50)이 있습니다");
      expect(store.updateState.hasUpdate).toBe(true);
      expect(store.updateState.latestVersion).toBe("0.2.50");
      expect(store.updateState.history.length).toBe(1);
    } finally {
      globalThis.fetch = originalFetch;
    }
  });
});
