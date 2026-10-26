/**
 * Behaviour of `views/SeriesPane.svelte`, the 1:1 port of
 * `desktop/src/main/kotlin/ui/series/SeriesPane.kt`.
 *
 * The reference implementation is a Compose pane driven by a single `DesktopUiState` +
 * `DesktopViewModel` pair, so the web pane is driven by the ported store
 * (`src/state/desktop-state.svelte.ts`) instead of props. These tests therefore seed the store and
 * then assert what the pane renders:
 *
 * - the `EpisodeCard` visual contract (read / highlighted / unread) from SeriesPane.kt lines 199-349,
 * - the pagination row shown only when `episodeLastPage > 1`,
 * - the three whole-pane states (loading, error + retry, missing series + back button),
 * - the artist button, which only exists for a chapter with >= 2 artist choices.
 *
 * Styling is *not* asserted through `getComputedStyle`: happy-dom does not implement Svelte's scoped
 * styles, so the class contract (`read` / `highlighted`) is the stable thing to check.
 */

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { flushSync, mount, unmount } from "svelte";

import SeriesPane from "../src/views/SeriesPane.svelte";
import { createDesktopStore, type DesktopStore } from "../src/state/desktop-state.svelte";
import { historyStore } from "../src/data/history-store.svelte";
import type { EpisodeItem, ToonItem } from "../src/sources/types";
import type { SourceRegistry } from "../src/sources/registry";

const item: ToonItem = {
  id: "42",
  title: "테스트 작품",
  thumbUrl: "https://src.test/42.jpg",
  href: "https://src.test/series/42",
};

const episodeList: EpisodeItem[] = [
  { wrId: "e3", title: "3화 제목", href: "https://src.test/e3", date: "2026.10.03", thumbUrl: "https://src.test/t3.jpg" },
  { wrId: "e2", title: "2화 제목", href: "https://src.test/e2", date: "2026.10.02", thumbUrl: "https://src.test/t2.jpg" },
  { wrId: "e1", title: "1화 제목", href: "https://src.test/e1", date: "2026.10.01", thumbUrl: "https://src.test/t1.jpg" },
];

type Calls = { episodes: number[]; images: number; parents: unknown[][] };

function fakeSource(
  options: {
    episodes?: (page: number) => Promise<unknown>;
    pageCount?: number;
    episodePages?: Record<number, EpisodeItem[]>;
  } = {},
  calls: Calls,
) {
  const pageCount = options.pageCount ?? 1;
  return {
    id: "test-src",
    displayName: "Test Source",
    meta: { origin: "https://src.test", episodePageSize: 100, searchPlaceholder: "검색" },
    catalogs: [{ id: "LATEST", label: "최신", paginated: true }],
    async loadListing(_catalog: string, page: number) {
      return { items: [item], pageInfo: { currentPage: page, lastPage: 1 } };
    },
    async loadEpisodes(_item: ToonItem, page: number) {
      calls.episodes.push(page);
      if (options.episodes) return options.episodes(page);
      const items = options.episodePages
        ? (options.episodePages[page] ?? [])
        : episodeList.map((episode) => ({ ...episode, wrId: `${episode.wrId}-p${page}` }));
      return { items, pageInfo: { currentPage: page, lastPage: pageCount } };
    },
    async resolveImages() {
      calls.images += 1;
      return ["https://img.test/1.jpg"];
    },
    async resolveParent(parent: unknown, choice: unknown, entry: unknown) {
      calls.parents.push([parent, choice, entry]);
      return { ...item, id: `artist:${(choice as { slug: string }).slug}`, title: "다른 작가 작품" };
    },
  };
}

function fakeRegistry(source: unknown): SourceRegistry {
  return {
    init: async () => {},
    list: () => [{ id: "test-src", displayName: "Test Source", origin: "", catalogs: [], builtin: true }],
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
beforeEach(async () => {
  document.body.innerHTML = '<div id="app"></div>';
  target = document.getElementById("app")!;
  // The store reads read state from the history store; keep it empty and initialised.
  await historyStore.init();
});

async function openSeries(
  source: unknown = fakeSource({}, { episodes: [], images: 0, parents: [] }),
  options: { entryEpisodeId?: string | null; store?: DesktopStore } = {},
): Promise<DesktopStore> {
  const store =
    options.store ??
    createDesktopStore({ registry: fakeRegistry(source), persistPreferences: false });
  await store.init();
  await store.openSeries({ ...item, sourceId: "test-src", entryEpisodeId: options.entryEpisodeId ?? null });
  return store;
}

function mountPane(store: DesktopStore): HTMLElement {
  mounted = mount(SeriesPane, { target, props: { store } });
  flushSync();
  return target;
}

let mounted: Record<string, unknown> | null = null;
afterEach(() => {
  if (mounted) unmount(mounted);
  mounted = null;
});

describe("SeriesPane header and summary", () => {
  it("shows the series title and the counted episode total", async () => {
    const store = await openSeries();
    mountPane(store);
    await tick();

    expect(target.querySelector(".title")?.textContent?.trim()).toBe("테스트 작품");
    // One-page listing: the counted total equals the loaded page (`catalogTotals`, then the map).
    expect(store.catalogTotals["test-src:42"]?.count).toBe(3);
    expect(store.totalEpisodesOf("test-src:42")).toBe(3);
    expect(target.querySelector(".total-badge")?.textContent?.trim()).toBe("총 3화");
    // The badge is a scoped `.total-badge` chip now (the web shell has no Tailwind config to rely
    // on for `bg-zinc-800 …`); the visual contract lives in the component's `<style>` block.
    const badge = target.querySelector(".total-badge")!;
    expect(badge.className).not.toContain("bg-zinc-800");
    expect(badge.className).not.toContain("text-zinc-200");
    // A single-page listing has no page summary next to the total.
    expect(target.querySelector(".page-summary")).toBeNull();
    expect(target.querySelectorAll(".episode-card")).toHaveLength(3);
  });

  it("prefers a total the plugin reported over the sweep and the loaded page", async () => {
    const calls: Calls = { episodes: [], images: 0, parents: [] };
    const store = await openSeries(
      fakeSource(
        {
          episodes: async (page: number) => ({
            items: episodeList.map((episode) => ({ ...episode, wrId: `${episode.wrId}-p${page}` })),
            // The plugin states the real count instead of leaving it to a page sweep.
            total: 250,
            pageInfo: { currentPage: page, lastPage: 2 },
          }),
        },
        calls,
      ),
    );
    mountPane(store);
    await tick();

    expect(store.totalEpisodesOf("test-src:42")).toBe(250);
    expect(target.querySelector(".total-badge")?.textContent?.trim()).toBe("총 250화");
    // Multi-page listing: the header names the page being shown as well.
    expect(target.querySelector(".page-summary")?.textContent?.trim()).toBe(
      "총 250화 (1 / 2 페이지)",
    );
  });

  it("estimates the total from the page shape when the plugin reports none", async () => {
    const calls: Calls = { episodes: [], images: 0, parents: [] };
    const store = await openSeries(fakeSource({ pageCount: 3 }, calls));
    // Drop the counted sweep (and its map entry) so only the estimate can answer.
    delete store.catalogTotals["test-src:42"];
    store.totalEpisodesByWork = {};
    // Re-load page 1: 3 items on a 3-page listing estimates 3 * 3 = 9.
    await store.loadEpisodes(1);
    delete store.catalogTotals["test-src:42"];
    mountPane(store);
    await tick();

    expect(store.catalogTotals["test-src:42"]).toBeUndefined();
    expect(store.totalEpisodesOf("test-src:42")).toBe(9);
    expect(target.querySelector(".total-badge")?.textContent?.trim()).toBe("총 9화");
    expect(target.querySelector(".page-summary")?.textContent?.trim()).toBe(
      "총 9화 (1 / 3 페이지)",
    );
  });

  it("falls back to the loading episode list length when no total was counted yet", async () => {
    const calls: Calls = { episodes: [], images: 0, parents: [] };
    const store = await openSeries(fakeSource({ pageCount: 2 }, calls));
    // A failed sweep leaves the work uncounted: the header must not invent a number.
    delete store.catalogTotals["test-src:42"];
    store.totalEpisodesByWork = {};
    mountPane(store);
    await tick();

    expect(store.catalogTotals["test-src:42"]).toBeUndefined();
    expect(store.totalEpisodesOf("test-src:42")).toBeNull();
    expect(target.querySelector(".total-badge")?.textContent?.trim()).toBe("총 3화");
  });

  it("shows the no-series error and a back button when state.series is null", async () => {
    const store = createDesktopStore({
      registry: fakeRegistry(fakeSource({}, { episodes: [], images: 0, parents: [] })),
      persistPreferences: false,
    });
    await store.init();
    // `openSeries` was never called, so `state.series` is null.
    expect(store.series).toBeNull();
    store.screen = "Series";
    mountPane(store);

    expect(target.querySelector(".state-title")?.textContent?.trim()).toBe("작품 정보가 없습니다.");
    expect(target.querySelector(".state-action")?.textContent?.trim()).toBe("목록으로");
    expect(target.querySelector(".episode-list")).toBeNull();
  });

  it("returns to the list from the no-series error", async () => {
    const store = createDesktopStore({
      registry: fakeRegistry(fakeSource({}, { episodes: [], images: 0, parents: [] })),
      persistPreferences: false,
    });
    await store.init();
    store.screen = "Series";
    mountPane(store);

    (target.querySelector(".state-action") as HTMLButtonElement).click();
    await tick();
    expect(store.screen).toBe("Browse");
  });
});

describe("SeriesPane episode cards (SeriesPane.kt EpisodeCard)", () => {
  it("renders unread, read and highlighted cards in the three §visual states", async () => {
    const calls: Calls = { episodes: [], images: 0, parents: [] };
    const store = await openSeries(
      fakeSource({}, calls),
      { entryEpisodeId: "e2-p1" },
    );
    // Mark the oldest chapter read with a timestamp, like `enrichEpisodePage` does.
    store.episodes = store.episodes.map((episode) =>
      episode.wrId === "e1-p1"
        ? { ...episode, isRead: true, readAt: new Date(2026, 9, 1).getTime() }
        : episode,
    );
    mountPane(store);
    await tick();

    const cards = [...target.querySelectorAll(".episode-card")] as HTMLElement[];
    expect(cards).toHaveLength(3);

    // unread (no class), highlighted (e2-p1 entered from the listing), read (e1-p1).
    expect(cards[0].className).not.toContain("read");
    expect(cards[0].className).not.toContain("highlighted");
    expect(cards[1].className).toContain("highlighted");
    expect(cards[1].className).not.toContain("read");
    expect(cards[2].className).toContain("read");

    // Thumbnail, title and date of the first row.
    expect(cards[0].querySelector(".thumb-image")?.getAttribute("src")).toBe("https://src.test/t3.jpg");
    expect(cards[0].querySelector(".episode-title")?.textContent?.trim()).toBe("3화 제목");
    expect(cards[0].querySelector(".episode-date")?.textContent?.trim()).toBe("2026.10.03");

    // Read badge chip + formatted read date (`UpdateDates.formatEpoch` → yy.MM.dd).
    expect(cards[2].querySelector(".read-badge")?.textContent?.trim()).toBe("읽음");
    expect(cards[2].querySelector(".read-date")?.textContent?.trim()).toBe("26.10.01");
    expect(cards[0].querySelector(".read-badge")).toBeNull();
  });

  it("treats a chapter in the history read set as read even without enrichment", async () => {
    await historyStore.recordProgress(
      "test-src",
      "42",
      "테스트 작품",
      "",
      // The fake source stamps page-suffixed ids (`e2` → `e2-p1`); the read set must match those.
      { ...episodeList[1], wrId: "e2-p1" },
      3,
      10,
    );
    const store = await openSeries();
    mountPane(store);
    await tick();

    const cards = [...target.querySelectorAll(".episode-card")] as HTMLElement[];
    const read = cards.filter((card) => card.className.includes("read"));
    expect(read).toHaveLength(1);
    expect(read[0].textContent).toContain("2화 제목");
    expect(read[0].querySelector(".read-badge")?.textContent?.trim()).toBe("읽음");
    expect(read[0].querySelector(".read-date")?.textContent?.trim()).toBeTruthy();
  });

  it("opens the reader through desktopState.openEpisode", async () => {
    const calls: Calls = { episodes: [], images: 0, parents: [] };
    const store = await openSeries(fakeSource({}, calls));
    mountPane(store);
    await tick();

    (target.querySelectorAll(".episode-card")[0] as HTMLElement).click();
    await tick();

    expect(store.screen).toBe("Reader");
    expect(store.currentEpisode?.wrId).toBe("e3-p1");
    expect(calls.images).toBe(1);
  });

  it("does not render an artist button for a single-artist chapter", async () => {
    const store = await openSeries();
    mountPane(store);
    await tick();
    expect(target.querySelector(".artist-button")).toBeNull();
  });

  it("opens the artist picker, resolves the parent and re-opens it as a series", async () => {
    const calls: Calls = { episodes: [], images: 0, parents: [] };
    const source = fakeSource({}, calls);
    const store = await openSeries(source);
    const choices = [
      { slug: "a", displayName: "작가 A" },
      { slug: "b", displayName: "작가 B" },
    ];
    store.episodes = store.episodes.map((episode) =>
      episode.wrId === "e3-p1" ? { ...episode, artistChoices: choices } : episode,
    );
    mountPane(store);
    await tick();

    const buttons = [...target.querySelectorAll(".artist-button")] as HTMLElement[];
    expect(buttons).toHaveLength(1);
    buttons[0].click();
    flushSync();

    const options = [...target.querySelectorAll(".artist-option")] as HTMLElement[];
    expect(options.map((node) => node.textContent?.trim())).toEqual(["작가 A", "작가 B"]);
    options[1].click();
    await tick();

    // `pickArtist` passes the entry episode id, then opens the resolved parent series.
    expect(calls.parents).toHaveLength(1);
    expect((calls.parents[0][2] as string)).toBe("e3-p1");
    expect(store.series?.id).toBe("artist:b");
    expect(store.series?.title).toBe("다른 작가 작품");
    expect(target.querySelector(".dialog")).toBeNull();
  });
});

describe("SeriesPane pagination", () => {
  it("hides the pagination row for a single-page listing", async () => {
    const store = await openSeries();
    mountPane(store);
    await tick();
    expect(target.querySelector(".pagination")).toBeNull();
  });

  it("pages through a multi-page listing with '<', 'N / M', '>'", async () => {
    const calls: Calls = { episodes: [], images: 0, parents: [] };
    const store = await openSeries(fakeSource({ pageCount: 3 }, calls));
    mountPane(store);
    await tick();

    expect(target.querySelector(".page-label")?.textContent?.trim()).toBe("1 / 3");
    const buttons = [...target.querySelectorAll(".page-button")] as HTMLButtonElement[];
    expect(buttons[0].disabled).toBe(true);
    expect(buttons[1].disabled).toBe(false);
    expect(buttons[0].getAttribute("aria-label")).toBe("이전");
    expect(buttons[1].getAttribute("aria-label")).toBe("다음");

    buttons[1].click();
    await tick();
    expect(store.episodePage).toBe(2);
    expect(target.querySelector(".page-label")?.textContent?.trim()).toBe("2 / 3");
    expect(calls.episodes).toContain(2);

    const again = [...target.querySelectorAll(".page-button")] as HTMLButtonElement[];
    expect(again[0].disabled).toBe(false);
    again[0].click();
    await tick();
    expect(target.querySelector(".page-label")?.textContent?.trim()).toBe("1 / 3");
  });

  it("opens the page-jump dialog from the 'N / M' button (Desktop SeriesPane 165-171)", async () => {
    const store = await openSeries(fakeSource({ pageCount: 3 }, { episodes: [], images: 0, parents: [] }));
    mountPane(store);
    await tick();

    const label = target.querySelector(".page-label") as HTMLButtonElement;
    expect(label.tagName).toBe("BUTTON");
    expect(label.getAttribute("aria-haspopup")).toBe("dialog");
    expect(store.showPageJump).toBe(false);

    label.click();
    await tick();
    expect(store.showPageJump).toBe(true);

    // `togglePageJump(true)` is idempotent: the dialog itself owns the dismissal.
    store.togglePageJump(false);
    flushSync();
    expect(store.showPageJump).toBe(false);
  });
});

describe("SeriesPane loading and error states", () => {
  it("shows a loading spinner while the first episode page is pending", async () => {
    const calls: Calls = { episodes: [], images: 0, parents: [] };
    let release = (): void => undefined;
    const pending = new Promise<void>((resolve) => {
      release = () => resolve();
    });
    const source = fakeSource({
      async episodes() {
        await pending;
        return { items: episodeList, pageInfo: { currentPage: 1, lastPage: 1 } };
      },
    }, calls);

    const store = createDesktopStore({ registry: fakeRegistry(source), persistPreferences: false });
    await store.init();
    void store.openSeries({ ...item, sourceId: "test-src" });
    await tick();

    mountPane(store);
    await tick();
    expect(store.episodeLoading).toBe(true);
    expect(target.querySelector(".spinner")).toBeTruthy();
    expect(target.querySelector(".episode-list")).toBeNull();

    release();
    await tick();
    expect(target.querySelector(".spinner")).toBeNull();
    expect(target.querySelectorAll(".episode-card")).toHaveLength(3);
  });

  it("shows the episode error and retries through desktopState.loadEpisodes", async () => {
    const calls: Calls = { episodes: [], images: 0, parents: [] };
    let fail = true;
    const source = fakeSource({
      async episodes() {
        if (fail) throw new Error("회차 서버가 응답하지 않습니다.");
        return { items: episodeList, pageInfo: { currentPage: 1, lastPage: 1 } };
      },
    }, calls);

    const store = createDesktopStore({ registry: fakeRegistry(source), persistPreferences: false });
    await store.init();
    await store.openSeries({ ...item, sourceId: "test-src" });
    mountPane(store);
    await tick();

    expect(store.episodeError).toBe("회차 서버가 응답하지 않습니다.");
    expect(target.querySelector(".state-title")?.textContent?.trim()).toBe(
      "회차 서버가 응답하지 않습니다.",
    );

    fail = false;
    const retry = target.querySelector(".state-action") as HTMLButtonElement;
    expect(retry.textContent).toContain("다시 시도");
    retry.click();
    await tick();

    expect(calls.episodes.length).toBeGreaterThanOrEqual(2);
    expect(store.episodeError).toBeNull();
    expect(target.querySelectorAll(".episode-card")).toHaveLength(3);
  });
});
