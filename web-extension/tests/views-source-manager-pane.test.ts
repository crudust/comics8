/**
 * Source manager, `views/SourceManagerPane.svelte`, the 1:1 port of
 * `desktop/src/main/kotlin/ui/source/SourceManagerPane.kt`, plus the store actions behind it
 * (`DesktopStore.openSourceManager` / `toggleSourceEnabled` / `reorderSources` / `uninstallSource` /
 * `importJsFile` — the web twins of `DesktopViewModel` + `SourcePrefs`).
 *
 * The pane is presentational: it reads `store.installedSources` and calls store methods. The tests
 * therefore seed a store (a fake registry, so no `chrome.storage`) and assert what the pane renders
 * and which action a click reaches. Two details are easy to get wrong and are pinned here: the local
 * source is pinned first and can never be removed, and every user-installed JS source has a trash
 * button (the extension ships no built-in plugin, so nothing is exempt).
 *
 * Styling is not asserted through `getComputedStyle`: happy-dom does not apply Svelte's scoped
 * styles, so the class contract (`.source-card`, `.switch input`, `.icon-button.danger`) is the
 * stable thing to check.
 */

import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushSync, mount, unmount } from "svelte";

import SourceManagerPane from "../src/views/SourceManagerPane.svelte";
import { createDesktopStore, resolveSourceKind } from "../src/state/desktop-state.svelte";
import type { DesktopStore } from "../src/state/desktop-state.svelte";
import type { AnyLoadedSource, SourceRegistry, SourceSummary } from "../src/sources/registry";

const tick = async () => {
  for (let i = 0; i < 20; i += 1) {
    await Promise.resolve();
    flushSync();
  }
};

const summary = (
  id: string,
  displayName: string,
  options: { origin?: string; builtin?: boolean } = {},
): SourceSummary => ({
  id,
  displayName,
  origin: options.origin ?? "",
  catalogs: [],
  builtin: options.builtin ?? false,
});

/** The fixed storage sources plus the installed plugins the user manages here. */
const local = summary("local", "로컬 저장소");
const webdav = summary("network-webdav", "내 드라이브", { origin: "https://dav.test/books" });
const plugin = summary("my-src", "내 플러그인", { origin: "https://src.test" });
const other = summary("other-src", "다른 플러그인");

/**
 * Fake registry: `list()` returns {@link sources} and `uninstall()` drops the id, which is exactly
 * the surface the store and the pane touch.
 */
function fakeRegistry(sources: SourceSummary[], installed: string[] = []) {
  const calls = { uninstall: [] as string[], install: [] as string[] };
  const loadedFor = (id: string): AnyLoadedSource =>
    ({
      id,
      displayName: sources.find((source) => source.id === id)?.displayName ?? id,
      meta: { origin: "https://src.test", episodePageSize: 100, searchPlaceholder: "검색" },
      catalogs: [],
      loadListing: async () => ({ items: [], pageInfo: { currentPage: 1, lastPage: 1 } }),
      loadEpisodes: async () => ({ items: [], pageInfo: { currentPage: 1, lastPage: 1 } }),
      resolveImages: async () => [],
      search: async () => [],
    }) as unknown as AnyLoadedSource;

  const registry = {
    init: async () => {},
    list: () => sources.filter((source) => !calls.uninstall.includes(source.id)),
    get: async (id: string) =>
      sources.some((source) => source.id === id) && !calls.uninstall.includes(id)
        ? loadedFor(id)
        : null,
    uninstall: async (id: string) => {
      if (!installed.includes(id)) return false;
      calls.uninstall.push(id);
      return true;
    },
    install: async (script: string) => {
      calls.install.push(script);
      return summary("fresh", "새 플러그인");
    },
  } as unknown as SourceRegistry;
  return { registry, calls };
}

function makeStore(sources: SourceSummary[] = [local, webdav, plugin]) {
  const { registry, calls } = fakeRegistry(sources, ["my-src", "other-src"]);
  const store = createDesktopStore({ registry, persistPreferences: false });
  store.installedSources = registry.list();
  return { store, calls };
}

let target: HTMLElement;
let mounted: Record<string, unknown> | null = null;

beforeEach(() => {
  if (typeof Animation !== "undefined" && Animation.prototype) {
    Animation.prototype.cancel = () => {};
  }
  document.body.innerHTML = '<div id="app"></div>';
  target = document.getElementById("app")!;
});

function mountPane(store: DesktopStore): HTMLElement {
  mounted = mount(SourceManagerPane, { target, props: { store } });
  flushSync();
  return target;
}

const rows = (): HTMLElement[] => [...target.querySelectorAll<HTMLElement>(".source-card")];
const rowIds = (): (string | null)[] => rows().map((row) => row.getAttribute("data-source-id"));
const rowFor = (id: string): HTMLElement =>
  rows().find((row) => row.getAttribute("data-source-id") === id)!;
const texts = (nodes: Element[]): (string | undefined)[] =>
  nodes.map((node) => node.textContent?.trim());

describe("SourceManagerPane header", () => {
  it("renders the back arrow, the title and the JS import action", () => {
    const { store } = makeStore();
    mountPane(store);

    expect(target.querySelector(".title")?.textContent?.trim()).toBe("소스 관리");
    expect(target.querySelector(".top-bar .icon-button")?.getAttribute("aria-label")).toBe(
      "뒤로가기",
    );
    expect(target.querySelector(".add-button")?.textContent?.trim()).toBe("+ .js 플러그인 추가");
    // The picker itself is a hidden file input restricted to .js scripts.
    const input = target.querySelector<HTMLInputElement>(".file-input")!;
    expect(input.getAttribute("type")).toBe("file");
    expect(input.getAttribute("accept")).toBe(".js");
  });

  it("goes back to Browse through closeSourceManager", () => {
    const { store } = makeStore();
    store.screen = "SourceManager";
    mountPane(store);

    target.querySelector<HTMLElement>(".top-bar .icon-button")!.click();
    flushSync();
    expect(store.screen).toBe("Browse");
  });

  it("opens the file picker from the header action", () => {
    const { store } = makeStore();
    mountPane(store);

    const input = target.querySelector<HTMLInputElement>(".file-input")!;
    const click = vi.spyOn(input, "click").mockImplementation(() => {});
    target.querySelector<HTMLElement>(".add-button")!.click();
    expect(click).toHaveBeenCalledTimes(1);
  });
});

describe("SourceManagerPane sections", () => {
  it("splits the sources into the storage and the online section", () => {
    const { store } = makeStore();
    mountPane(store);

    expect(texts([...target.querySelectorAll(".section-header")])).toEqual([
      "로컬 / WebDAV",
      "웹 / JS 스크립트",
    ]);
    // No comic cards, no catalog chips: only one row per source, local first.
    expect(rowIds()).toEqual(["local", "network-webdav", "my-src"]);
    expect(target.querySelector(".grid")).toBeNull();
    expect(target.querySelector(".chip")).toBeNull();
  });

  it("labels each row with its display name and origin", () => {
    const { store } = makeStore();
    mountPane(store);

    const dav = rowFor("network-webdav");
    expect(dav.querySelector(".name")?.textContent?.trim()).toBe("내 드라이브");
    expect(dav.querySelector(".origin")?.textContent?.trim()).toBe("WebDAV: https://dav.test/books");

    // A plugin row names its JS type when it reports no origin.
    expect(rowFor("my-src").querySelector(".origin")?.textContent?.trim()).toBe(
      "JS 확장 스크립트",
    );
    expect(rowFor("local").querySelector(".origin")?.textContent?.trim()).toBe("local");
  });

  it("shows the empty online section with its JS action", () => {
    const { store } = makeStore([local]);
    mountPane(store);

    expect(target.querySelector(".empty-card p")?.textContent?.trim()).toBe(
      "등록된 온라인/확장 소스가 없습니다.",
    );
    const input = target.querySelector<HTMLInputElement>(".file-input")!;
    const click = vi.spyOn(input, "click").mockImplementation(() => {});
    target.querySelector<HTMLElement>(".empty-action")!.click();
    expect(click).toHaveBeenCalledTimes(1);
  });
});

describe("SourceManagerPane row controls", () => {
  it("pins the local row: fixed, always enabled, no reorder and no trash", () => {
    const { store } = makeStore();
    mountPane(store);

    const localRow = rowFor("local");
    expect(localRow.querySelector(".fixed-label")?.textContent?.trim()).toBe("고정됨");
    expect(localRow.querySelector(".reorder")).toBeNull();
    expect(localRow.querySelector(".icon-button.danger")).toBeNull();
    const toggle = localRow.querySelector<HTMLInputElement>(".switch input")!;
    expect(toggle.checked).toBe(true);
    expect(toggle.disabled).toBe(true);
  });

  it("switches a source off through toggleSourceEnabled", async () => {
    const { store } = makeStore();
    store.activeSourceId = "my-src";
    mountPane(store);

    const toggle = rowFor("my-src").querySelector<HTMLInputElement>(".switch input")!;
    expect(toggle.checked).toBe(true);
    toggle.click();
    await tick();

    expect(store.isSourceEnabled("my-src")).toBe(false);
    // The disabled source leaves the list, and the active source falls back to the local library.
    expect(rowIds()).toEqual(["local", "network-webdav"]);
    expect(store.activeSourceId).toBe("local");
  });

  it("offers the trash button for every user-installed JS plugin", async () => {
    const { store, calls } = makeStore([local, webdav, plugin, other]);
    mountPane(store);

    // Only the storage sources are exempt: `local` is pinned and WebDAV is configured elsewhere.
    expect(rowFor("local").querySelector(".icon-button.danger")).toBeNull();
    expect(rowFor("network-webdav").querySelector(".icon-button.danger")).toBeNull();
    expect(rowFor("my-src").querySelector(".icon-button.danger")).not.toBeNull();
    expect(rowFor("other-src").querySelector(".icon-button.danger")).not.toBeNull();

    // The pane calls the bare `confirm()` global, which Vitest 4 no longer exposes under happy-dom.
    vi.stubGlobal("confirm", () => true);
    rowFor("my-src").querySelector<HTMLElement>(".icon-button.danger")!.click();
    await tick();

    expect(calls.uninstall).toEqual(["my-src"]);
    expect(rowIds()).toEqual(["local", "network-webdav", "other-src"]);
    vi.unstubAllGlobals();
  });

  it("reorders a source inside its own section with the arrow buttons", async () => {
    const { store } = makeStore([local, webdav, plugin, other]);
    mountPane(store);

    // First online source: "up" is disabled, "down" swaps it with the next online row.
    const reorderButtons = (id: string) =>
      [...rowFor(id).querySelectorAll<HTMLButtonElement>(".reorder .icon-button")];
    expect(reorderButtons("my-src")[0]!.disabled).toBe(true);
    reorderButtons("my-src")[1]!.click();
    await tick();
    expect(rowIds()).toEqual(["local", "network-webdav", "other-src", "my-src"]);

    // The last online source cannot move further down, and it can move back up.
    expect(reorderButtons("my-src")[1]!.disabled).toBe(true);
    expect(reorderButtons("my-src")[0]!.disabled).toBe(false);
    // The storage section keeps its own order (`local` stays pinned).
    expect(reorderButtons("network-webdav")[0]!.disabled).toBe(true);
  });

  it("imports a picked .js file through importJsFile", async () => {
    const { store } = makeStore();
    mountPane(store);

    const input = target.querySelector<HTMLInputElement>(".file-input")!;
    const file = new File(["// plugin"], "plugin.js", { type: "text/javascript" });
    Object.defineProperty(input, "files", { value: [file], configurable: true });
    input.dispatchEvent(new Event("change"));
    await tick();

    expect(store.registry).toBeTruthy();
    // A rejected/installed file leaves no error behind on a clean import path.
    expect(store.sourceError).toBeNull();
  });
});

describe("DesktopStore source manager actions", () => {
  it("routes to SourceManager and back", () => {
    const { store } = makeStore();
    store.openSourceManager();
    expect(store.screen).toBe("SourceManager");
    store.closeSourceManager();
    expect(store.screen).toBe("Browse");
  });

  it("classifies source ids like `ComicSource.resolveSourceType()`", () => {
    expect(resolveSourceKind("local")).toBe("LOCAL");
    expect(resolveSourceKind("network-webdav")).toBe("WEBDAV");
    expect(resolveSourceKind("network-webdav-home")).toBe("WEBDAV");
    expect(resolveSourceKind("my-src")).toBe("JS");
  });

  it("keeps an unknown source out of the enabled list when the user switched it off", async () => {
    const { store } = makeStore();
    await store.toggleSourceEnabled("my-src");
    expect(store.isSourceEnabled("my-src")).toBe(false);
    // Switching it back on restores the row.
    await store.toggleSourceEnabled("my-src");
    expect(store.isSourceEnabled("my-src")).toBe(true);
    expect(store.installedSources.map((source) => source.id)).toContain("my-src");
  });

  it("never uninstalls the local library", async () => {
    const { store, calls } = makeStore();
    await store.uninstallSource("local");
    expect(calls.uninstall).toEqual([]);
    expect(store.installedSources.map((source) => source.id)).toContain("local");
  });

  it("uninstalls any user-installed JS plugin", async () => {
    const { store, calls } = makeStore();
    await store.uninstallSource("my-src");
    expect(calls.uninstall).toEqual(["my-src"]);
    expect(store.installedSources.map((source) => source.id)).not.toContain("my-src");
  });
});
