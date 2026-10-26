/**
 * Shell-level wiring of `src/ReaderApp.svelte`, the port of `desktop/src/main/kotlin/Main.kt`.
 *
 * Only one block of `Main.kt` is shell-owned: `if (state.showPageJump)` (lines 320-336), which picks
 * its target from the screen — `episodePage` / `episodeLastPage` / `goToEpisodePage` on Series, the
 * catalog (or reader) triple everywhere else. `views/BrowsePane.svelte` and
 * `viewer/ReaderPane.svelte` keep their own dialogs (they own the page they jump in), so the shell
 * must not stack a second one over them.
 *
 * The shell's `onMount` runs `desktopState.init()`, and with no source installed the store settles on
 * its empty state (`screen = "Browse"`, no series). The fixtures below are therefore written by
 * {@link mountShell} *after* that settles, not in `beforeEach`: the screens under test are the ones
 * the store reaches with a source loaded.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { flushSync, mount, unmount } from "svelte";

import ReaderApp from "../src/ReaderApp.svelte";
import { desktopState } from "../src/state/desktop-state.svelte";

const tick = async () => {
  for (let i = 0; i < 20; i += 1) {
    await Promise.resolve();
    flushSync();
  }
};

let target: HTMLElement;
let app: Record<string, unknown> | null = null;

beforeEach(() => {
  if (typeof Animation !== "undefined" && Animation.prototype) {
    Animation.prototype.cancel = () => {};
  }
  document.body.innerHTML = '<div id="app"></div>';
  target = document.getElementById("app")!;
  desktopState.showPageJump = false;
});

afterEach(() => {
  if (app) unmount(app);
  app = null;
  desktopState.showPageJump = false;
  desktopState.screen = "Browse";
});

/**
 * Mounts the shell and parks the store on the Series screen once `init()` has settled.
 *
 * That screen is the one whose page-jump target is episode-scoped (`episodePage` /
 * `episodeLastPage` / `goToEpisodePage`), which is exactly what the shell block decides.
 */
async function mountShell(): Promise<void> {
  app = mount(ReaderApp, { target });
  flushSync();
  await tick();
  desktopState.screen = "Series";
  desktopState.series = { id: "42", title: "테스트 작품", thumbUrl: "", href: "" };
  desktopState.episodePage = 2;
  desktopState.episodeLastPage = 7;
  flushSync();
}

describe("ReaderApp — shell page jump (Main.kt 320-336)", () => {
  it("stays closed until the store asks for it", async () => {
    await mountShell();
    await tick();
    expect(target.querySelector("[data-testid='page-jump-dialog']")).toBeNull();
  });

  it("targets the episode page range on the Series screen", async () => {
    await mountShell();
    desktopState.togglePageJump(true);
    await tick();

    const dialog = target.querySelector<HTMLElement>("[data-testid='page-jump-dialog']");
    expect(dialog).not.toBeNull();
    // `PageJumpDialog(current = state.episodePage, last = state.episodeLastPage)`.
    expect(dialog!.querySelector(".dialog-title")?.textContent).toBe("페이지 이동 (1 ~ 7)");
    expect(dialog!.querySelector<HTMLInputElement>(".field-input")!.value).toBe("2");

    // Dismissal goes through `viewModel.togglePageJump(false)`.
    (dialog!.querySelector(".dialog-button.cancel") as HTMLButtonElement).click();
    await tick();
    expect(desktopState.showPageJump).toBe(false);
    expect(target.querySelector("[data-testid='page-jump-dialog']")).toBeNull();
  });

  it("jumps the episode page through goToEpisodePage", async () => {
    const goToEpisodePage = vi
      .spyOn(desktopState, "goToEpisodePage")
      .mockResolvedValue(undefined);
    await mountShell();
    desktopState.togglePageJump(true);
    await tick();

    const dialog = target.querySelector<HTMLElement>("[data-testid='page-jump-dialog']")!;
    const field = dialog.querySelector<HTMLInputElement>(".field-input")!;
    field.value = "5";
    field.dispatchEvent(new Event("input", { bubbles: true }));
    (dialog.querySelector(".dialog-button.confirm") as HTMLButtonElement).click();
    await tick();

    expect(goToEpisodePage).toHaveBeenCalledWith(5);
    goToEpisodePage.mockRestore();
  });

  it("leaves the Browse dialog to the pane that already mounts it", async () => {
    await mountShell();

    desktopState.screen = "Browse";
    desktopState.togglePageJump(true);
    await tick();
    expect(target.querySelector("[data-testid='page-jump-dialog']")).toBeNull();

    // Series (and any other screen `Main.kt` routes here) still gets the shell dialog.
    desktopState.screen = "Series";
    flushSync();
    expect(target.querySelector("[data-testid='page-jump-dialog']")).not.toBeNull();
  });
});
