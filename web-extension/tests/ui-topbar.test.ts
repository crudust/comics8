/**
 * Behaviour of the shell top bar (`src/ui/TopBar.svelte` + `src/ui/SourceTitleDropdown.svelte`),
 * the ports of `desktop/src/main/kotlin/ui/components/TopBar.kt` (lines 89-460).
 *
 * The bar is presentational: it reads a `DesktopStore` and calls the view-model methods. These tests
 * therefore assert *wiring* — which row each screen renders, which store method a click reaches, and
 * the two details that are easy to get wrong: the `inOverlay` background and the reader option
 * chips, which the Kotlin source gates on `viewMode`.
 */

import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushSync, mount, unmount } from "svelte";

import { DesktopStore } from "../src/state/desktop-state.svelte";
import type { HistoryRecord } from "../src/data/history-store.svelte";
import type { SourceSummary } from "../src/sources/registry";
import SourceTitleDropdown from "../src/ui/SourceTitleDropdown.svelte";
import TopBar from "../src/ui/TopBar.svelte";

const tick = async () => {
  for (let i = 0; i < 20; i++) {
    await Promise.resolve();
    flushSync();
  }
};

const source = (id: string, displayName: string): SourceSummary => ({
  id,
  displayName,
  origin: "",
  catalogs: [],
  builtin: false,
});

const historyRow = (): HistoryRecord => ({
  sourceId: "test-src",
  toonId: "42",
  title: "테스트 작품",
  thumbUrl: "",
  lastWrId: "e2",
  lastEpisodeTitle: "2화",
  pageIndex: 3,
  totalPages: 10,
  updatedAt: 1,
});

const texts = (nodes: NodeListOf<Element>): (string | undefined)[] =>
  [...nodes].map((node) => node.textContent?.trim());

/** A store with one active plugin source and nothing loaded (no registry, no storage). */
function makeStore(screen: DesktopStore["screen"] = "Browse"): DesktopStore {
  const store = new DesktopStore({ persistPreferences: false });
  store.installedSources = [source("test-src", "Test Source"), source("other", "Other")];
  store.activeSourceId = "test-src";
  store.screen = screen;
  return store;
}

let target: HTMLElement;
beforeEach(() => {
  if (typeof Animation !== "undefined" && Animation.prototype) {
    Animation.prototype.cancel = () => {};
  }
  document.body.innerHTML = '<div id="app"></div>';
  target = document.getElementById("app")!;
});

describe("TopBar — Browse row", () => {
  it("shows the source dropdown title and the history / search / refresh actions", async () => {
    const store = makeStore();
    const openHistory = vi.spyOn(store, "openHistory").mockResolvedValue();
    const toggleSearchBar = vi.spyOn(store, "toggleSearchBar");
    const refresh = vi.spyOn(store, "refresh").mockResolvedValue();

    const app = mount(TopBar, { target, props: { store } });
    await tick();

    // Browse renders the dropdown instead of a plain title, and has no back button.
    expect(target.querySelector(".title")).toBeNull();
    expect(target.querySelector(".trigger .label")?.textContent).toBe("Test Source");

    // Browse row: download (if writable), history, search
    const buttons = [...target.querySelectorAll<HTMLButtonElement>(".row .icon-button")];
    const labels = buttons.map((button) => button.getAttribute("aria-label"));
    expect(labels).toContain("히스토리");
    expect(labels).toContain("검색");
    const historyBtn = buttons.find((b) => b.getAttribute("aria-label") === "히스토리")!;
    const searchBtn = buttons.find((b) => b.getAttribute("aria-label") === "검색")!;
    historyBtn.click();
    searchBtn.click();
    await tick();

    expect(openHistory).toHaveBeenCalledTimes(1);
    expect(toggleSearchBar).toHaveBeenCalledTimes(1);
    unmount(app);
  });

  it("slides the search field down and submits it through store.search", async () => {
    const store = makeStore();
    store.searchExpanded = true;
    const search = vi.spyOn(store, "search").mockResolvedValue();

    const app = mount(TopBar, { target, props: { store } });
    await tick();

    const input = target.querySelector<HTMLInputElement>(".search-field input")!;
    expect(input).not.toBeNull();
    // `displaySearchPlaceholder` falls back to the generic hint when the source has no metadata.
    expect(input.placeholder).toBe("제목 검색");

    input.value = "마법";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    flushSync();
    expect(store.searchInput).toBe("마법");

    input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
    await tick();
    expect(search).toHaveBeenCalledWith("마법");

    target.querySelector<HTMLElement>(".clear-button")!.click();
    await tick();
    expect(store.searchInput).toBe("");
    unmount(app);
  });
});

describe("TopBar — History row", () => {
  it("offers clear-all only while the list has rows", async () => {
    const store = makeStore("History");
    const clearAllHistory = vi.spyOn(store, "clearAllHistory").mockResolvedValue();
    const closeHistory = vi.spyOn(store, "closeHistory");

    const app = mount(TopBar, { target, props: { store } });
    await tick();

    expect(target.querySelector(".title")?.textContent).toBe("최근 본 작품");
    // Back only: `if (state.historyItems.isNotEmpty())` hides the delete action.
    expect(target.querySelectorAll(".row .icon-button")).toHaveLength(1);

    store.historyItems = [historyRow()];
    flushSync();
    const buttons = [...target.querySelectorAll<HTMLButtonElement>(".row .icon-button")];
    expect(buttons).toHaveLength(2);
    buttons[1]!.click();
    expect(clearAllHistory).toHaveBeenCalledTimes(1);

    buttons[0]!.click();
    expect(closeHistory).toHaveBeenCalledTimes(1);
    unmount(app);
  });
});

describe("TopBar — Series row", () => {
  it("resumes from history or starts from the first chapter", async () => {
    const store = makeStore("Series");
    store.series = { id: "42", title: "테스트 작품", thumbUrl: "", href: "" };
    store.seriesHistory = historyRow();
    const resumeSeries = vi.spyOn(store, "resumeSeries").mockResolvedValue();
    const openFirstEpisode = vi.spyOn(store, "openFirstEpisode").mockResolvedValue();

    const app = mount(TopBar, { target, props: { store } });
    await tick();

    expect(target.querySelector(".title")?.textContent).toBe("테스트 작품");
    const resume = target.querySelector<HTMLElement>(".resume")!;
    expect(resume.textContent?.trim()).toContain("이어보기");
    resume.click();
    expect(resumeSeries).toHaveBeenCalledTimes(1);

    store.seriesHistory = null;
    flushSync();
    const first = target.querySelector<HTMLElement>(".resume")!;
    expect(first.textContent?.trim()).toContain("처음부터 읽기");
    first.click();
    expect(openFirstEpisode).toHaveBeenCalledTimes(1);
    unmount(app);
  });

  it("prints the saved progress inside the resume label, like actionResumeSeriesWithProgress", async () => {
    const store = makeStore("Series");
    store.series = { id: "42", title: "테스트 작품", thumbUrl: "", href: "" };
    store.seriesHistory = historyRow();
    // A counted sweep gives `progressLabel` its denominator; the row records chapter 2 of 12.
    store.catalogTotals = { "test-src:42": { count: 12, lastPage: 1, pageSize: 100, delta: 0 } };
    store.totalEpisodesByWork = { "test-src:42": 12 };

    const app = mount(TopBar, { target, props: { store } });
    await tick();

    const label = target.querySelector<HTMLElement>(".resume")!.textContent?.trim();
    expect(label).toBe(`이어보기 (${store.progressLabel(store.seriesHistory)})`);
    // `formatProgressBadge`: `LATEST_EPISODE` prints `N/total` once an order is known, else the total.
    expect(label).toMatch(/^이어보기 \((\d+\/12|12화)\)$/);
    expect(target.querySelector<HTMLElement>(".resume")!.title).toBe(label);
    unmount(app);
  });

  it("checks the active sort order and applies the picked one", async () => {
    const store = makeStore("Series");
    store.series = { id: "42", title: "테스트 작품", thumbUrl: "", href: "" };
    store.episodeSortOrder = "NAME_ASC";
    const setEpisodeSortOrder = vi.spyOn(store, "setEpisodeSortOrder");

    const app = mount(TopBar, { target, props: { store } });
    await tick();

    // The sort button
    target.querySelector<HTMLElement>("[aria-label='회차 정렬']")!.click();
    await tick();

    const options = [...target.querySelectorAll<HTMLElement>(".sort-menu [role=menuitemradio]")];
    expect(texts(options as unknown as NodeListOf<Element>)).toEqual([
      "기본순",
      "이름 오름차순",
      "이름 내림차순",
      "최신순",
      "오래된순",
    ]);
    expect(options.map((node) => node.getAttribute("aria-checked"))).toEqual([
      "false",
      "true",
      "false",
      "false",
      "false",
    ]);
    expect(options[1]!.querySelector("svg")).not.toBeNull();

    options[3]!.click();
    await tick();
    expect(setEpisodeSortOrder).toHaveBeenCalledWith("DATE_DESC");
    expect(target.querySelector(".sort-menu")).toBeNull();
    unmount(app);
  });

  it("fills the star gold while the series is favorited", async () => {
    const store = makeStore("Series");
    store.series = { id: "42", title: "테스트 작품", thumbUrl: "", href: "" };
    const toggleFavorite = vi.spyOn(store, "toggleFavorite").mockResolvedValue();

    const app = mount(TopBar, { target, props: { store } });
    await tick();

    const star = () => target.querySelector<SVGElement>(".icon-button.favorite svg")!;
    expect(star().getAttribute("fill")).toBe("none");
    target.querySelector<HTMLElement>(".icon-button.favorite")!.click();
    expect(toggleFavorite).toHaveBeenCalledTimes(1);

    // `MonochromeTheme.Gold` — the filled vs outlined star is the only signal of the state.
    store.seriesFavorited = true;
    flushSync();
    expect(star().getAttribute("fill")).toBe("#FFB800");
    unmount(app);
  });

  it("shows the authoritative episode total next to the series title", async () => {
    const store = makeStore("Series");
    store.series = { id: "42", title: "테스트 작품", thumbUrl: "", href: "" };
    store.totalEpisodesByWork = { "test-src:42": 250 };

    const app = mount(TopBar, { target, props: { store } });
    await tick();

    expect(target.querySelector(".title")?.textContent).toBe("테스트 작품");
    expect(target.querySelector(".series-total")?.textContent?.trim()).toBe("총 250화");
    unmount(app);
  });

  it("falls back to the counted sweep, then to the loaded page, and stays silent when unknown", async () => {
    const store = makeStore("Series");
    store.series = { id: "42", title: "테스트 작품", thumbUrl: "", href: "" };

    const app = mount(TopBar, { target, props: { store } });
    await tick();
    // Nothing loaded yet: no total, no empty badge.
    expect(target.querySelector(".series-total")).toBeNull();

    store.catalogTotals = { "test-src:42": { count: 13, lastPage: 3, pageSize: 5, delta: -2 } };
    flushSync();
    expect(target.querySelector(".series-total")?.textContent?.trim()).toBe("총 13화");

    store.catalogTotals = {};
    store.episodes = [
      { wrId: "e2", title: "2화", href: "" },
      { wrId: "e1", title: "1화", href: "" },
    ];
    flushSync();
    expect(target.querySelector(".series-total")?.textContent?.trim()).toBe("총 2화");

    // A reported total outranks both fallbacks.
    store.totalEpisodesByWork = { "test-src:42": 199 };
    flushSync();
    expect(target.querySelector(".series-total")?.textContent?.trim()).toBe("총 199화");
    unmount(app);
  });

  it("does not print the total on the other screens", async () => {
    const store = makeStore("History");
    store.series = { id: "42", title: "테스트 작품", thumbUrl: "", href: "" };
    store.totalEpisodesByWork = { "test-src:42": 250 };

    const app = mount(TopBar, { target, props: { store } });
    await tick();

    expect(target.querySelector(".series-total")).toBeNull();
    unmount(app);
  });
});

describe("TopBar — Reader row", () => {
  it("uses the translucent background and gates the option chips on the view mode", async () => {
    const store = makeStore("Reader");
    store.currentEpisode = { wrId: "e1", title: "1화 제목", href: "" };
    store.viewMode = "PAGE";
    store.splitMode = "FIT";
    store.readDirection = "RIGHT_TO_LEFT";

    const app = mount(TopBar, { target, props: { store, inOverlay: true } });
    await tick();

    // `surface.copy(alpha = 0.94f)` in overlay mode; the docked bar stays opaque (`#000000`).
    expect(target.querySelector(".top-bar")!.classList.contains("overlay")).toBe(true);
    expect(target.querySelector(".title")?.textContent).toBe("1화 제목");
    expect(texts(target.querySelectorAll(".chip"))).toEqual([
      "웹툰",
      "한쪽",
      "양면",
      "우→좌",
      "좌→우",
      "Fit",
      "Slice",
    ]);
    unmount(app);
  });

  it("calls the view mode / direction / split setters and hides the gated rows", async () => {
    const store = makeStore("Reader");
    store.currentEpisode = { wrId: "e1", title: "1화 제목", href: "" };
    store.viewMode = "PAGE";
    const setViewMode = vi.spyOn(store, "setViewMode");
    const setReadDirection = vi.spyOn(store, "setReadDirection");
    const setSplitMode = vi.spyOn(store, "setSplitMode");

    const app = mount(TopBar, { target, props: { store } });
    await tick();

    const chips = (selector: string) => target.querySelectorAll<HTMLElement>(selector);
    chips(".chip.view-mode")[2]!.click();
    chips(".chip.direction")[1]!.click();
    chips(".chip.split-mode")[1]!.click();
    expect(setViewMode).toHaveBeenCalledWith("DUAL");
    expect(setReadDirection).toHaveBeenCalledWith("LEFT_TO_RIGHT");
    expect(setSplitMode).toHaveBeenCalledWith("SLICE");

    // `if (state.viewMode != ViewMode.SCROLL)` / `if (state.viewMode == ViewMode.PAGE)`.
    store.viewMode = "SCROLL";
    flushSync();
    expect(texts(target.querySelectorAll(".chip"))).toEqual(["웹툰", "한쪽", "양면"]);

    store.viewMode = "DUAL";
    flushSync();
    expect(texts(target.querySelectorAll(".chip"))).toEqual(["웹툰", "한쪽", "양면", "우→좌", "좌→우"]);
    unmount(app);
  });
});

describe("TopBar — Settings row", () => {
  it("titles itself 설정 and closes back to browse", async () => {
    const store = makeStore("Settings");
    const closeSettings = vi.spyOn(store, "closeSettings");

    const app = mount(TopBar, { target, props: { store } });
    await tick();

    expect(target.querySelector(".title")?.textContent).toBe("설정");
    target.querySelector<HTMLElement>(".row .icon-button")!.click();
    expect(closeSettings).toHaveBeenCalledTimes(1);
    unmount(app);
  });
});

describe("SourceTitleDropdown", () => {
  it("lists every installed source, marks the active one and switches on click", async () => {
    const store = makeStore();
    const setActiveSource = vi.spyOn(store, "setActiveSource").mockResolvedValue();

    const app = mount(SourceTitleDropdown, { target, props: { store } });
    await tick();

    target.querySelector<HTMLElement>(".trigger")!.click();
    await tick();

    const items = [...target.querySelectorAll<HTMLElement>(".menu [role=menuitemradio]")];
    expect(texts(items as unknown as NodeListOf<Element>)).toEqual(["Test Source", "Other"]);
    expect(items.map((node) => node.getAttribute("aria-checked"))).toEqual(["true", "false"]);
    // Only the active row carries the check icon.
    expect(items[0]!.querySelector(".item-check")).not.toBeNull();
    expect(items[1]!.querySelector(".item-check")).toBeNull();

    items[1]!.click();
    await tick();
    expect(setActiveSource).toHaveBeenCalledWith("other");
    expect(target.querySelector(".menu")).toBeNull();
    unmount(app);
  });

  it("separates the source list from 소스 관리 / 설정 and reports both actions", async () => {
    const store = makeStore();
    const onOpenSourceManager = vi.fn();
    const onOpenSettings = vi.fn();

    const app = mount(SourceTitleDropdown, {
      target,
      props: { store, onOpenSourceManager, onOpenSettings },
    });
    await tick();

    target.querySelector<HTMLElement>(".trigger")!.click();
    await tick();
    expect(target.querySelector(".menu .divider")).not.toBeNull();

    const actions = [...target.querySelectorAll<HTMLElement>(".menu [role=menuitem]")];
    expect(texts(actions as unknown as NodeListOf<Element>)).toEqual(["소스 관리", "설정"]);

    actions[0]!.click();
    expect(onOpenSourceManager).toHaveBeenCalledTimes(1);
    await tick();
    expect(target.querySelector(".menu")).toBeNull();

    target.querySelector<HTMLElement>(".trigger")!.click();
    await tick();
    [...target.querySelectorAll<HTMLElement>(".menu [role=menuitem]")][1]!.click();
    expect(onOpenSettings).toHaveBeenCalledTimes(1);
    unmount(app);
  });

  it("closes on Escape and labels the local source like the desktop storage tab", async () => {
    const store = makeStore();
    store.activeSourceId = "local";
    store.installedSources = [source("local", "로컬 저장소")];

    const app = mount(SourceTitleDropdown, { target, props: { store } });
    await tick();
    expect(target.querySelector(".trigger .label")?.textContent).toBe("로컬 저장소");

    target.querySelector<HTMLElement>(".trigger")!.click();
    await tick();
    expect(target.querySelector(".menu")).not.toBeNull();

    window.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
    await tick();
    expect(target.querySelector(".menu")).toBeNull();
    unmount(app);
  });
});
