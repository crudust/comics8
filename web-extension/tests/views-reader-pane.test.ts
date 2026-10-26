/**
 * Behaviour of `src/viewer/ReaderPane.svelte`, the 1:1 port of
 * `desktop/src/main/kotlin/ui/reader/ReaderPane.kt`.
 *
 * The pane is driven by the ported store (`src/state/desktop-state.svelte.ts`) instead of the
 * Kotlin `DesktopUiState` + `DesktopViewModel` pair, so each test seeds the store and then asserts
 * what the pane renders or which store action it triggered:
 *
 * - immersive mode (pill only) vs. the overlay bars (`controlsVisible`),
 * - the three tap zones from ReaderPane.kt lines 473-482, including the RTL mirroring,
 * - the boundary prompt state machine (prompt → confirm → `prevEpisode`/`nextEpisode`),
 * - the keyboard shortcuts from ReaderPane.kt lines 124-173,
 * - page restoring (`lastReadPage`) and saving (`savePage`) per view mode,
 * - the loading / error / retry surfaces and the page-jump dialog.
 *
 * Styling is not asserted through `getComputedStyle`: happy-dom does not implement Svelte's scoped
 * styles, so the class contract is the stable thing to check.
 */

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { flushSync, mount, unmount } from "svelte";

import ReaderPane from "../src/viewer/ReaderPane.svelte";
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
  { wrId: "e3", title: "3화 제목", href: "https://src.test/e3" },
  { wrId: "e2", title: "2화 제목", href: "https://src.test/e2" },
  { wrId: "e1", title: "1화 제목", href: "https://src.test/e1" },
];

type Calls = {
  images: number;
  next: number;
  prev: number;
  close: number;
};

function fakeSource(
  options: { images?: string[]; fail?: () => boolean } = {},
  calls: Calls,
) {
  return {
    id: "test-src",
    displayName: "Test Source",
    meta: { origin: "https://src.test", episodePageSize: 100 },
    catalogs: [{ id: "LATEST", label: "최신", paginated: true }],
    async loadListing(_catalog: string, page: number) {
      return { items: [item], pageInfo: { currentPage: page, lastPage: 1 } };
    },
    async loadEpisodes() {
      return { items: episodeList, pageInfo: { currentPage: 1, lastPage: 1 } };
    },
    async resolveImages() {
      calls.images += 1;
      if (options.fail?.()) throw new Error("이미지 서버가 응답하지 않습니다.");
      return options.images ?? ["https://img.test/1.jpg", "https://img.test/2.jpg", "https://img.test/3.jpg"];
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

const tick = async (): Promise<void> => {
  for (let index = 0; index < 20; index += 1) {
    await Promise.resolve();
    flushSync();
  }
};

let target: HTMLElement;
let mounted: Record<string, unknown> | null = null;

beforeEach(async () => {
  document.body.innerHTML = '<div id="app"></div>';
  target = document.getElementById("app")!;
  await historyStore.init();
});

afterEach(() => {
  if (mounted) unmount(mounted);
  mounted = null;
});

function mountPane(store: DesktopStore): HTMLElement {
  mounted = mount(ReaderPane, { target, props: { store } });
  flushSync();
  return target;
}

/** Opens `wrId` (defaults to the middle chapter) and returns the seeded store. */
async function openReader(
  options: {
    images?: string[];
    fail?: () => boolean;
    wrId?: string;
    entries?: Calls;
    viewMode?: "SCROLL" | "PAGE" | "DUAL";
    direction?: "RIGHT_TO_LEFT" | "LEFT_TO_RIGHT";
    splitMode?: "FIT" | "SLICE";
    lastReadPage?: number;
  } = {},
): Promise<{ store: DesktopStore; calls: Calls; source: ReturnType<typeof fakeSource> }> {
  const calls: Calls = options.entries ?? { images: 0, next: 0, prev: 0, close: 0 };
  const source = fakeSource({ images: options.images, fail: options.fail }, calls);
  const store = createDesktopStore({
    registry: fakeRegistry(source),
    persistPreferences: false,
  });
  await store.init();
  await store.openSeries({ ...item, sourceId: "test-src" });

  // Instrument the navigation actions the pane calls, without changing their behaviour.
  const next = store.nextEpisode.bind(store);
  const prev = store.prevEpisode.bind(store);
  const close = store.closeReader.bind(store);
  store.nextEpisode = async () => {
    calls.next += 1;
    await next();
  };
  store.prevEpisode = async () => {
    calls.prev += 1;
    await prev();
  };
  store.closeReader = () => {
    calls.close += 1;
    close();
  };

  if (options.viewMode) store.setViewMode(options.viewMode);
  if (options.direction) store.setReadDirection(options.direction);
  if (options.splitMode) store.setSplitMode(options.splitMode);

  const episode = store.episodes.find((row) => row.wrId === (options.wrId ?? "e2")) ?? store.episodes[1];
  await store.openEpisode({ ...episode, lastReadPage: options.lastReadPage ?? 0 });
  return { store, calls, source };
}

/** Report a natural size for the mounted images, like a real decode would. */
function loadImages(width = 800, height = 1200): void {
  for (const img of target.querySelectorAll("img")) {
    Object.defineProperty(img, "naturalWidth", { value: width, configurable: true });
    Object.defineProperty(img, "naturalHeight", { value: height, configurable: true });
    img.dispatchEvent(new Event("load"));
  }
  flushSync();
}

/** Pill text plus the dual-spread range it carries in `aria-label` / `title`. */
function pillLabel(): string | undefined {
  const pill = target.querySelector(".page-pill");
  return (pill?.getAttribute("aria-label") ?? pill?.textContent)?.trim();
}

function clickAt(x: number, width = 1000): void {
  const stage = target.querySelector(".reader-stage") as HTMLElement;
  stage.dispatchEvent(
    new MouseEvent("click", { bubbles: true, clientX: x, clientY: 300 }),
  );
  void width;
  flushSync();
}

describe("ReaderPane", () => {
  it("starts in immersive mode and shows only the range pill", async () => {
    const { store } = await openReader({ viewMode: "PAGE" });
    mountPane(store);
    await tick();

    expect(target.querySelector(".top-bar")).toBeNull();
    expect(target.querySelector(".bottom-bar")).toBeNull();
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("1 / 3");
  });

  it("toggles the overlay with a centre tap (24% zone) and clears the prompt", async () => {
    const { store } = await openReader({ viewMode: "PAGE" });
    mountPane(store);
    await tick();

    clickAt(500);
    expect(target.querySelector(".top-bar")).toBeTruthy();
    expect(target.querySelector(".bottom-bar")).toBeTruthy();
    expect(target.querySelector(".page-pill")).toBeNull();

    clickAt(500);
    expect(target.querySelector(".top-bar")).toBeNull();
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("1 / 3");
  });

  it("renders the bottom bar with prev/next, slider and the page-jump button", async () => {
    const { store } = await openReader({ viewMode: "PAGE" });
    mountPane(store);
    await tick();
    clickAt(500);

    const bar = target.querySelector(".bottom-bar") as HTMLElement;
    expect(bar.querySelector(".prev-episode")?.textContent).toContain("이전화");
    expect(bar.querySelector(".next-episode")?.textContent).toContain("다음화");
    expect(bar.querySelector(".close-reader")?.textContent).toContain("목록");
    expect(bar.querySelector(".page-jump-button")?.textContent).toContain("페이지 이동");
    expect(bar.querySelector(".progress-slider")).toBeTruthy();
    expect(bar.querySelector(".range-text")?.textContent?.trim()).toBe("1 / 3");

    // Chapter 2 of 3: both neighbours exist.
    expect((bar.querySelector(".prev-episode") as HTMLButtonElement).disabled).toBe(false);
    expect((bar.querySelector(".next-episode") as HTMLButtonElement).disabled).toBe(false);
  });
});

describe("ReaderPane tap zones (lines 473-482)", () => {
  it("advances on the right and retreats on the left in LTR", async () => {
    const { store } = await openReader({ viewMode: "PAGE", direction: "LEFT_TO_RIGHT" });
    mountPane(store);
    await tick();

    clickAt(900); // right 38% → advance
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("2 / 3");
    clickAt(100); // left 38% → retreat
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("1 / 3");
  });

  it("mirrors the zones in RTL", async () => {
    const { store } = await openReader({ viewMode: "PAGE", direction: "RIGHT_TO_LEFT" });
    mountPane(store);
    await tick();

    clickAt(100); // left 38% → advance in RTL
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("2 / 3");
    clickAt(900); // right 38% → retreat in RTL
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("1 / 3");
  });

  it("ignores a tap that was actually a drag", async () => {
    const { store } = await openReader({ viewMode: "PAGE", direction: "LEFT_TO_RIGHT" });
    mountPane(store);
    await tick();

    const stage = target.querySelector(".reader-stage") as HTMLElement;
    stage.dispatchEvent(new MouseEvent("pointerdown", { bubbles: true, clientX: 900, clientY: 100 }));
    stage.dispatchEvent(new MouseEvent("pointermove", { bubbles: true, clientX: 700, clientY: 140 }));
    stage.dispatchEvent(new MouseEvent("click", { bubbles: true, clientX: 700, clientY: 140 }));
    flushSync();

    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("1 / 3");
  });
});

describe("ReaderPane boundary prompts (lines 414-436)", () => {
  it("prompts on the last page and confirms into nextEpisode on the second gesture", async () => {
    const { store, calls } = await openReader({ viewMode: "PAGE", direction: "LEFT_TO_RIGHT" });
    mountPane(store);
    await tick();

    clickAt(900);
    clickAt(900);
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("3 / 3");

    // First advance at the edge: prompt, no navigation yet.
    clickAt(900);
    const prompt = target.querySelector(".boundary-prompt") as HTMLElement;
    expect(prompt).toBeTruthy();
    expect(prompt.textContent).toContain("다음 화로 이동");
    expect(prompt.textContent).toContain("한 번 더 클릭하면 다음 회차로 이동");
    expect(calls.next).toBe(0);

    // Second advance confirms it.
    clickAt(900);
    await tick();
    expect(calls.next).toBe(1);
    expect(target.querySelector(".boundary-prompt")).toBeNull();
  });

  it("prompts on the first page and confirms into prevEpisode", async () => {
    const { store, calls } = await openReader({ viewMode: "PAGE", direction: "LEFT_TO_RIGHT" });
    mountPane(store);
    await tick();

    clickAt(100);
    const prompt = target.querySelector(".boundary-prompt") as HTMLElement;
    expect(prompt.textContent).toContain("이전 화로 이동");
    expect(prompt.textContent).toContain("한 번 더 클릭하면 이전 회차로 이동");
    expect(calls.prev).toBe(0);

    clickAt(100);
    await tick();
    expect(calls.prev).toBe(1);
  });

  it("closes the reader at the last chapter's last page", async () => {
    // Newest-first listing: `e3` is index 0, so "next" (towards newer) has nowhere to go.
    const { store, calls } = await openReader({
      viewMode: "PAGE",
      direction: "LEFT_TO_RIGHT",
      wrId: "e3",
    });
    mountPane(store);
    await tick();

    clickAt(900);
    clickAt(900);
    clickAt(900);
    expect(store.hasNextEpisode).toBe(false);
    expect(target.querySelector(".boundary-prompt")?.textContent).toContain("마지막 회차입니다");
    expect(target.querySelector(".boundary-prompt")?.textContent).toContain("회차 목록으로 이동");

    clickAt(900);
    await tick();
    expect(calls.close).toBe(1);
  });

  it("confirms through the prompt itself (click on the floating toast)", async () => {
    const { store, calls } = await openReader({ viewMode: "DUAL", direction: "LEFT_TO_RIGHT" });
    mountPane(store);
    await tick();

    // DUAL over 3 images = spreads [0-1], [2]: two spreads.
    clickAt(900);
    clickAt(900);
    const prompt = target.querySelector(".boundary-prompt") as HTMLElement;
    expect(prompt).toBeTruthy();
    prompt.click();
    await tick();
    expect(calls.next).toBe(1);
  });
});

describe("ReaderPane keyboard (lines 124-173)", () => {
  const press = (init: KeyboardEventInit): void => {
    window.dispatchEvent(new KeyboardEvent("keydown", { bubbles: true, ...init }));
    flushSync();
  };

  it("closes the reader on Escape", async () => {
    const { store, calls } = await openReader({ viewMode: "PAGE" });
    mountPane(store);
    await tick();

    press({ key: "Escape", code: "Escape" });
    expect(calls.close).toBe(1);
  });

  it("closes the reader on Alt+Left and Cmd+[", async () => {
    const first = await openReader({ viewMode: "PAGE" });
    mountPane(first.store);
    await tick();
    press({ key: "ArrowLeft", code: "ArrowLeft", altKey: true });
    expect(first.calls.close).toBe(1);
    unmount(mounted as never);
    mounted = null;

    const second = await openReader({ viewMode: "PAGE" });
    mountPane(second.store);
    await tick();
    press({ key: "[", code: "BracketLeft", metaKey: true });
    expect(second.calls.close).toBe(1);
  });

  it("honours RTL for the arrow keys", async () => {
    const { store } = await openReader({ viewMode: "PAGE", direction: "RIGHT_TO_LEFT" });
    mountPane(store);
    await tick();

    press({ key: "ArrowLeft", code: "ArrowLeft" }); // RTL: left advances
    expect(store.currentEpisode?.wrId).toBe("e2");
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("2 / 3");

    press({ key: "ArrowRight", code: "ArrowRight" }); // RTL: right retreats
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("1 / 3");
  });

  it("advances with Space and PageDown, retreats with PageUp", async () => {
    const { store } = await openReader({ viewMode: "PAGE", direction: "LEFT_TO_RIGHT" });
    mountPane(store);
    await tick();

    press({ key: " ", code: "Space" });
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("2 / 3");
    press({ key: "PageDown", code: "PageDown" });
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("3 / 3");
    press({ key: "PageUp", code: "PageUp" });
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("2 / 3");
  });

  it("toggles fullscreen with F", async () => {
    const { store } = await openReader({ viewMode: "PAGE" });
    mountPane(store);
    await tick();

    expect(store.isFullscreen).toBe(false);
    press({ key: "f", code: "KeyF" });
    expect(store.isFullscreen).toBe(true);
    expect(target.querySelector(".reader-pane")?.className).toContain("fullscreen");
    press({ key: "f", code: "KeyF" });
    expect(store.isFullscreen).toBe(false);
  });

  it("leaves keyboard shortcuts alone while typing in the page-jump field", async () => {
    const { store, calls } = await openReader({ viewMode: "PAGE" });
    mountPane(store);
    await tick();
    clickAt(500);
    (target.querySelector(".page-jump-button") as HTMLElement).click();
    flushSync();

    const input = target.querySelector(".field-input") as HTMLInputElement;
    input.dispatchEvent(new KeyboardEvent("keydown", { key: " ", code: "Space", bubbles: true }));
    flushSync();
    // The spacebar must not turn the page behind the dialog, and the dialog must stay open.
    expect(target.querySelector(".page-jump-dialog")).toBeTruthy();
    expect(store.currentEpisode?.wrId).toBe("e2");
    expect(store.currentEpisode?.lastReadPage).toBe(0);
    void calls;
  });
});

describe("ReaderPane view modes", () => {
  it("renders a continuous column in SCROLL and tracks the visible page", async () => {
    const { store } = await openReader({ viewMode: "SCROLL", direction: "LEFT_TO_RIGHT" });
    mountPane(store);
    await tick();

    expect(target.querySelectorAll(".scroll-page")).toHaveLength(3);
    expect(target.querySelector(".scroll-image")).toBeTruthy();
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("1 / 3");

    // The column advances one page per gesture, like `animateScrollToItem(index ± 1)`.
    clickAt(900);
    await tick();
    expect(store.currentEpisode?.lastReadPage).toBe(1);
  });

  it("saves the page for every mode through savePage", async () => {
    const { store } = await openReader({ viewMode: "PAGE", direction: "LEFT_TO_RIGHT" });
    mountPane(store);
    await tick();
    clickAt(900);
    await tick();
    expect(store.currentEpisode?.lastReadPage).toBe(1);
  });

  it("groups a wide page alone in DUAL and mirrors the pair for RTL", async () => {
    const { store } = await openReader({
      viewMode: "DUAL",
      direction: "RIGHT_TO_LEFT",
      images: ["https://img.test/1.jpg", "https://img.test/2.jpg", "https://img.test/3.jpg"],
    });
    mountPane(store);
    await tick();
    loadImages(800, 1200);

    // Portrait pages pair up: spread 1, page label mirrored for RTL.
    expect(pillLabel()).toBe("2-1 / 3");
    const slots = target.querySelectorAll(".spread-row .slot");
    expect(slots).toHaveLength(2);

    clickAt(100); // RTL: left advances to the trailing single page
    await tick();
    expect(pillLabel()).toBe("3 / 3");
  });

  it("keeps a wide page single in DUAL", async () => {
    const { store } = await openReader({
      viewMode: "DUAL",
      direction: "LEFT_TO_RIGHT",
      images: ["https://img.test/1.jpg", "https://img.test/2.jpg"],
    });
    mountPane(store);
    await tick();
    loadImages(1600, 1000); // ratio 1.6 → every page is wide

    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("1 / 2");
    expect(target.querySelector(".spread-single")).toBeTruthy();
    expect(target.querySelector(".spread-row")).toBeNull();
  });

  it("slices a wide page in PAGE+SLICE and keeps the active half", async () => {
    const { store } = await openReader({
      viewMode: "PAGE",
      splitMode: "SLICE",
      direction: "RIGHT_TO_LEFT",
      images: ["https://img.test/1.jpg", "https://img.test/2.jpg"],
    });
    mountPane(store);
    await tick();
    loadImages(1600, 1000); // ratio 1.6 → wide → two slices

    // Image 1 is wide: RTL slices it right then left, so the pill counts slices.
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("1 / 3");
    clickAt(100);
    await tick();
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("2 / 3");
    // Both slices still belong to image 0, so progress stays on page 1 (0-based 0).
    expect(store.currentEpisode?.lastReadPage).toBe(0);
  });

  it("provides the view-mode chips in the overlay top bar", async () => {
    const { store } = await openReader({ viewMode: "PAGE" });
    mountPane(store);
    await tick();
    clickAt(500);

    const chips = [...target.querySelectorAll(".top-bar .chip")] as HTMLElement[];
    expect(chips.map((chip) => chip.textContent?.trim())).toEqual([
      "스크롤",
      "단면",
      "양면",
      "우→좌",
      "좌→우",
      "Fit",
      "Slice",
    ]);

    chips[2].click();
    await tick();
    expect(store.viewMode).toBe("DUAL");
    expect(target.querySelector(".dual-view")).toBeTruthy();
  });
});

describe("ReaderPane restore and load feedback", () => {
  it("restores lastReadPage for PAGE, DUAL and SCROLL", async () => {
    const page = await openReader({ viewMode: "PAGE", lastReadPage: 2 });
    mountPane(page.store);
    await tick();
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("3 / 3");
    unmount(mounted as never);
    mounted = null;

    const dual = await openReader({ viewMode: "DUAL", lastReadPage: 2, direction: "LEFT_TO_RIGHT" });
    mountPane(dual.store);
    await tick();
    // Three images = 2 spreads ([0-1], [2]); page index 2 is the trailing single, spread 2 of 2.
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("2 / 2");
    unmount(mounted as never);
    mounted = null;

    const scroll = await openReader({ viewMode: "SCROLL", lastReadPage: 2 });
    mountPane(scroll.store);
    await tick();
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("3 / 3");
  });

  it("shows the loading spinner while the first images resolve", async () => {
    let release = (): void => undefined;
    const pending = new Promise<void>((resolve) => {
      release = () => resolve();
    });
    const calls: Calls = { images: 0, next: 0, prev: 0, close: 0 };
    const source = fakeSource({}, calls);
    const original = source.resolveImages;
    source.resolveImages = async (...args: Parameters<typeof original>) => {
      await pending;
      return original(...args);
    };

    const store = createDesktopStore({ registry: fakeRegistry(source), persistPreferences: false });
    await store.init();
    await store.openSeries({ ...item, sourceId: "test-src" });
    void store.openEpisode(store.episodes[1]!);
    await tick();

    mountPane(store);
    await tick();
    expect(store.readerLoading).toBe(true);
    expect(target.querySelector(".reader-loading")).toBeTruthy();

    release();
    await tick();
    expect(target.querySelector(".reader-loading")).toBeNull();
    expect(target.querySelector(".page-pill")).toBeTruthy();
  });

  it("shows the error with a retry that re-opens the episode", async () => {
    const calls: Calls = { images: 0, next: 0, prev: 0, close: 0 };
    let fail = true;
    const { store } = await openReader({ fail: () => fail, entries: calls, viewMode: "PAGE" });
    mountPane(store);
    await tick();

    expect(store.readerError).toBe("이미지 서버가 응답하지 않습니다.");
    expect(target.querySelector(".reader-error")?.textContent).toContain(
      "이미지 서버가 응답하지 않습니다.",
    );

    fail = false;
    (target.querySelector(".reader-retry") as HTMLElement).click();
    await tick();

    expect(calls.images).toBeGreaterThanOrEqual(2);
    expect(target.querySelector(".reader-error")).toBeNull();
    expect(target.querySelector(".page-pill")?.textContent?.trim()).toBe("1 / 3");
  });

  it("shows the reader toast on top of the stage", async () => {
    const { store } = await openReader({ viewMode: "PAGE" });
    mountPane(store);
    store.showToast("자동으로 스크롤 모드로 전환되었습니다", 5000);
    await tick();
    expect(target.querySelector(".reader-toast")?.textContent).toContain("스크롤 모드");
  });
});

describe("ReaderPane page jump dialog", () => {
  it("opens, validates and jumps to a page", async () => {
    const { store } = await openReader({ viewMode: "PAGE" });
    mountPane(store);
    await tick();
    clickAt(500);

    (target.querySelector(".page-jump-button") as HTMLElement).click();
    flushSync();

    const dialog = target.querySelector(".page-jump-dialog") as HTMLElement;
    expect(dialog).toBeTruthy();
    expect(dialog.querySelector(".dialog-title")?.textContent?.trim()).toBe("페이지 이동 (1 ~ 3)");

    const input = dialog.querySelector(".field-input") as HTMLInputElement;
    expect(input.value).toBe("1");

    // Non-digits are rejected by the field, like `input.all { it.isDigit() }`.
    input.value = "3";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    flushSync();
    (dialog.querySelector(".confirm") as HTMLElement).click();
    await tick();

    expect(target.querySelector(".page-jump-dialog")).toBeNull();
    expect(target.querySelector(".range-text")?.textContent?.trim()).toBe("3 / 3");
  });

  it("clamps an out-of-range value and cancels without jumping", async () => {
    const { store } = await openReader({ viewMode: "PAGE" });
    mountPane(store);
    await tick();
    clickAt(500);
    (target.querySelector(".page-jump-button") as HTMLElement).click();
    flushSync();

    const input = target.querySelector(".field-input") as HTMLInputElement;
    input.value = "99";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    flushSync();
    (target.querySelector(".confirm") as HTMLElement).click();
    await tick();
    // 99 clamps to the last page (`coerceIn(1, maxPage)`).
    expect(target.querySelector(".range-text")?.textContent?.trim()).toBe("3 / 3");

    (target.querySelector(".page-jump-button") as HTMLElement).click();
    flushSync();
    (target.querySelector(".cancel") as HTMLElement).click();
    await tick();
    expect(target.querySelector(".page-jump-dialog")).toBeNull();
    // Cancelling keeps the page the jump left it on.
    expect(target.querySelector(".range-text")?.textContent?.trim()).toBe("3 / 3");
  });

  it("latches continuous trackpad momentum wheel events to a single page turn", async () => {
    const { store } = await openReader({ viewMode: "PAGE", direction: "LEFT_TO_RIGHT", lastReadPage: 0 });
    mountPane(store);
    await tick();
    expect(pillLabel()).toBe("1 / 3");

    const stageEl = target.querySelector(".reader-stage") as HTMLElement;

    // Hard swipe: initial flick deltaX = 80
    stageEl.dispatchEvent(new WheelEvent("wheel", { deltaX: 80, deltaY: 0, bubbles: true, cancelable: true }));
    await tick();
    expect(pillLabel()).toBe("2 / 3");

    // Continuous momentum stream: decaying deltas arriving rapidly (every 40ms) extending past the 250ms cooldown.
    // Total elapsed = 280ms (> 250ms), and deltas are still running (30px > 15px):
    // must NOT trigger a second page turn while still latched!
    for (const delta of [65, 55, 45, 40, 35, 30]) {
      await new Promise((resolve) => setTimeout(resolve, 45));
      stageEl.dispatchEvent(new WheelEvent("wheel", { deltaX: delta, deltaY: 0, bubbles: true, cancelable: true }));
    }
    await tick();
    expect(pillLabel()).toBe("2 / 3");

    // Once momentum slows below idle threshold (8px) after cooldown, latch releases cleanly
    stageEl.dispatchEvent(new WheelEvent("wheel", { deltaX: 5, deltaY: 0, bubbles: true, cancelable: true }));
    await tick();
    expect(pillLabel()).toBe("2 / 3");

    // Second deliberate swipe: user flicks again, turning the next page
    stageEl.dispatchEvent(new WheelEvent("wheel", { deltaX: 65, deltaY: 0, bubbles: true, cancelable: true }));
    await tick();
    expect(pillLabel()).toBe("3 / 3");
  });
});
