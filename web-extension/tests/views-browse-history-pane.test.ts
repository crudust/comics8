/**
 * Behaviour of `views/BrowsePane.svelte` and `views/HistoryPane.svelte`, the 1:1 ports of
 * `desktop/src/main/kotlin/ui/browse/BrowsePane.kt` and `desktop/src/main/kotlin/ui/history/HistoryPane.kt`.
 *
 * Both Kotlin panes are Compose surfaces driven by a single `DesktopUiState` + `DesktopViewModel`
 * pair, so both web panes are driven by the ported store (`src/state/desktop-state.svelte.ts`)
 * instead of props. The tests therefore seed the store and assert what the pane renders:
 *
 * BrowsePane
 * - the catalog chip row (`BrowseTab.displayLabel`, one selected chip) and `selectTab`,
 * - the adaptive poster grid with `ToonCard`'s parts (cover, 2-line title, author/subtitle, gold star),
 * - the bottom pager, which only exists for a paginated catalog with `lastPage > 1`,
 * - the loading / error / empty-favorites branches of the Kotlin `when`.
 *
 * HistoryPane
 * - `HistoryItemCard` (72x54 thumbnail, title, relative time, progress badge from
 *   `store.progressLabel(item)`),
 * - clicking a row opens the series through `openSeriesFromHistory` (back returns to History),
 * - the empty state ("최근 본 작품이 없습니다") and its way back to browsing.
 *
 * Styling is not asserted through `getComputedStyle`: happy-dom does not apply Svelte's scoped
 * styles, so the class contract (`.chip.selected`, `.star.on`, `.progress-badge`) is the stable
 * thing to check.
 */

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { flushSync, mount, unmount } from "svelte";
import { readFileSync } from "node:fs";

import BrowsePane from "../src/views/BrowsePane.svelte";
import HistoryPane from "../src/views/HistoryPane.svelte";
import {
  createDesktopStore,
  type CatalogTotal,
  type DesktopStore,
} from "../src/state/desktop-state.svelte";
import { favoritesStore } from "../src/data/favorites-store.svelte";
import { catalogStore } from "../src/data/catalog-store.svelte";
import type { SyncClient } from "../src/sync/sync-client";
import { historyStore } from "../src/data/history-store.svelte";
import type { SourceRegistry } from "../src/sources/registry";
import type { ToonItem } from "../src/sources/types";

const item: ToonItem = {
  id: "42",
  title: "테스트 작품",
  thumbUrl: "https://src.test/42.jpg",
  href: "https://src.test/series/42",
  genre: "판타지",
  updatedAt: "10.03",
  isNew: true,
};

const second: ToonItem = {
  id: "43",
  title: "두 번째 작품",
  thumbUrl: "https://src.test/43.jpg",
  href: "https://src.test/series/43",
  genre: "액션",
};

type Calls = {
  listing: Array<[string, number]>;
  episodes: number[];
  images: number;
};

/**
 * Fake `AnyLoadedSource`. `pages` maps a 1-indexed catalog page to its cards; omitting it serves
 * `lastPage = 1`, which is how the pager's "single page" guard is exercised.
 */
function fakeSource(
  options: {
    pageCount?: number;
    itemsFor?: (page: number) => ToonItem[];
    failListing?: () => boolean;
    catalogs?: Array<{ id: string; label: string; paginated: boolean }>;
  },
  calls: Calls,
) {
  const pageCount = options.pageCount ?? 1;
  return {
    id: "test-src",
    displayName: "Test Source",
    meta: {
      origin: "https://src.test",
      episodePageSize: 100,
      searchPlaceholder: "검색",
      progressDisplay: "LAST_READ_ORDER",
    },
    catalogs: options.catalogs ?? [
      { id: "LATEST", label: "최신", paginated: true },
      { id: "POPULAR", label: "인기", paginated: false },
    ],
    async loadListing(catalogId: string, page: number) {
      calls.listing.push([catalogId, page]);
      if (options.failListing?.()) throw new Error("목록 서버가 응답하지 않습니다.");
      const items = options.itemsFor ? options.itemsFor(page) : [item];
      return { items, pageInfo: { currentPage: page, lastPage: pageCount } };
    },
    async loadEpisodes(_item: ToonItem, page: number) {
      calls.episodes.push(page);
      return {
        items: [
          { wrId: "e2", title: "2화", href: "https://src.test/e2", date: "2026.10.02" },
          { wrId: "e1", title: "1화", href: "https://src.test/e1", date: "2026.10.01" },
        ],
        pageInfo: { currentPage: page, lastPage: 1 },
      };
    },
    async resolveImages() {
      calls.images += 1;
      return ["https://img.test/1.jpg"];
    },
    async search() {
      return [item];
    },
  };
}

function fakeRegistry(source: unknown): SourceRegistry {
  return {
    init: async () => {},
    list: () => [
      { id: "test-src", displayName: "Test Source", origin: "", catalogs: [], builtin: true },
    ],
    get: async () => source,
  } as unknown as SourceRegistry;
}

const tick = async () => {
  for (let i = 0; i < 20; i += 1) {
    await Promise.resolve();
    flushSync();
  }
};

let target: HTMLElement;
let mounted: Record<string, unknown> | null = null;

beforeEach(async () => {
  document.body.innerHTML = '<div id="app"></div>';
  target = document.getElementById("app")!;
  await Promise.all([favoritesStore.init(), historyStore.init()]);
  // Both stores are process-wide, so every test starts from a known empty state.
  for (const record of historyStore.getHistory()) {
    await historyStore.deleteHistory(record.sourceId, record.toonId);
  }
  for (const record of favoritesStore.getFavorites()) {
    await favoritesStore.removeFavorite(record.sourceId, record.id);
  }
});

afterEach(() => {
  if (mounted) unmount(mounted);
  mounted = null;
});

function mountPane(
  component: Parameters<typeof mount>[0],
  store: DesktopStore,
): HTMLElement {
  mounted = mount(component, { target, props: { store } });
  flushSync();
  return target;
}

async function browseStore(
  options: Parameters<typeof fakeSource>[0] = {},
  calls: Calls = { listing: [], episodes: [], images: 0 },
): Promise<{ store: DesktopStore; calls: Calls }> {
  const store = createDesktopStore({
    registry: fakeRegistry(fakeSource(options, calls)),
    persistPreferences: false,
  });
  await store.init();
  return { store, calls };
}

// ---------------------------------------------------------------------------------------------
// BrowsePane
// ---------------------------------------------------------------------------------------------

describe("BrowsePane catalog chips", () => {
  it("renders every browse tab with the active one selected", async () => {
    const { store } = await browseStore();
    mountPane(BrowsePane, store);
    await tick();

    const chips = [...target.querySelectorAll(".chip")] as HTMLElement[];
    expect(chips.map((chip) => chip.textContent?.trim())).toEqual(["최신", "즐겨찾기", "인기"]);
    // `BrowseTab.Favorite` sits right after `LATEST` and the first tab is active on load.
    expect(chips[0].className).toContain("selected");
    expect(chips[1].className).not.toContain("selected");
    expect(chips[0].getAttribute("aria-pressed")).toBe("true");
  });

  it("calls selectTab when another chip is clicked", async () => {
    const { store, calls } = await browseStore();
    mountPane(BrowsePane, store);
    await tick();

    (target.querySelectorAll(".chip")[2] as HTMLElement).click();
    await tick();

    expect(store.activeTab?.catalogId).toBe("POPULAR");
    expect(calls.listing.at(-1)).toEqual(["POPULAR", 1]);
    const chips = [...target.querySelectorAll(".chip")] as HTMLElement[];
    expect(chips[2].className).toContain("selected");
    expect(chips[0].className).not.toContain("selected");
  });

  it("hides the chip row while searching (Kotlin `!state.isSearch` guard)", async () => {
    const { store } = await browseStore();
    mountPane(BrowsePane, store);
    await tick();

    await store.search("테스트");
    await tick();
    expect(store.isSearch).toBe(true);
    expect(target.querySelector(".tabs")).toBeNull();
  });
});

describe("BrowsePane ToonCard grid", () => {
  it("renders the cover, the title, the author line, NEW and the star toggle", async () => {
    const { store } = await browseStore();
    await store.openSeries({ ...item, sourceId: "test-src" });
    store.series = null;
    store.screen = "Browse";
    // An author is the only author data a `ToonItem` carries.
    store.items = [
      { ...item, sourceId: "test-src", artistChoices: [{ slug: "a", displayName: "작가 A" }] },
    ];
    mountPane(BrowsePane, store);
    await tick();

    const card = target.querySelector(".card") as HTMLElement;
    expect(card.querySelector(".cover-image")?.getAttribute("src")).toBe("https://src.test/42.jpg");
    expect(card.querySelector(".card-title")?.textContent?.trim()).toBe("테스트 작품");
    // `artistChoices` wins over the genre, exactly like the desktop subtitle row.
    expect(card.querySelector(".card-sub")?.textContent?.trim()).toBe("작가 A");
    expect(card.querySelector(".card-updated")?.textContent?.trim()).toBe("10.03");
    expect(card.querySelector(".badge.new")?.textContent?.trim()).toBe("NEW");
    expect(card.querySelector(".star")).toBeTruthy();
    expect((card.querySelector(".star") as HTMLElement).className).not.toContain("on");
  });

  it("falls back to the genre when no artist is exposed", async () => {
    const { store } = await browseStore();
    store.items = [{ ...item, sourceId: "test-src" }];
    mountPane(BrowsePane, store);
    await tick();
    expect(target.querySelector(".card-sub")?.textContent?.trim()).toBe("판타지");
  });

  it("shows the readProgress badge and the gold star when favorited", async () => {
    const { store } = await browseStore();
    store.items = [
      { ...item, sourceId: "test-src", isFavorite: true, readProgress: "3/12" },
    ];
    mountPane(BrowsePane, store);
    await tick();

    expect(target.querySelector(".badge.progress")?.textContent?.trim()).toBe("3/12");
    const star = target.querySelector(".star") as HTMLElement;
    expect(star.className).toContain("on");
    expect(star.getAttribute("aria-pressed")).toBe("true");
  });

  it("paints the badge the store filled from the server catalog batch", async () => {
    const store = createDesktopStore({
      registry: fakeRegistry(fakeSource({}, { listing: [], episodes: [], images: 0 })),
      syncClient: {
        fetchCatalogBatch: async (rows: readonly { sourceId: string; toonId: string }[]) =>
          rows.map((row) => ({
            sourceId: row.sourceId,
            toonId: row.toonId,
            totalEpisodes: 104,
            lastPage: 2,
            pageSize: 100,
            delta: 4,
          })),
      } as unknown as SyncClient,
      persistPreferences: false,
    });
    await catalogStore.clear();
    await store.init();
    mountPane(BrowsePane, store);
    // The catalog batch resolves after the first paint; the badge pops in on re-decoration.
    for (let index = 0; index < 3; index += 1) await tick();

    // Kotlin `BrowsePane.kt`: `item.readProgress` at `Alignment.BottomStart` of the cover.
    const badge = target.querySelector(".badge.progress") as HTMLElement;
    expect(badge).toBeTruthy();
    expect(badge.textContent?.trim()).toBe("104화");
    // It lives inside the cover box (the positioned ancestor), next to the cover image.
    expect(badge.closest(".cover")).toBeTruthy();
    expect(badge.closest(".cover")?.querySelector(".cover-image")).toBeTruthy();
  });

  it("styles the progress badge as the desktop's surface chip", () => {
    // Svelte scopes the rules, so happy-dom cannot apply them; the declaration block is asserted
    // directly against the component source instead of pretending a computed style exists.
    const source = readFileSync("src/views/BrowsePane.svelte", "utf8");
    const rule = /\.badge\.progress\s*\{([\s\S]*?)\}/.exec(source)?.[1] ?? "";
    expect(rule).toContain("bottom: 6px");
    expect(rule).toContain("left: 6px");
    expect(rule).toContain("background: rgba(24, 24, 27, 0.92)");
    expect(rule).toContain("color: #93c5fd");
    expect(rule).toContain("font-size: 11px");
    expect(rule).toContain("font-weight: 700");
    expect(rule).toContain("0.5px solid rgba(96, 165, 250, 0.35)");
    // Above the cover bitmap (`position: relative` box, no z-index of its own).
    expect(rule).toContain("z-index: 2");
  });

  it("opens the series through openSeries when a card is clicked", async () => {
    const { store } = await browseStore();
    mountPane(BrowsePane, store);
    await tick();

    (target.querySelector(".card") as HTMLElement).click();
    await tick();

    expect(store.screen).toBe("Series");
    expect(store.series?.id).toBe("42");
    expect(store.series?.sourceId).toBe("test-src");
  });

  it("toggles the favorite without opening the series", async () => {
    const { store } = await browseStore();
    await favoritesStore.removeFavorite("test-src", "42");
    mountPane(BrowsePane, store);
    await tick();

    (target.querySelector(".star") as HTMLElement).click();
    await tick();

    expect(favoritesStore.isFavorite("test-src", "42")).toBe(true);
    // `event.stopPropagation()` keeps the click from falling through to the card.
    expect(store.screen).toBe("Browse");
    expect(store.series).toBeNull();
    expect((target.querySelector(".star") as HTMLElement).className).toContain("on");
  });

  it("drops a repeated card from the grid (`distinctBy { it.listingKey() }`)", async () => {
    const { store } = await browseStore();
    store.items = [
      { ...item, sourceId: "test-src" },
      { ...item, sourceId: "test-src" },
      { ...second, sourceId: "test-src" },
    ];
    mountPane(BrowsePane, store);
    await tick();
    expect(target.querySelectorAll(".card")).toHaveLength(2);
  });
});

describe("BrowsePane pagination bar", () => {
  it("stays visible for a single-page paginated catalog, with both arrows dead", async () => {
    const { store } = await browseStore({ pageCount: 1 });
    mountPane(BrowsePane, store);
    await tick();
    expect(store.lastPage).toBe(1);
    // `activeTab.paginated !== false` is the whole guard: a paginated tab keeps its page bar so the
    // jump button (and the `N / ?` label for a source without a total) is always reachable.
    expect(target.querySelector(".pager")).toBeTruthy();
    expect(target.querySelector(".page-label")?.textContent?.trim()).toBe("1 / 1");
    const buttons = [...target.querySelectorAll(".page-button")] as HTMLButtonElement[];
    expect(buttons.map((button) => button.disabled)).toEqual([true, true]);
  });

  it("is hidden for a non-paginated catalog even when it reports several pages", async () => {
    const { store } = await browseStore({ pageCount: 3 });
    mountPane(BrowsePane, store);
    await tick();
    await store.selectTab("test-src:POPULAR");
    await tick();

    expect(store.activeTab?.paginated).toBe(false);
    expect(store.lastPage).toBe(3);
    expect(target.querySelector(".pager")).toBeNull();
  });

  it("pages through a paginated catalog with '<', 'N / M', '>'", async () => {
    const { store, calls } = await browseStore({
      pageCount: 3,
      itemsFor: (page) => [{ ...item, id: `${page}`, title: `${page}페이지 작품` }],
    });
    mountPane(BrowsePane, store);
    await tick();

    expect(target.querySelector(".page-label")?.textContent?.trim()).toBe("1 / 3");
    const buttons = [...target.querySelectorAll(".page-button")] as HTMLButtonElement[];
    expect(buttons.map((button) => button.textContent?.trim())).toEqual(["‹", "›"]);
    expect(buttons[0].getAttribute("aria-label")).toBe("이전");
    expect(buttons[1].getAttribute("aria-label")).toBe("다음");
    expect(buttons[0].disabled).toBe(true);
    expect(buttons[1].disabled).toBe(false);

    buttons[1].click();
    await tick();
    expect(store.page).toBe(2);
    expect(calls.listing.at(-1)).toEqual(["LATEST", 2]);
    expect(target.querySelector(".page-label")?.textContent?.trim()).toBe("2 / 3");

    const again = [...target.querySelectorAll(".page-button")] as HTMLButtonElement[];
    expect(again[0].disabled).toBe(false);
    again[0].click();
    await tick();
    expect(store.page).toBe(1);
    expect(target.querySelector(".page-label")?.textContent?.trim()).toBe("1 / 3");
  });

  it("labels an unknown total as '1 / ?' and still turns the next page", async () => {
    const { store, calls } = await browseStore({
      pageCount: Number.NaN, // the fake reports no usable lastPage
      itemsFor: (page) => [{ ...item, id: `${page}`, title: `${page}페이지 작품` }],
    });
    mountPane(BrowsePane, store);
    await tick();

    expect(store.lastPageKnown).toBe(false);
    expect(target.querySelector(".page-label")?.textContent?.trim()).toBe("1 / ?");
    const buttons = [...target.querySelectorAll(".page-button")] as HTMLButtonElement[];
    expect(buttons[1].disabled).toBe(false);

    buttons[1].click();
    await tick();
    expect(calls.listing.at(-1)).toEqual(["LATEST", 2]);
    expect(target.querySelector(".page-label")?.textContent?.trim()).toBe("2 / ?");
  });

  it("opens PageJumpDialog from the 'N / M' label and jumps through goToPage", async () => {
    const { store, calls } = await browseStore({
      pageCount: 5,
      itemsFor: (page) => [{ ...item, id: `${page}`, title: `${page}페이지 작품` }],
    });
    mountPane(BrowsePane, store);
    await tick();

    expect(target.querySelector("[data-testid='page-jump-dialog']")).toBeNull();
    (target.querySelector(".page-label") as HTMLButtonElement).click();
    await tick();

    expect(store.showPageJump).toBe(true);
    const dialog = target.querySelector("[data-testid='page-jump-dialog']") as HTMLElement;
    expect(dialog).toBeTruthy();
    // `PageJumpDialog` seeds the field with the current page and titles the `1 ~ N` range.
    expect(dialog.querySelector(".dialog-title")?.textContent).toBe("페이지 이동 (1 ~ 5)");
    const field = dialog.querySelector(".field-input") as HTMLInputElement;
    expect(field.value).toBe("1");

    field.value = "4";
    field.dispatchEvent(new Event("input", { bubbles: true }));
    (dialog.querySelector(".dialog-button.confirm") as HTMLButtonElement).click();
    await tick();

    expect(calls.listing.at(-1)).toEqual(["LATEST", 4]);
    expect(store.page).toBe(4);
    expect(store.showPageJump).toBe(false);
    expect(target.querySelector("[data-testid='page-jump-dialog']")).toBeNull();
    expect(target.querySelector(".page-label")?.textContent?.trim()).toBe("4 / 5");
  });

  it("closes the jump dialog without moving the page", async () => {
    const { store, calls } = await browseStore({ pageCount: 4 });
    mountPane(BrowsePane, store);
    await tick();

    (target.querySelector(".page-label") as HTMLButtonElement).click();
    await tick();
    const before = calls.listing.length;
    (target.querySelector(".dialog-button.cancel") as HTMLButtonElement).click();
    await tick();

    expect(store.showPageJump).toBe(false);
    expect(store.page).toBe(1);
    expect(calls.listing).toHaveLength(before);
  });
});

describe("BrowsePane loading, error and empty states", () => {
  it("shows a spinner while the first page is pending", async () => {
    let release = (): void => undefined;
    const pending = new Promise<void>((resolve) => {
      release = () => resolve();
    });
    const calls: Calls = { listing: [], episodes: [], images: 0 };
    const source = fakeSource({}, calls);
    const original = source.loadListing;
    source.loadListing = async (catalogId: string, page: number) => {
      await pending;
      return original(catalogId, page);
    };

    const store = createDesktopStore({
      registry: fakeRegistry(source),
      persistPreferences: false,
    });
    void store.init();
    await tick();

    mountPane(BrowsePane, store);
    await tick();
    expect(store.loading).toBe(true);
    expect(target.querySelector(".spinner")).toBeTruthy();
    expect(target.querySelector(".card")).toBeNull();

    release();
    await tick();
    expect(target.querySelector(".spinner")).toBeNull();
    expect(target.querySelector(".card")).toBeTruthy();
  });

  it("shows the error message and retries through refresh", async () => {
    let fail = true;
    const calls: Calls = { listing: [], episodes: [], images: 0 };
    const { store } = await browseStore({ failListing: () => fail }, calls);
    mountPane(BrowsePane, store);
    await tick();

    expect(store.error).toBe("목록 서버가 응답하지 않습니다.");
    expect(target.querySelector(".state-title")?.textContent?.trim()).toBe(
      "목록 서버가 응답하지 않습니다.",
    );

    fail = false;
    const retry = target.querySelector(".state-action") as HTMLButtonElement;
    expect(retry.textContent?.trim()).toBe("다시 시도");
    retry.click();
    await tick();

    expect(store.error).toBeNull();
    expect(target.querySelector(".card")).toBeTruthy();
    expect(calls.listing.length).toBeGreaterThanOrEqual(2);
  });

  it("shows the empty-favorites message and returns to the first catalog", async () => {
    const { store, calls } = await browseStore({ pageCount: 1 });
    await favoritesStore.init();
    await favoritesStore.removeFavorite("test-src", "42");
    await store.selectTab("test-src:FAVORITE");
    mountPane(BrowsePane, store);
    await tick();

    expect(store.activeTab?.kind).toBe("favorite");
    expect(store.items).toEqual([]);
    expect(target.querySelector(".state-title")?.textContent?.trim()).toBe(
      "즐겨찾기한 작품이 없습니다.",
    );

    const action = target.querySelector(".state-action") as HTMLButtonElement;
    expect(action.textContent?.trim()).toBe("보관함으로");
    action.click();
    await tick();

    // `state.browseTabs.firstOrNull { it is BrowseTab.Remote }` → the first catalog.
    expect(store.activeTab?.catalogId).toBe("LATEST");
    expect(calls.listing.at(-1)).toEqual(["LATEST", 1]);
    expect(target.querySelector(".card")).toBeTruthy();
  });
});

// ---------------------------------------------------------------------------------------------
// HistoryPane
// ---------------------------------------------------------------------------------------------

const HISTORY_ROW = {
  sourceId: "test-src",
  toonId: "42",
  title: "테스트 작품",
  thumbUrl: "https://src.test/42.jpg",
  lastWrId: "e2",
  lastEpisodeTitle: "2화",
  pageIndex: 3,
  totalPages: 10,
};

async function historyStore_(calls: Calls) {
  const store = createDesktopStore({
    registry: fakeRegistry(fakeSource({}, calls)),
    persistPreferences: false,
  });
  await store.init();
  return store;
}

describe("HistoryPane rows (HistoryItemCard)", () => {
  it("renders the thumbnail, title, chapter, relative time and progress badge", async () => {
    const calls: Calls = { listing: [], episodes: [], images: 0 };
    const store = await historyStore_(calls);
    await historyStore.recordProgress(
      HISTORY_ROW.sourceId,
      HISTORY_ROW.toonId,
      HISTORY_ROW.title,
      HISTORY_ROW.thumbUrl,
      { wrId: HISTORY_ROW.lastWrId, title: HISTORY_ROW.lastEpisodeTitle, href: "" },
      HISTORY_ROW.pageIndex,
      HISTORY_ROW.totalPages,
    );
    store.catalogTotals = {
      "test-src:42": { count: 12, lastPage: 1, pageSize: 100, delta: 0 } satisfies CatalogTotal,
    };
    await store.loadHistory();
    mountPane(HistoryPane, store);
    await tick();

    const card = target.querySelector(".history-card") as HTMLElement;
    expect(card.querySelector(".thumb-image")?.getAttribute("src")).toBe("https://src.test/42.jpg");
    expect(card.querySelector(".title")?.textContent?.trim()).toBe("테스트 작품");
    expect(card.querySelector(".episode")?.textContent?.trim()).toBe("2화");
    expect(card.querySelector(".read-at")?.textContent?.trim()).toBe("방금 전");
    // `progressLabel`: no chapter order learned yet, so the counted total is printed as "12화".
    expect(store.progressLabel(store.historyItems[0]!)).toBe("12화");
    expect(card.querySelector(".progress-badge")?.textContent?.trim()).toBe("12화");
  });

  it("prints relative time for older rows and an empty badge when nothing is counted", async () => {
    const calls: Calls = { listing: [], episodes: [], images: 0 };
    const store = await historyStore_(calls);
    await historyStore.recordProgress(
      "test-src",
      "43",
      "두 번째 작품",
      "https://src.test/43.jpg",
      { wrId: "e1", title: "1화", href: "" },
      0,
      0,
    );
    await store.loadHistory();
    const now = Date.now();
    store.historyItems = store.historyItems.map((row) => ({
      ...row,
      updatedAt: now - 3 * 60 * 60 * 1000,
    }));
    mountPane(HistoryPane, store);
    await tick();

    expect(target.querySelector(".read-at")?.textContent?.trim()).toBe("3시간 전");
    // An uncounted work has no denominator, so no badge is invented (`EpisodeListingTotal.kt`).
    expect(store.progressLabel(store.historyItems[0]!)).toBe("");
    expect(target.querySelector(".progress-badge")).toBeNull();
  });

  it("renders each series once (`distinctBy { workId().storageKey() }`)", async () => {
    const calls: Calls = { listing: [], episodes: [], images: 0 };
    const store = await historyStore_(calls);
    await store.loadHistory();
    store.historyItems = [
      { ...HISTORY_ROW, updatedAt: Date.now() },
      { ...HISTORY_ROW, updatedAt: Date.now() - 1000 },
    ];
    mountPane(HistoryPane, store);
    await tick();
    expect(target.querySelectorAll(".history-card")).toHaveLength(1);
  });

  it("opens the series through openSeriesFromHistory and keeps History as the return screen", async () => {
    const calls: Calls = { listing: [], episodes: [], images: 0 };
    const store = await historyStore_(calls);
    await historyStore.recordProgress(
      HISTORY_ROW.sourceId,
      HISTORY_ROW.toonId,
      HISTORY_ROW.title,
      HISTORY_ROW.thumbUrl,
      { wrId: HISTORY_ROW.lastWrId, title: HISTORY_ROW.lastEpisodeTitle, href: "" },
      HISTORY_ROW.pageIndex,
      HISTORY_ROW.totalPages,
    );
    await store.loadHistory();
    store.screen = "History";
    mountPane(HistoryPane, store);
    await tick();

    (target.querySelector(".history-card") as HTMLElement).click();
    await tick();

    expect(store.screen).toBe("Series");
    expect(store.series?.id).toBe("42");
    expect(store.series?.title).toBe("테스트 작품");

    await store.closeSeries();
    await tick();
    expect(store.screen).toBe("History");
  });

  it("resumes the stored chapter from the continue button", async () => {
    const calls: Calls = { listing: [], episodes: [], images: 0 };
    const store = await historyStore_(calls);
    await historyStore.recordProgress(
      HISTORY_ROW.sourceId,
      HISTORY_ROW.toonId,
      HISTORY_ROW.title,
      HISTORY_ROW.thumbUrl,
      { wrId: HISTORY_ROW.lastWrId, title: HISTORY_ROW.lastEpisodeTitle, href: "" },
      0,
      10,
    );
    await store.loadHistory();
    store.screen = "History";
    mountPane(HistoryPane, store);
    await tick();

    const resume = target.querySelector(".action.primary") as HTMLButtonElement;
    expect(resume.textContent?.trim()).toBe("이어보기");
    resume.click();
    await tick();

    expect(store.screen).toBe("Reader");
    expect(store.currentEpisode?.wrId).toBe("e2");
    expect(calls.images).toBe(1);
  });

  it("deletes a row through deleteHistory without opening the series", async () => {
    const calls: Calls = { listing: [], episodes: [], images: 0 };
    const store = await historyStore_(calls);
    await historyStore.recordProgress(
      HISTORY_ROW.sourceId,
      HISTORY_ROW.toonId,
      HISTORY_ROW.title,
      HISTORY_ROW.thumbUrl,
      { wrId: HISTORY_ROW.lastWrId, title: HISTORY_ROW.lastEpisodeTitle, href: "" },
      1,
      10,
    );
    await store.loadHistory();
    store.screen = "History";
    mountPane(HistoryPane, store);
    await tick();

    (target.querySelector(".action.danger") as HTMLButtonElement).click();
    await tick();

    expect(store.screen).toBe("History");
    expect(store.historyItems).toEqual([]);
    expect(historyStore.getProgress("test-src", "42")).toBeNull();
  });
});

describe("HistoryPane empty and loading states", () => {
  it("shows the empty-history message and the way back to browsing", async () => {
    const calls: Calls = { listing: [], episodes: [], images: 0 };
    const store = await historyStore_(calls);
    await store.loadHistory();
    store.screen = "History";
    mountPane(HistoryPane, store);
    await tick();

    expect(target.querySelector(".state-title")?.textContent?.trim()).toBe(
      "최근 본 작품이 없습니다.",
    );
    expect(target.querySelector(".history-card")).toBeNull();

    const action = target.querySelector(".state-action") as HTMLButtonElement;
    expect(action.textContent?.trim()).toBe("목록으로");
    action.click();
    await tick();
    expect(store.screen).toBe("Browse");
  });

  it("shows a spinner while the first history load is pending", async () => {
    const calls: Calls = { listing: [], episodes: [], images: 0 };
    const store = await historyStore_(calls);
    store.historyLoading = true;
    store.historyItems = [];
    mountPane(HistoryPane, store);
    await tick();

    expect(target.querySelector(".spinner")).toBeTruthy();
    expect(target.querySelector(".history-list")).toBeNull();
  });
});
