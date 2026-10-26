/**
 * Pure page geometry and boundary rules for the reader pane.
 *
 * Owners on the Kotlin side (this file is a straight port, not a re-derivation):
 * - `core/src/main/kotlin/model/Models.kt` → `buildSinglePageSlices`, `buildDualSpreads`,
 *   `ImageHalf`, `SinglePageSlice`, `DualSpread` (wide = `width / height >= 1.0`).
 * - `core/src/main/kotlin/model/ReaderDomain.kt` → `resolveBoundaryDecision`, `imageAspectRatio`
 *   (the "last page reached" prompt / confirm state machine of `ReaderPane.kt` lines 414-436).
 *
 * Kept free of DOM and Svelte so the awkward parts — an aspect ratio that is still unknown while
 * images decode, a wide page that must never be paired, RTL slicing order — can be tested directly
 * (`tests/viewer-reader-pages.test.ts`) instead of through a mounted component.
 */

import type { ReadDirection, SplitMode } from "../state/desktop-state.svelte";

/** `com.comics8.core.model.ImageHalf`. */
export type ImageHalf = "FULL" | "LEFT" | "RIGHT";

/** `com.comics8.core.model.SinglePageSlice`. */
export type SinglePageSlice = {
  /** Zero-based image index inside the chapter. */
  imageIndex: number;
  half: ImageHalf;
};

/**
 * `com.comics8.core.model.DualSpread`.
 *
 * `type` is spelled out instead of using Kotlin's `Single`/`Dual` subclasses so a Svelte template
 * can narrow it with `if (spread.type === "single")`.
 */
export type DualSpread =
  | { type: "single"; index: number }
  | { type: "dual"; firstIndex: number; secondIndex: number };

/**
 * Width / height at or above which a page counts as an already merged two-page spread.
 * Kotlin uses `ratio >= 1.0f` (not `>`), so a perfectly square page is treated as wide.
 */
export const WIDE_PAGE_MIN_ASPECT_RATIO = 1.0;

/** Aspect ratios keyed by image index (`DesktopUiState.imageAspectRatios`). */
export type AspectRatios = Readonly<Record<number, number>>;

/** `ReaderDomain.imageAspectRatio`: `null` while a bitmap is undecoded or invalid. */
export function imageAspectRatio(width: number, height: number): number | null {
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return null;
  }
  return width / height;
}

/** `ratio != null && ratio >= 1.0f` — the wide-page test shared by both builders. */
export function isWidePage(aspectRatios: AspectRatios, index: number): boolean {
  const ratio = aspectRatios[index];
  return typeof ratio === "number" && Number.isFinite(ratio) && ratio >= WIDE_PAGE_MIN_ASPECT_RATIO;
}

/**
 * `buildSinglePageSlices(imageCount, aspectRatios, splitMode, readDirection)`.
 *
 * `FIT` renders every page whole. `SLICE` splits a wide page into its two halves in reading order:
 * right half first for RTL, left half first for LTR. A portrait page stays `FULL`.
 */
export function buildSinglePageSlices(
  imageCount: number,
  aspectRatios: AspectRatios,
  splitMode: SplitMode,
  readDirection: ReadDirection,
): SinglePageSlice[] {
  if (imageCount <= 0) return [];
  if (splitMode === "FIT") {
    return Array.from({ length: imageCount }, (_unused, index) => ({
      imageIndex: index,
      half: "FULL" as ImageHalf,
    }));
  }

  const slices: SinglePageSlice[] = [];
  for (let index = 0; index < imageCount; index += 1) {
    if (isWidePage(aspectRatios, index)) {
      if (readDirection === "RIGHT_TO_LEFT") {
        slices.push({ imageIndex: index, half: "RIGHT" });
        slices.push({ imageIndex: index, half: "LEFT" });
      } else {
        slices.push({ imageIndex: index, half: "LEFT" });
        slices.push({ imageIndex: index, half: "RIGHT" });
      }
    } else {
      slices.push({ imageIndex: index, half: "FULL" });
    }
  }
  return slices;
}

/**
 * `buildDualSpreads(imageCount, aspectRatios)` — smart wide-spread grouping.
 *
 * Consecutive portrait pages are paired; a wide page is always alone, and a portrait page followed
 * by a wide page (or by nothing) is emitted alone so it is never straddled across a spread.
 */
export function buildDualSpreads(imageCount: number, aspectRatios: AspectRatios): DualSpread[] {
  if (imageCount <= 0) return [];

  const spreads: DualSpread[] = [];
  let index = 0;
  while (index < imageCount) {
    if (isWidePage(aspectRatios, index)) {
      spreads.push({ type: "single", index });
      index += 1;
      continue;
    }

    if (index + 1 < imageCount) {
      if (isWidePage(aspectRatios, index + 1)) {
        spreads.push({ type: "single", index });
        index += 1;
      } else {
        spreads.push({ type: "dual", firstIndex: index, secondIndex: index + 1 });
        index += 2;
      }
    } else {
      spreads.push({ type: "single", index });
      index += 1;
    }
  }
  return spreads;
}

/** Placement of one half of a sliced wide page inside the stage. */
export type HalfSliceGeometry = {
  /** Width of the whole (both halves) bitmap. */
  width: number;
  height: number;
  /** Offset of the bitmap; the right half is shifted one half-width left. */
  left: number;
  top: number;
};

/**
 * Geometry for `ImageHalf.LEFT` / `ImageHalf.RIGHT` of a wide page.
 *
 * The desktop draws the crop rect (`computeContentCropRect` + `Canvas`) fitted into the container and
 * centres it, so the web equivalent places the whole bitmap at twice the fitted half width and
 * clips one half — the visible half is centred in both axes, whichever side is shown.
 *
 * Returns `null` when the ratio or the stage box is unusable, so the caller can fall back to plain
 * `object-fit: contain`.
 */
export function halfSliceGeometry(
  ratio: number | undefined,
  half: ImageHalf,
  stageWidth: number,
  stageHeight: number,
): HalfSliceGeometry | null {
  if (typeof ratio !== "number" || !Number.isFinite(ratio) || ratio <= 0) return null;
  if (!Number.isFinite(stageWidth) || !Number.isFinite(stageHeight)) return null;
  if (stageWidth <= 0 || stageHeight <= 0) return null;

  const halfAspect = ratio / 2;
  const stageAspect = stageWidth / stageHeight;

  let halfWidth: number;
  let halfHeight: number;
  if (stageAspect > halfAspect) {
    // The stage is wider than the half: the half's height binds.
    halfHeight = stageHeight;
    halfWidth = stageHeight * halfAspect;
  } else {
    halfWidth = stageWidth;
    halfHeight = stageWidth / halfAspect;
  }

  const centredLeft = (stageWidth - halfWidth) / 2;
  const top = (stageHeight - halfHeight) / 2;
  return {
    width: halfWidth * 2,
    height: halfHeight,
    left: half === "RIGHT" ? centredLeft - halfWidth : centredLeft,
    top,
  };
}

/** The page index a spread saves as progress (`ReaderDualView`'s `pageToSave`). */
export function savedPageForSpread(spread: DualSpread, isR2L: boolean): number {
  if (spread.type === "single") return spread.index;
  return isR2L ? spread.secondIndex : spread.firstIndex;
}

/** `ReaderDualView.currentRangeText`: `"7 / 20"` or `"6-7 / 20"` (mirrored for RTL). */
export function spreadRangeLabel(
  spread: DualSpread,
  totalImages: number,
  isR2L: boolean,
): string {
  if (spread.type === "single") return `${spread.index + 1} / ${totalImages}`;
  const first = spread.firstIndex + 1;
  const second = spread.secondIndex + 1;
  return isR2L ? `${second}-${first} / ${totalImages}` : `${first}-${second} / ${totalImages}`;
}

// ---------------------------------------------------------------------------------------------
// boundary decisions (ReaderDomain.resolveBoundaryDecision)
// ---------------------------------------------------------------------------------------------

/** `ReaderDomain.BoundaryDirection`. */
export type BoundaryDirection = "ADVANCE" | "RETREAT";

/** `ReaderDomain.PageEdge`. */
export type PageEdge = "FIRST" | "LAST";

/**
 * `ReaderDomain.BoundaryDecision`.
 *
 * - `page-turn`: an ordinary page turn inside the chapter.
 * - `show-prompt`: the edge was reached; `ReaderPane.kt` lines 414-436 show the floating prompt.
 * - `confirm-navigate`: the prompt was already visible, so the same gesture confirms it.
 */
export type BoundaryDecision =
  | { kind: "page-turn"; targetPage: number }
  | { kind: "show-prompt"; edge: PageEdge }
  | { kind: "confirm-navigate"; edge: PageEdge };

/**
 * `ReaderDomain.resolveBoundaryDecision(currentPage, totalPages, direction, isPromptActive)`.
 *
 * The second identical gesture confirms the prompt, which is why the caller passes whether the
 * prompt is still on screen.
 */
export function resolveBoundaryDecision(
  currentPage: number,
  totalPages: number,
  direction: BoundaryDirection,
  isPromptActive: boolean,
): BoundaryDecision {
  const safeTotal = Math.max(1, Math.trunc(totalPages));
  const safeCurrent = Math.min(Math.max(Math.trunc(currentPage), 0), safeTotal - 1);

  if (direction === "ADVANCE") {
    if (safeCurrent < safeTotal - 1) {
      return { kind: "page-turn", targetPage: safeCurrent + 1 };
    }
    return isPromptActive
      ? { kind: "confirm-navigate", edge: "LAST" }
      : { kind: "show-prompt", edge: "LAST" };
  }

  if (safeCurrent > 0) {
    return { kind: "page-turn", targetPage: safeCurrent - 1 };
  }
  return isPromptActive
    ? { kind: "confirm-navigate", edge: "FIRST" }
    : { kind: "show-prompt", edge: "FIRST" };
}
