/**
 * Reader page geometry and boundary rules (`src/viewer/reader-pages.ts`).
 *
 * These are the exact cases the Kotlin unit tests already pin down, restated for the web port:
 * `core/src/test/kotlin/model/DualSpreadTest.kt`, `SinglePageSliceTest.kt` and
 * `ReaderDomain.resolveBoundaryDecision`. Asserting them here means a change to the port breaks a
 * test the same way it would break the Kotlin suite, instead of only showing up visually.
 */

import { describe, expect, it } from "vitest";

import {
  buildDualSpreads,
  buildSinglePageSlices,
  halfSliceGeometry,
  imageAspectRatio,
  isWidePage,
  resolveBoundaryDecision,
  savedPageForSpread,
  spreadRangeLabel,
} from "../src/viewer/reader-pages";

describe("buildDualSpreads (DualSpreadTest.kt)", () => {
  it("groups consecutive portrait pages", () => {
    expect(buildDualSpreads(4, {})).toEqual([
      { type: "dual", firstIndex: 0, secondIndex: 1 },
      { type: "dual", firstIndex: 2, secondIndex: 3 },
    ]);
  });

  it("keeps an odd trailing page as a single", () => {
    expect(buildDualSpreads(5, {})).toEqual([
      { type: "dual", firstIndex: 0, secondIndex: 1 },
      { type: "dual", firstIndex: 2, secondIndex: 3 },
      { type: "single", index: 4 },
    ]);
  });

  it("keeps a wide page alone and never straddles it", () => {
    // Page 1 is wide, so page 0 cannot pair with it and page 2 pairs with page 3.
    expect(buildDualSpreads(4, { 1: 1.4 })).toEqual([
      { type: "single", index: 0 },
      { type: "single", index: 1 },
      { type: "dual", firstIndex: 2, secondIndex: 3 },
    ]);
  });

  it("treats an exactly square page as wide (ratio >= 1.0) and ignores unknown ratios", () => {
    expect(buildDualSpreads(2, { 0: 1.0 })).toEqual([
      { type: "single", index: 0 },
      { type: "single", index: 1 },
    ]);
    // Unknown index → portrait → paired.
    expect(buildDualSpreads(2, { 7: 2.5 })).toEqual([
      { type: "dual", firstIndex: 0, secondIndex: 1 },
    ]);
    expect(buildDualSpreads(0, {})).toEqual([]);
  });
});

describe("buildSinglePageSlices (SinglePageSliceTest.kt)", () => {
  it("returns every page whole in FIT mode", () => {
    expect(buildSinglePageSlices(3, { 1: 1.4 }, "FIT", "RIGHT_TO_LEFT")).toEqual([
      { imageIndex: 0, half: "FULL" },
      { imageIndex: 1, half: "FULL" },
      { imageIndex: 2, half: "FULL" },
    ]);
  });

  it("splits a wide page right-then-left for RTL", () => {
    expect(buildSinglePageSlices(3, { 1: 1.5 }, "SLICE", "RIGHT_TO_LEFT")).toEqual([
      { imageIndex: 0, half: "FULL" },
      { imageIndex: 1, half: "RIGHT" },
      { imageIndex: 1, half: "LEFT" },
      { imageIndex: 2, half: "FULL" },
    ]);
  });

  it("splits a wide page left-then-right for LTR", () => {
    expect(buildSinglePageSlices(3, { 1: 1.5 }, "SLICE", "LEFT_TO_RIGHT")).toEqual([
      { imageIndex: 0, half: "FULL" },
      { imageIndex: 1, half: "LEFT" },
      { imageIndex: 1, half: "RIGHT" },
      { imageIndex: 2, half: "FULL" },
    ]);
  });

  it("keeps portrait pages intact in SLICE mode", () => {
    expect(buildSinglePageSlices(2, { 0: 0.7, 1: 0.75 }, "SLICE", "RIGHT_TO_LEFT")).toEqual([
      { imageIndex: 0, half: "FULL" },
      { imageIndex: 1, half: "FULL" },
    ]);
    expect(buildSinglePageSlices(0, {}, "SLICE", "RIGHT_TO_LEFT")).toEqual([]);
  });
});

describe("aspect ratio helpers (ReaderDomain.imageAspectRatio / isWidePage)", () => {
  it("rejects undecoded bitmaps instead of dividing by zero", () => {
    expect(imageAspectRatio(1600, 2400)).toBeCloseTo(2 / 3);
    expect(imageAspectRatio(0, 2400)).toBeNull();
    expect(imageAspectRatio(1600, 0)).toBeNull();
    expect(imageAspectRatio(-1, 10)).toBeNull();
    expect(imageAspectRatio(Number.NaN, 10)).toBeNull();
  });

  it("flags a page wide at or above 1.0", () => {
    expect(isWidePage({ 0: 1.0 }, 0)).toBe(true);
    expect(isWidePage({ 0: 0.99 }, 0)).toBe(false);
    expect(isWidePage({}, 0)).toBe(false);
  });
});

describe("spread progress and labels (ReaderDualView)", () => {
  it("saves the first page for LTR and the second for RTL", () => {
    const dual = { type: "dual", firstIndex: 4, secondIndex: 5 } as const;
    expect(savedPageForSpread(dual, true)).toBe(5);
    expect(savedPageForSpread(dual, false)).toBe(4);
    expect(savedPageForSpread({ type: "single", index: 9 }, true)).toBe(9);
  });

  it("mirrors a dual spread range for RTL", () => {
    const dual = { type: "dual", firstIndex: 5, secondIndex: 6 } as const;
    expect(spreadRangeLabel(dual, 20, true)).toBe("7-6 / 20");
    expect(spreadRangeLabel(dual, 20, false)).toBe("6-7 / 20");
    expect(spreadRangeLabel({ type: "single", index: 0 }, 20, true)).toBe("1 / 20");
  });
});

describe("halfSliceGeometry (ImageHalf crops of DesktopAsyncImage)", () => {
  // Stage 1000x1000, wide page ratio 2.0 → one half is 1:1, so it fits the stage exactly.
  it("fits a half into the stage and clips the other half", () => {
    const left = halfSliceGeometry(2, "LEFT", 1000, 1000);
    expect(left).not.toBeNull();
    expect(left!.height).toBe(1000);
    expect(left!.width).toBe(2000);
    expect(left!.left).toBe(0);
    expect(left!.top).toBe(0);

    // The right half of the same bitmap is the same box shifted one half-width left.
    const right = halfSliceGeometry(2, "RIGHT", 1000, 1000);
    expect(right!.width).toBe(2000);
    expect(right!.left).toBe(-1000);
  });

  it("centres a taller half vertically and a narrower half horizontally", () => {
    // Stage 1200x600, ratio 1.0 → half aspect 0.5; stage aspect 2.0 > 0.5 so height binds.
    const geometry = halfSliceGeometry(1, "LEFT", 1200, 600)!;
    expect(geometry.height).toBe(600);
    expect(geometry.width).toBe(600); // half width 300 → whole bitmap 600
    expect(geometry.left).toBe((1200 - 300) / 2);
    expect(geometry.top).toBe(0);
  });

  it("returns null instead of dividing by an unknown ratio or an unmeasured stage", () => {
    expect(halfSliceGeometry(undefined, "LEFT", 1000, 1000)).toBeNull();
    expect(halfSliceGeometry(0, "LEFT", 1000, 1000)).toBeNull();
    expect(halfSliceGeometry(1.5, "LEFT", 0, 1000)).toBeNull();
    expect(halfSliceGeometry(1.5, "LEFT", 1000, 0)).toBeNull();
  });
});

describe("resolveBoundaryDecision (ReaderPane.kt lines 414-436)", () => {
  it("turns pages inside the chapter", () => {
    expect(resolveBoundaryDecision(3, 10, "ADVANCE", false)).toEqual({
      kind: "page-turn",
      targetPage: 4,
    });
    expect(resolveBoundaryDecision(3, 10, "RETREAT", false)).toEqual({
      kind: "page-turn",
      targetPage: 2,
    });
  });

  it("prompts on the first edge gesture, then confirms on the second", () => {
    expect(resolveBoundaryDecision(9, 10, "ADVANCE", false)).toEqual({
      kind: "show-prompt",
      edge: "LAST",
    });
    expect(resolveBoundaryDecision(9, 10, "ADVANCE", true)).toEqual({
      kind: "confirm-navigate",
      edge: "LAST",
    });
    expect(resolveBoundaryDecision(0, 10, "RETREAT", false)).toEqual({
      kind: "show-prompt",
      edge: "FIRST",
    });
    expect(resolveBoundaryDecision(0, 10, "RETREAT", true)).toEqual({
      kind: "confirm-navigate",
      edge: "FIRST",
    });
  });

  it("clamps out-of-range pages and a zero page count", () => {
    // totalPages 0 → one implicit page at index 0.
    expect(resolveBoundaryDecision(0, 0, "ADVANCE", false)).toEqual({
      kind: "show-prompt",
      edge: "LAST",
    });
    // current beyond the end behaves like the last page.
    expect(resolveBoundaryDecision(99, 10, "ADVANCE", false)).toEqual({
      kind: "show-prompt",
      edge: "LAST",
    });
    expect(resolveBoundaryDecision(99, 10, "RETREAT", false)).toEqual({
      kind: "page-turn",
      targetPage: 8,
    });
  });
});
