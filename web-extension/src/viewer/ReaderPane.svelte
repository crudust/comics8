<script lang="ts">
  /**
   * Reader surface, ported 1:1 from `desktop/src/main/kotlin/ui/reader/ReaderPane.kt`.
   *
   * Mapping notes (deliberate, not accidental):
   * - `state: DesktopUiState` + `viewModel: DesktopViewModel` collapse into one `store`
   *   ({@link DesktopStore}, `src/state/desktop-state.svelte.ts`), exactly like `SeriesPane.svelte`.
   *   It defaults to the process-wide `desktopState`; the `store` prop only exists so a test or a
   *   nested surface can render the pane against its own store.
   * - `ViewMode.SCROLL` → `.scroll-view` (continuous column, 65% width like `fillMaxWidth(0.65f)`),
   *   tracking `firstVisibleIndex` and saving it through `store.savePage` (the Kotlin
   *   `LaunchedEffect(listState.firstVisibleItemIndex) { viewModel.savePage(...) }`).
   * - `ViewMode.PAGE` → `buildSinglePageSlices` (`core/.../Models.kt`), so `SplitMode.SLICE` cuts a
   *   wide page into two halves in reading order (right half first for RTL).
   * - `ViewMode.DUAL` → `buildDualSpreads`, including the smart wide-spread rule: a page whose
   *   `width / height >= 1.0` is always alone, and the physical left/right placement follows
   *   `readDirection` (page 1 on the right for RTL).
   * - Tap zones are lines 473-482: left 38% / right 38% / centre 24%, mirrored by `isR2L`.
   *   Kotlin only wires them in the paged layout (`ReaderPagedLayout.handleTap`); SCROLL uses the
   *   same geometry but turns a side tap into one page of scrolling, which is what the same gesture
   *   means in a continuous column.
   * - Boundary prompts (`ReaderPagedLayout` + `EpisodeBoundaryPrompt`, lines 414-436) are driven by
   *   the pure `ReaderDomain.resolveBoundaryDecision` port in `./reader-pages.ts`: the first edge
   *   gesture shows the prompt, the second confirms it (`prevEpisode()` / `nextEpisode()`, or
   *   `closeReader()` when the chapter has no neighbour). The prompt auto-hides after 3s, like the
   *   Kotlin `promptJob`.
   * - Immersive overlay (`ReaderOverlayControls`): `controlsVisible` starts `false`; while hidden a
   *   bottom pill shows the current range; while visible the overlay `TopBar` slides in from the top
   *   and `ReaderBottomBar` from the bottom.
   * - Keyboard (lines 124-173): Back/Escape, Alt/Cmd+Left, Cmd+`[` close the reader; arrows are
   *   direction-aware; Space/PageDown advance, PageUp retreats; `F` toggles fullscreen.
   * - Loading/error mirror the Kotlin guards (`readerLoading && readerImages.isEmpty()`, and
   *   `readerError != null && readerImages.isEmpty()`), with Retry calling
   *   `openEpisode(currentEpisode)`.
   */

  import { onDestroy, tick, untrack } from 'svelte';
  import {
    ArrowLeft,
    ChevronLeft,
    ChevronRight,
    Hash,
    Maximize2,
    Minimize2,
    RefreshCw,
  } from 'lucide-svelte';

  import { desktopState } from '../state/desktop-state.svelte';
  import type { DesktopStore, ViewMode } from '../state/desktop-state.svelte';
  import PageJumpDialog from '../ui/PageJumpDialog.svelte';
  import {
    buildDualSpreads,
    buildSinglePageSlices,
    halfSliceGeometry,
    imageAspectRatio,
    isWidePage,
    resolveBoundaryDecision,
    savedPageForSpread,
    spreadRangeLabel,
    type ImageHalf,
    type SinglePageSlice,
  } from './reader-pages';

  interface Props {
    /** Store to render; defaults to the shell's `desktopState`. */
    store?: DesktopStore;
  }

  let { store = desktopState }: Props = $props();

  /** `LocalStrings` values the pane prints verbatim (ko). */
  const LABEL_PREV_EPISODE = '이전화';
  const LABEL_NEXT_EPISODE = '다음화';
  const LABEL_EPISODE_LIST = '목록';
  const LABEL_PAGE_JUMP = '페이지 이동';
  const LABEL_RETRY = '다시 시도';
  const LOADING_TEXT = '불러오는 중...';
  const ERROR_NO_IMAGES = '이미지를 불러올 수 없습니다.';
  /**
   * Boundary prompts. The title is the action the confirmation performs (spec: "다음 화로 이동" /
   * "이전 화로 이동"); the subtitle is the desktop hint, and it degrades to "회차 목록으로 이동"
   * when there is no neighbouring chapter (`ReaderPane.kt` `hasNextEpisode` / `hasPrevEpisode`).
   */
  const PROMPT_NEXT_TITLE = '다음 화로 이동';
  const PROMPT_NEXT_HINT = '한 번 더 클릭하면 다음 회차로 이동';
  const PROMPT_LAST_EPISODE = '마지막 회차입니다';
  const PROMPT_CLOSE_HINT = '한 번 더 클릭하면 회차 목록으로 이동';
  const PROMPT_PREV_TITLE = '이전 화로 이동';
  const PROMPT_PREV_HINT = '한 번 더 클릭하면 이전 회차로 이동';
  const PROMPT_FIRST_EPISODE = '첫 번째 회차입니다';
  /** `delay(3000)` in `promptJob`. */
  const PROMPT_TIMEOUT_MS = 3000;
  /**
   * `ReaderDomain.SCROLL_PAGE_THRESHOLD` / `SCROLL_IDLE_THRESHOLD` / `SCROLL_LATCH_COOLDOWN_MS`.
   * Parity with macOS trackpad discrete paging engine in `desktop/.../CommonWidgets.kt`,
   * scaled for browser WheelEvent pixel deltas.
   */
  const SCROLL_PAGE_THRESHOLD = 15.0;
  const SCROLL_IDLE_THRESHOLD = 8.0;
  const SCROLL_LATCH_COOLDOWN_MS = 250;
  const SCROLL_STREAM_IDLE_MS = 120;
  /** `ZoomableBox` treats a drag beyond 8px as a pan, never a tap. */
  const TAP_SLOP_PX = 8;

  // --- state -----------------------------------------------------------------------------------

  const images = $derived(store.readerImages);
  const totalImages = $derived(images.length);
  const aspectRatios = $derived(store.imageAspectRatios);
  const isR2L = $derived(store.readDirection === 'RIGHT_TO_LEFT');

  const slices = $derived(
    buildSinglePageSlices(totalImages, aspectRatios, store.splitMode, store.readDirection),
  );
  const totalSlices = $derived(Math.max(slices.length, 1));

  const spreads = $derived(buildDualSpreads(totalImages, aspectRatios));
  const totalSpreads = $derived(Math.max(spreads.length, 1));

  /** Every mode starts in immersive mode (`controlsVisible = remember { mutableStateOf(false) }`). */
  let controlsVisible = $state(false);

  /** SCROLL: first visible image; PAGE: slice index; DUAL: spread index. All 0-based. */
  let firstVisible = $state(0);
  let sliceIndex = $state(0);
  let spreadIndex = $state(0);

  let nextPrompt = $state(false);
  let prevPrompt = $state(false);
  let promptTimer: ReturnType<typeof setTimeout> | null = null;

  let pageJumpOpen = $state(false);

  let scrollEl: HTMLDivElement | undefined = $state();
  /** Stage box, used to fit the cropped half of a sliced wide page (`ZoomableBox` + `Canvas`). */
  let stageWidth = $state(0);
  let stageHeight = $state(0);

  const pageEls: (HTMLElement | undefined)[] = [];

  /** Registers each SCROLL page element so `firstVisible` tracking can read its offset. */
  function trackPage(node: HTMLElement, index: number) {
    pageEls[index] = node;
    return {
      update(next: number) {
        pageEls[next] = node;
        if (next !== index) delete pageEls[index];
        index = next;
      },
      destroy() {
        if (pageEls[index] === node) delete pageEls[index];
      },
    };
  }
  let failedPages = $state<Record<number, boolean>>({});
  let imageEpochs = $state<Record<number, number>>({});

  const currentSlice: SinglePageSlice | null = $derived(slices[sliceIndex] ?? null);
  const currentSpread = $derived(spreads[spreadIndex] ?? null);
  const currentSpreadIsWide = $derived(
    currentSpread?.type === 'single' ? isWidePage(aspectRatios, currentSpread.index) : false,
  );
  const dualLeftIndex = $derived(
    currentSpread?.type === 'dual'
      ? isR2L
        ? currentSpread.secondIndex
        : currentSpread.firstIndex
      : -1,
  );
  const dualRightIndex = $derived(
    currentSpread?.type === 'dual'
      ? isR2L
        ? currentSpread.firstIndex
        : currentSpread.secondIndex
      : -1,
  );

  const pageIndex = $derived(
    store.viewMode === 'SCROLL'
      ? firstVisible
      : store.viewMode === 'PAGE'
        ? sliceIndex
        : spreadIndex,
  );
  const maxPages = $derived(
    store.viewMode === 'SCROLL'
      ? totalImages
      : store.viewMode === 'PAGE'
        ? totalSlices
        : totalSpreads,
  );
  /** 1-based page/spread number shown by the slider, the jump dialog and the pill. */
  const currentPage = $derived(
    Math.min(Math.max(pageIndex + 1, 1), Math.max(maxPages, 1)),
  );

  /** `currentRangeText`: `"7 / 20"`, or `"6-7 / 20"` (mirrored for RTL) in dual mode. */
  const rangeText = $derived.by(() => {
    if (totalImages === 0) return '';
    if (store.viewMode === 'SCROLL') return `${Math.min(firstVisible + 1, totalImages)} / ${totalImages}`;
    if (store.viewMode === 'PAGE') return `${Math.min(sliceIndex + 1, totalSlices)} / ${totalSlices}`;
    const spread = currentSpread;
    return spread ? spreadRangeLabel(spread, totalImages, isR2L) : '';
  });

  const episodeTitle = $derived(store.currentEpisode?.title || '만화 보기');

  // --- geometry helpers -------------------------------------------------------------------------

  function clampIndex(value: number, max: number): number {
    if (!Number.isFinite(value)) return 0;
    return Math.min(Math.max(Math.trunc(value), 0), Math.max(max - 1, 0));
  }

  /**
   * Inline style for one half of a sliced wide page (`ImageHalf.LEFT` / `RIGHT`).
   *
   * `halfSliceGeometry` owns the arithmetic (it is unit-tested); this only formats it.
   */
  function halfSliceStyle(ratio: number | undefined, half: ImageHalf): string {
    const geometry = halfSliceGeometry(ratio, half, stageWidth, stageHeight);
    if (!geometry) return '';
    return `width:${geometry.width}px;height:${geometry.height}px;left:${geometry.left}px;top:${geometry.top}px;`;
  }

  // --- navigation (`ReaderPagedLayout.navigate`) ------------------------------------------------

  function clearPrompts(): void {
    nextPrompt = false;
    prevPrompt = false;
    if (promptTimer) {
      clearTimeout(promptTimer);
      promptTimer = null;
    }
  }

  function schedulePromptHide(): void {
    if (promptTimer) clearTimeout(promptTimer);
    promptTimer = setTimeout(() => {
      promptTimer = null;
      nextPrompt = false;
      prevPrompt = false;
    }, PROMPT_TIMEOUT_MS);
  }

  function goToIndex(index: number): void {
    if (store.viewMode === 'SCROLL') {
      const target = clampIndex(index, totalImages);
      firstVisible = target;
      void scrollToPage(target, false);
      return;
    }
    if (store.viewMode === 'PAGE') {
      sliceIndex = clampIndex(index, totalSlices);
      return;
    }
    spreadIndex = clampIndex(index, totalSpreads);
  }

  function goToPage(page: number): void {
    goToIndex(Math.min(Math.max(Math.trunc(page), 1), Math.max(maxPages, 1)) - 1);
  }

  /** `ReaderPagedLayout.navigate(direction)`. */
  function navigate(direction: 'ADVANCE' | 'RETREAT'): void {
    controlsVisible = false;
    const isPromptActive = direction === 'ADVANCE' ? nextPrompt : prevPrompt;
    const decision = resolveBoundaryDecision(pageIndex, maxPages, direction, isPromptActive);

    if (decision.kind === 'page-turn') {
      clearPrompts();
      goToIndex(decision.targetPage);
      return;
    }

    if (decision.kind === 'show-prompt') {
      const isAdvance = direction === 'ADVANCE';
      nextPrompt = isAdvance;
      prevPrompt = !isAdvance;
      schedulePromptHide();
      return;
    }

    // `confirm-navigate`: the prompt was already visible, so this gesture confirms it.
    clearPrompts();
    if (direction === 'ADVANCE') {
      if (store.hasNextEpisode) void store.nextEpisode();
      else store.closeReader();
    } else if (store.hasPrevEpisode) {
      void store.prevEpisode();
    } else {
      store.closeReader();
    }
  }

  /**
   * `onAdvance` / `onRetreat` used by the keyboard, the tap zones and the wheel.
   *
   * Every mode routes through `navigate`, so the edge behaviour (prompt, then confirm to the
   * neighbouring chapter) is identical in SCROLL, PAGE and DUAL. `navigate` translates an ordinary
   * page turn into `goToIndex`, which is one page of scrolling in SCROLL mode.
   */
  function advance(): void {
    navigate('ADVANCE');
  }

  function retreat(): void {
    navigate('RETREAT');
  }

  // --- SCROLL: tracking (`LazyListState.firstVisibleItemIndex`) ---------------------------------

  async function scrollToPage(index: number, smooth: boolean): Promise<void> {
    await tick();
    const container = scrollEl;
    const node = pageEls[clampIndex(index, Math.max(totalImages, 1))];
    if (!container || !node) return;
    const top = node.offsetTop;
    try {
      container.scrollTo({ top, behavior: smooth ? 'smooth' : 'auto' });
    } catch {
      container.scrollTop = top;
    }
  }

  /** True once at least one page reports a real box (a headless DOM reports all-zero boxes). */
  function hasMeasurablePages(): boolean {
    return pageEls.some((node) => !!node && (node.offsetHeight > 0 || node.offsetTop > 0));
  }

  function handleScroll(): void {
    const container = scrollEl;
    if (!container || totalImages === 0) return;
    // Without layout there is no "first visible page"; never overwrite the tracked index with 0.
    if (!hasMeasurablePages()) return;
    const scrollTop = container.scrollTop;
    let visible = 0;
    for (let index = 0; index < pageEls.length; index += 1) {
      const node = pageEls[index];
      if (!node) continue;
      visible = index;
      if (node.offsetTop + node.offsetHeight > scrollTop + 1) break;
    }
    if (visible !== firstVisible) {
      firstVisible = visible;
      controlsVisible = false;
    }
  }

  // --- tap zones (lines 473-482) ----------------------------------------------------------------

  let pointerStart: { x: number; y: number } | null = null;
  let pointerDragged = false;

  function handlePointerDown(event: PointerEvent): void {
    pointerStart = { x: event.clientX, y: event.clientY };
    pointerDragged = false;
  }

  function handlePointerMove(event: PointerEvent): void {
    if (!pointerStart) return;
    const dx = event.clientX - pointerStart.x;
    const dy = event.clientY - pointerStart.y;
    if (Math.hypot(dx, dy) > TAP_SLOP_PX) pointerDragged = true;
  }

  function consumeDrag(): boolean {
    if (!pointerDragged) return false;
    pointerDragged = false;
    return true;
  }

  /**
   * `handleTap` from `ReaderPagedLayout`:
   * `tapX < width * 0.38` → advance when RTL else retreat;
   * `tapX > width * 0.62` → the reverse;
   * otherwise toggle the overlay and clear the boundary prompts.
   */
  function handleStageClick(event: MouseEvent): void {
    if (consumeDrag()) return;
    const target = event.target as HTMLElement | null;
    // Buttons inside the scroll footer must keep their own action.
    if (target?.closest('button, a, input, select, textarea, [role="dialog"]')) return;

    const element = event.currentTarget as HTMLElement | null;
    const rect = element?.getBoundingClientRect();
    const width = rect && rect.width > 0 ? rect.width : window.innerWidth || 1;
    const x = event.clientX - (rect?.left ?? 0);
    const ratio = x / width;

    if (ratio < 0.38) {
      if (isR2L) advance();
      else retreat();
      return;
    }
    if (ratio > 0.62) {
      if (isR2L) retreat();
      else advance();
      return;
    }
    controlsVisible = !controlsVisible;
    clearPrompts();
  }

  // --- wheel paging (`desktopDiscreteScrollPaging` parity) ---------------------------------------

  let isWheelLatched = false;
  let lastWheelTriggerTime = 0;
  let wheelIdleTimer: ReturnType<typeof setTimeout> | null = null;
  let wheelStreamAxis: 'HORIZONTAL' | 'VERTICAL' | 'UNDECIDED' = 'UNDECIDED';

  function handleWheel(event: WheelEvent): void {
    // SCROLL owns its own scrolling; zoom chords belong to the browser/ZoomableBox.
    if (store.viewMode === 'SCROLL') return;
    if (event.ctrlKey || event.metaKey) return;

    const deltaX = event.deltaX;
    const deltaY = event.deltaY;
    const absX = Math.abs(deltaX);
    const absY = Math.abs(deltaY);

    const now = Date.now();
    const elapsed = now - lastWheelTriggerTime;

    // 1. Reset idle timer: guarantees unlatch after stream has stopped AND cooldown elapsed
    // (parity with `CommonWidgets.kt` lines 285-294 `idleJob`)
    if (wheelIdleTimer) {
      clearTimeout(wheelIdleTimer);
      wheelIdleTimer = null;
    }
    const remainingCooldown = Math.max(0, SCROLL_LATCH_COOLDOWN_MS - elapsed);
    wheelIdleTimer = setTimeout(() => {
      isWheelLatched = false;
      wheelStreamAxis = 'UNDECIDED';
      wheelIdleTimer = null;
    }, Math.max(SCROLL_STREAM_IDLE_MS, remainingCooldown));

    // 2. Lock the gesture axis on the first clearly dominant delta (`resolveScrollGestureAxis`)
    if (wheelStreamAxis === 'UNDECIDED') {
      if (Math.max(absX, absY) >= SCROLL_IDLE_THRESHOLD) {
        if (absX - absY > SCROLL_IDLE_THRESHOLD) {
          wheelStreamAxis = 'HORIZONTAL';
        } else if (absY - absX > SCROLL_IDLE_THRESHOLD) {
          wheelStreamAxis = 'VERTICAL';
        }
      }
    }

    // 3. If momentum decayed below idle threshold and cooldown has passed, release latch
    // (`CommonWidgets.kt` lines 324-330)
    if (absX < SCROLL_IDLE_THRESHOLD && absY < SCROLL_IDLE_THRESHOLD) {
      if (elapsed >= SCROLL_LATCH_COOLDOWN_MS) {
        isWheelLatched = false;
      }
      return;
    }

    // 4. If currently latched, suppress continuation/inertia events from the same swipe
    // (`CommonWidgets.kt` lines 332-336)
    if (isWheelLatched) {
      event.preventDefault();
      return;
    }

    // 5. Only trigger new discrete action if minimum cooldown elapsed
    // (`CommonWidgets.kt` lines 338-341)
    if (elapsed < SCROLL_LATCH_COOLDOWN_MS) {
      event.preventDefault();
      return;
    }

    // 6. Must meet threshold
    if (absX < SCROLL_PAGE_THRESHOLD && absY < SCROLL_PAGE_THRESHOLD) return;

    // 7. Resolve direction (`ReaderDomain.resolveScrollDirection`):
    // Horizontal streams follow reading direction, vertical streams treat down as forward.
    let direction: 'advance' | 'retreat' | null = null;
    if (absX >= absY) {
      const isForward = isR2L ? deltaX < 0 : deltaX > 0;
      direction = isForward ? 'advance' : 'retreat';
    } else {
      direction = deltaY > 0 ? 'advance' : 'retreat';
    }

    if (!direction) return;

    // 8. Latch immediately and trigger discrete action exactly once
    // (`CommonWidgets.kt` lines 360-369)
    isWheelLatched = true;
    lastWheelTriggerTime = now;
    event.preventDefault();

    if (direction === 'advance') {
      advance();
    } else {
      retreat();
    }
  }

  // --- keyboard (lines 124-173) -----------------------------------------------------------------

  function isEditableTarget(target: EventTarget | null): boolean {
    if (!(target instanceof HTMLElement)) return false;
    if (target.isContentEditable) return true;
    if (target.closest('[contenteditable="true"]')) return true;
    return /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName);
  }

  function toggleFullscreen(): void {
    store.toggleFullscreen();
    try {
      if (store.isFullscreen) {
        void document.documentElement.requestFullscreen?.()?.catch(() => undefined);
      } else if (document.fullscreenElement) {
        void document.exitFullscreen?.()?.catch(() => undefined);
      }
    } catch {
      // Extension surfaces often cannot use the Fullscreen API; the CSS class still applies.
    }
  }

  function handleKeydown(event: KeyboardEvent): void {
    if (isEditableTarget(event.target)) return;

    const key = event.key;
    const code = event.code;
    const isCmd = event.metaKey || event.ctrlKey;
    const isAlt = event.altKey;
    const hasModifier = isCmd || isAlt;

    if (key === 'Escape') {
      event.preventDefault();
      if (pageJumpOpen) pageJumpOpen = false;
      else store.closeReader();
      return;
    }
    // The dialog owns its own Enter/arrow keys while it is open.
    if (pageJumpOpen) return;

    const isBack =
      hasModifier && (code === 'ArrowLeft' || code === 'BracketLeft' || key === '[');
    const isForward =
      hasModifier && (code === 'ArrowRight' || code === 'BracketRight' || key === ']');

    if (isBack) {
      event.preventDefault();
      store.closeReader();
      return;
    }
    if (isForward) {
      event.preventDefault();
      if (store.hasNextEpisode) void store.nextEpisode();
      return;
    }
    if (!hasModifier && (code === 'KeyF' || key === 'f' || key === 'F')) {
      event.preventDefault();
      toggleFullscreen();
      return;
    }
    if (!hasModifier && (code === 'ArrowRight' || key === 'ArrowRight')) {
      event.preventDefault();
      if (isR2L) retreat();
      else advance();
      return;
    }
    if (!hasModifier && (code === 'ArrowLeft' || key === 'ArrowLeft')) {
      event.preventDefault();
      if (isR2L) advance();
      else retreat();
      return;
    }
    if (!hasModifier && (code === 'Space' || key === ' ' || code === 'PageDown' || key === 'PageDown')) {
      event.preventDefault();
      advance();
      return;
    }
    if (!hasModifier && (code === 'PageUp' || key === 'PageUp')) {
      event.preventDefault();
      retreat();
    }
  }

  // --- images -----------------------------------------------------------------------------------

  function onImageLoad(event: Event, index: number): void {
    const img = event.currentTarget as HTMLImageElement;
    const ratio = imageAspectRatio(img.naturalWidth, img.naturalHeight);
    if (ratio === null) return;
    store.recordImageAspectRatio(index, img.naturalWidth, img.naturalHeight);
    if (failedPages[index]) failedPages = { ...failedPages, [index]: false };
  }

  function onImageError(index: number): void {
    failedPages = { ...failedPages, [index]: true };
  }

  function retryImage(index: number): void {
    failedPages = { ...failedPages, [index]: false };
    imageEpochs = { ...imageEpochs, [index]: (imageEpochs[index] ?? 0) + 1 };
  }

  // --- restore / save (LaunchedEffect pairs) ----------------------------------------------------

  /** `restoredWrId` guard: one restore per (episode, view mode) pair. */
  let restoredKey: string | null = null;
  /** Last page handed to `savePage`, so the write effect cannot loop on its own mutation. */
  let lastSavedPage = -1;

  function restoreFromProgress(target: number): void {
    if (store.viewMode === 'SCROLL') {
      const index = clampIndex(target, Math.max(totalImages, 1));
      firstVisible = index;
      lastSavedPage = index;
      void scrollToPage(index, false);
      return;
    }
    if (store.viewMode === 'PAGE') {
      // `slices.indexOfFirst { it.imageIndex >= target }`, clamped into the slice range.
      let index = slices.findIndex((slice) => slice.imageIndex >= target);
      if (index < 0) index = 0;
      sliceIndex = clampIndex(index, totalSlices);
      lastSavedPage = slices[sliceIndex]?.imageIndex ?? -1;
      return;
    }
    // `spread.secondIndex >= target` for a dual spread, `spread.index >= target` for a single.
    let index = spreads.findIndex((spread) =>
      spread.type === 'single' ? spread.index >= target : spread.secondIndex >= target,
    );
    if (index < 0) index = 0;
    spreadIndex = clampIndex(index, totalSpreads);
    const spread = spreads[spreadIndex];
    lastSavedPage = spread ? savedPageForSpread(spread, isR2L) : -1;
  }

  $effect(() => {
    const wrId = store.currentEpisode?.wrId ?? null;
    const mode = store.viewMode;
    const count = totalImages;
    if (!wrId || count === 0) return;
    const key = `${wrId}:${mode}`;
    if (key === restoredKey) return;
    restoredKey = key;

    // A new chapter (or a mode switch) starts from the persisted page.
    clearPrompts();
    failedPages = {};
    imageEpochs = {};
    const target = store.currentEpisode?.lastReadPage ?? 0;
    untrack(() => restoreFromProgress(target));
  });

  // Reset the restore guard when the chapter closes so re-opening it restores again.
  $effect(() => {
    const wrId = store.currentEpisode?.wrId ?? null;
    if (wrId === null) {
      restoredKey = null;
      lastSavedPage = -1;
    }
  });

  /** Keeps the active image when `SLICE`/`FIT` or the reading direction re-slices the chapter. */
  let previousSlices: SinglePageSlice[] = [];
  $effect(() => {
    const current = slices;
    if (current === previousSlices) return;
    const old = previousSlices[sliceIndex];
    previousSlices = current;
    if (!old) return;
    let index = current.findIndex(
      (slice) => slice.imageIndex === old.imageIndex && slice.half === old.half,
    );
    if (index < 0) index = current.findIndex((slice) => slice.imageIndex === old.imageIndex);
    if (index >= 0) sliceIndex = clampIndex(index, Math.max(current.length, 1));
  });

  /** Keeps the active page when freshly measured ratios regroup the spreads. */
  let previousSpreads: typeof spreads = [];
  $effect(() => {
    const current = spreads;
    if (current === previousSpreads) return;
    const old = previousSpreads[spreadIndex];
    previousSpreads = current;
    if (!old) return;
    const oldPage = savedPageForSpread(old, isR2L);
    let index = current.findIndex((spread) => {
      const start = spread.type === 'single' ? spread.index : spread.firstIndex;
      const end = spread.type === 'single' ? spread.index : spread.secondIndex;
      return oldPage >= start && oldPage <= end;
    });
    if (index < 0) index = 0;
    spreadIndex = clampIndex(index, Math.max(current.length, 1));
  });

  /**
   * `LaunchedEffect(pagerState.currentPage, slices) { viewModel.savePage(slice.imageIndex) }`.
   *
   * Every dependency is read reactively here; only the write itself is untracked so `savePage`'s
   * update of `currentEpisode` cannot feed back into this effect.
   */
  $effect(() => {
    const mode = store.viewMode;
    const count = totalImages;
    const sliceList = slices;
    const spreadList = spreads;
    const rtl = isR2L;
    const activeIndex = mode === 'SCROLL' ? firstVisible : mode === 'PAGE' ? sliceIndex : spreadIndex;

    let page = -1;
    if (mode === 'SCROLL') {
      page = activeIndex;
    } else if (mode === 'PAGE') {
      page = sliceList[activeIndex]?.imageIndex ?? -1;
    } else {
      const spread = spreadList[activeIndex];
      page = spread ? savedPageForSpread(spread, rtl) : -1;
    }

    if (count === 0 || page < 0 || page >= count) return;
    if (page === lastSavedPage) return;
    lastSavedPage = page;
    untrack(() => store.savePage(page));
  });

  // --- lifecycle --------------------------------------------------------------------------------

  const onFullscreenChange = (): void => {
    if (typeof document === 'undefined') return;
    const active = document.fullscreenElement !== null;
    // Only adopt the platform state when it disagrees, so `F` is not fought by a stale event.
    if (store.isFullscreen !== active) store.isFullscreen = active;
  };

  if (typeof document !== 'undefined') {
    document.addEventListener('fullscreenchange', onFullscreenChange);
  }

  onDestroy(() => {
    if (promptTimer) clearTimeout(promptTimer);
    if (wheelIdleTimer) clearTimeout(wheelIdleTimer);
    if (typeof document !== 'undefined') {
      document.removeEventListener('fullscreenchange', onFullscreenChange);
    }
  });

  function setMode(mode: ViewMode): void {
    if (store.viewMode === mode) return;
    store.setViewMode(mode);
    // `rememberPagerState(initialPage = …)` in the Kotlin per-mode composables.
    restoredKey = null;
  }

</script>

{#snippet pageImage(index: number, className: string, style: string)}
  {#if images[index] && !failedPages[index]}
    {#key imageEpochs[index] ?? 0}
      <img
        class={className}
        {style}
        src={images[index]}
        alt={`페이지 ${index + 1}`}
        draggable="false"
        decoding="async"
        onload={(event) => onImageLoad(event, index)}
        onerror={() => onImageError(index)}
      />
    {/key}
  {:else if failedPages[index]}
    <button type="button" class="image-failed" onclick={() => retryImage(index)}>
      <span>{ERROR_NO_IMAGES}</span>
      <span class="image-failed-hint">{LABEL_RETRY}</span>
    </button>
  {/if}
{/snippet}

<svelte:window onkeydown={handleKeydown} />

<div
  class="reader-pane"
  class:fullscreen={store.isFullscreen}
  data-view-mode={store.viewMode}
  data-direction={store.readDirection}
  class:controls-visible={controlsVisible}
>
  <div
    class="reader-stage"
    bind:clientWidth={stageWidth}
    bind:clientHeight={stageHeight}
    onclick={handleStageClick}
    onpointerdown={handlePointerDown}
    onpointermove={handlePointerMove}
    onwheel={handleWheel}
    role="presentation"
  >
    {#if store.viewMode === 'SCROLL'}
      <!-- ReaderScrollView: continuous vertical column, 65% width. -->
      <div class="scroll-view" bind:this={scrollEl} onscroll={handleScroll}>
        {#each images as url, index (index + '-' + url)}
          <div class="scroll-page" use:trackPage={index} data-page-index={index}>
            {@render pageImage(index, 'scroll-image', '')}
          </div>
        {/each}

        <div class="scroll-footer">
          {#if store.hasPrevEpisode}
            <button type="button" class="footer-button tonal" onclick={() => void store.prevEpisode()}>
              <ChevronLeft size={15} />
              <span>{LABEL_PREV_EPISODE}</span>
            </button>
          {/if}
          {#if store.hasNextEpisode}
            <button type="button" class="footer-button primary" onclick={() => void store.nextEpisode()}>
              <span>{LABEL_NEXT_EPISODE}</span>
              <ChevronRight size={15} />
            </button>
          {/if}
        </div>
      </div>
    {:else if store.viewMode === 'PAGE'}
      <!-- ReaderSingleView: Fit shows the whole page, Slice cuts a wide page in two halves. -->
      <div class="page-view">
        {#if currentSlice && images[currentSlice.imageIndex]}
          {#if currentSlice.half === 'FULL' || aspectRatios[currentSlice.imageIndex] === undefined}
            <div class="page-slice">
              {@render pageImage(currentSlice.imageIndex, 'page-image', '')}
            </div>
          {:else}
            <div class="page-slice half">
              {@render pageImage(
                currentSlice.imageIndex,
                'page-image half',
                halfSliceStyle(aspectRatios[currentSlice.imageIndex], currentSlice.half),
              )}
            </div>
          {/if}
        {/if}
      </div>
    {:else}
      <!-- ReaderDualView: smart wide-spread grouping, physical order follows the direction. -->
      <div class="dual-view">
        {#if currentSpread?.type === 'single'}
          {#if currentSpreadIsWide}
            <div class="spread-single">
              {@render pageImage(currentSpread.index, 'dual-image', '')}
            </div>
          {:else}
            <div class="spread-row">
              {#if isR2L}
                <div class="slot empty" aria-hidden="true"></div>
                <div class="slot spine-start">
                  {@render pageImage(currentSpread.index, 'dual-image', '')}
                </div>
              {:else}
                <div class="slot spine-end">
                  {@render pageImage(currentSpread.index, 'dual-image', '')}
                </div>
                <div class="slot empty" aria-hidden="true"></div>
              {/if}
            </div>
          {/if}
        {:else if currentSpread?.type === 'dual'}
          <div class="spread-row">
            <div class="slot spine-end">
              {@render pageImage(dualLeftIndex, 'dual-image', '')}
            </div>
            <div class="slot spine-start">
              {@render pageImage(dualRightIndex, 'dual-image', '')}
            </div>
          </div>
        {/if}
      </div>
    {/if}
  </div>

  <!-- Loading: `readerLoading && readerImages.isEmpty()`. -->
  {#if store.readerLoading && totalImages === 0}
    <div class="reader-loading" role="status" aria-live="polite">
      <RefreshCw size={30} class="reader-spinner" />
      <p class="reader-loading-text">{LOADING_TEXT}</p>
    </div>
  {/if}

  <!-- Error: `readerError != null && readerImages.isEmpty()`, retried through `openEpisode`. -->
  {#if store.readerError && totalImages === 0}
    <div class="reader-error" role="alert">
      <p class="reader-error-text">{store.readerError}</p>
      <button
        type="button"
        class="reader-retry"
        onclick={() => {
          const episode = store.currentEpisode;
          if (episode) void store.openEpisode(episode);
        }}
      >
        <RefreshCw size={14} />
        <span>{LABEL_RETRY}</span>
      </button>
    </div>
  {/if}

  <!-- `readerToastMessage`: transient, top centre. -->
  {#if store.readerToastMessage}
    <div class="reader-toast" role="status">{store.readerToastMessage}</div>
  {/if}

  <!-- Boundary prompts (`EpisodeBoundaryPrompt`), bottom centre above the bars. -->
  {#if prevPrompt}
    <button type="button" class="boundary-prompt prev" onclick={() => navigate('RETREAT')}>
      <span class="prompt-title">
        {store.hasPrevEpisode ? PROMPT_PREV_TITLE : PROMPT_FIRST_EPISODE}
      </span>
      <span class="prompt-subtitle">
        {store.hasPrevEpisode ? PROMPT_PREV_HINT : PROMPT_CLOSE_HINT}
      </span>
    </button>
  {/if}

  {#if nextPrompt}
    <button type="button" class="boundary-prompt next" onclick={() => navigate('ADVANCE')}>
      <span class="prompt-title">
        {store.hasNextEpisode ? PROMPT_NEXT_TITLE : PROMPT_LAST_EPISODE}
      </span>
      <span class="prompt-subtitle">
        {store.hasNextEpisode ? PROMPT_NEXT_HINT : PROMPT_CLOSE_HINT}
      </span>
    </button>
  {/if}

  <!--
    Immersive pill: hidden until the overlay is opened. It reports the current surface position
    (`${currentPage} / ${maxPages}`); DUAL also exposes the two-page range through `title`/aria.
  -->
  {#if !controlsVisible && maxPages > 0}
    <div
      class="page-pill"
      data-testid="page-pill"
      title={rangeText}
      aria-label={rangeText !== '' ? rangeText : `${currentPage} / ${maxPages}`}
    >
      {currentPage} / {maxPages}
    </div>
  {/if}

  <!-- Overlay top bar (slides in from the top). -->
  {#if controlsVisible}
    <header class="top-bar overlay">
      <button type="button" class="top-back" title="뒤로" aria-label="뒤로" onclick={() => store.closeReader()}>
        <ArrowLeft size={17} />
      </button>

      <h1 class="top-title" title={episodeTitle}>{episodeTitle}</h1>

      <div class="top-controls">
        <div class="chip-group" role="group" aria-label="보기 모드">
          <button
            type="button"
            class="chip"
            class:active={store.viewMode === 'SCROLL'}
            aria-pressed={store.viewMode === 'SCROLL'}
            onclick={() => setMode('SCROLL')}>스크롤</button
          >
          <button
            type="button"
            class="chip"
            class:active={store.viewMode === 'PAGE'}
            aria-pressed={store.viewMode === 'PAGE'}
            onclick={() => setMode('PAGE')}>단면</button
          >
          <button
            type="button"
            class="chip"
            class:active={store.viewMode === 'DUAL'}
            aria-pressed={store.viewMode === 'DUAL'}
            onclick={() => setMode('DUAL')}>양면</button
          >
        </div>

        {#if store.viewMode !== 'SCROLL'}
          <div class="chip-group" role="group" aria-label="읽기 방향">
            <button
              type="button"
              class="chip"
              class:active={isR2L}
              aria-pressed={isR2L}
              onclick={() => store.setReadDirection('RIGHT_TO_LEFT')}>우→좌</button
            >
            <button
              type="button"
              class="chip"
              class:active={!isR2L}
              aria-pressed={!isR2L}
              onclick={() => store.setReadDirection('LEFT_TO_RIGHT')}>좌→우</button
            >
          </div>
        {/if}

        {#if store.viewMode === 'PAGE'}
          <div class="chip-group" role="group" aria-label="분할 모드">
            <button
              type="button"
              class="chip"
              class:active={store.splitMode === 'FIT'}
              aria-pressed={store.splitMode === 'FIT'}
              onclick={() => store.setSplitMode('FIT')}>Fit</button
            >
            <button
              type="button"
              class="chip"
              class:active={store.splitMode === 'SLICE'}
              aria-pressed={store.splitMode === 'SLICE'}
              onclick={() => store.setSplitMode('SLICE')}>Slice</button
            >
          </div>
        {/if}

        <button
          type="button"
          class="icon-button"
          title={store.isFullscreen ? '전체 화면 종료 (F)' : '전체 화면 (F)'}
          aria-label={store.isFullscreen ? '전체 화면 종료' : '전체 화면'}
          onclick={toggleFullscreen}
        >
          {#if store.isFullscreen}
            <Minimize2 size={16} />
          {:else}
            <Maximize2 size={16} />
          {/if}
        </button>
      </div>
    </header>
  {/if}

  <!-- Overlay bottom bar (`ReaderBottomBar`, slides in from the bottom). -->
  {#if controlsVisible}
    <footer class="bottom-bar">
      {#if maxPages > 1}
        <div class="progress-row">
          <span class="range-text">{rangeText}</span>
          <input
            class="progress-slider"
            type="range"
            min="1"
            max={Math.max(maxPages, 1)}
            step="1"
            value={currentPage}
            aria-label="페이지"
            aria-valuetext={rangeText}
            oninput={(event) => goToPage(Number((event.currentTarget as HTMLInputElement).value))}
          />
        </div>
      {/if}

      <div class="actions-row">
        <button
          type="button"
          class="bar-button outlined prev-episode"
          disabled={!store.hasPrevEpisode}
          onclick={() => void store.prevEpisode()}
        >
          <ChevronLeft size={15} />
          <span>{LABEL_PREV_EPISODE}</span>
        </button>

        <button type="button" class="bar-button outlined page-jump-button" onclick={() => (pageJumpOpen = true)}>
          <Hash size={14} />
          <span>{LABEL_PAGE_JUMP}</span>
        </button>

        <button type="button" class="bar-button filled close-reader" onclick={() => store.closeReader()}>
          {LABEL_EPISODE_LIST}
        </button>

        <button
          type="button"
          class="bar-button outlined next-episode"
          disabled={!store.hasNextEpisode}
          onclick={() => void store.nextEpisode()}
        >
          <span>{LABEL_NEXT_EPISODE}</span>
          <ChevronRight size={15} />
        </button>
      </div>
    </footer>
  {/if}

  {#if pageJumpOpen}
    <PageJumpDialog
      current={currentPage}
      last={maxPages}
      onDismiss={() => (pageJumpOpen = false)}
      onConfirm={(page) => goToPage(page)}
    />
  {/if}
</div>

<style>
  /* Pure black canvas, zinc monochrome accents (`MonochromeTheme`). */
  .reader-pane {
    position: relative;
    display: flex;
    flex-direction: column;
    width: 100%;
    height: 100%;
    min-height: 0;
    overflow: hidden;
    background: #000000;
    color: #f4f4f5;
    -webkit-user-select: none;
    user-select: none;
  }

  /* `DesktopViewModel.toggleFullscreen` hides the rest of the shell so the page fills the window. */
  .reader-pane.fullscreen {
    position: fixed;
    inset: 0;
    z-index: 100;
  }

  .reader-stage {
    position: relative;
    flex: 1 1 auto;
    min-height: 0;
    overflow: hidden;
    background: #000000;
    touch-action: manipulation;
    cursor: pointer;
  }

  /* --- SCROLL ---------------------------------------------------------------------------- */

  .scroll-view {
    position: absolute;
    inset: 0;
    overflow-x: hidden;
    overflow-y: auto;
    overscroll-behavior: contain;
    scrollbar-width: thin;
    scrollbar-color: #3f3f46 transparent;
  }

  .scroll-page {
    display: flex;
    justify-content: center;
    width: 100%;
    padding: 2px 0;
  }

  .scroll-image {
    display: block;
    width: 65%;
    max-width: 100%;
    height: auto;
    object-fit: contain;
    background: #0a0a0a;
    -webkit-user-drag: none;
  }

  .scroll-footer {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 16px;
    padding: 32px;
  }

  .footer-button {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 8px 16px;
    border-radius: 8px;
    font: 600 12.5px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .footer-button.tonal {
    border: 1px solid #3f3f46;
    background: #27272a;
    color: #e4e4e7;
  }

  .footer-button.primary {
    border: 1px solid #52525b;
    background: #e4e4e7;
    color: #09090b;
  }

  /* --- PAGE ----------------------------------------------------------------------------- */

  .page-view {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .page-slice {
    position: relative;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    overflow: hidden;
  }

  .page-image {
    max-width: 100%;
    max-height: 100%;
    object-fit: contain;
    -webkit-user-drag: none;
  }

  .page-image.half {
    position: absolute;
    max-width: none;
    max-height: none;
    object-fit: fill;
  }

  /* --- DUAL ----------------------------------------------------------------------------- */

  .dual-view {
    position: absolute;
    inset: 0;
    display: flex;
    padding: 4px 8px;
  }

  .spread-row {
    display: flex;
    flex: 1 1 auto;
    align-items: stretch;
    width: 100%;
    min-width: 0;
    min-height: 0;
  }

  .spread-single {
    display: flex;
    flex: 1 1 auto;
    align-items: center;
    justify-content: center;
    width: 100%;
    min-width: 0;
    min-height: 0;
  }

  .slot {
    display: flex;
    flex: 1 1 0;
    align-items: center;
    justify-content: center;
    min-width: 0;
    min-height: 0;
  }

  .slot.spine-start {
    justify-content: flex-start;
  }

  .slot.spine-end {
    justify-content: flex-end;
  }

  .dual-image {
    max-width: 100%;
    max-height: 100%;
    object-fit: contain;
    -webkit-user-drag: none;
  }

  /* --- broken image --------------------------------------------------------------------- */

  .image-failed {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 6px;
    min-width: 220px;
    min-height: 140px;
    margin: auto;
    padding: 16px;
    border: 1px dashed #3f3f46;
    border-radius: 12px;
    background: rgba(24, 24, 27, 0.5);
    color: #a1a1aa;
    font: 500 12px/1.4 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .image-failed:hover {
    border-color: #52525b;
    color: #d4d4d8;
  }

  .image-failed-hint {
    color: #71717a;
    font-size: 11px;
  }

  /* --- loading / error / toast ------------------------------------------------------------ */

  .reader-loading,
  .reader-error {
    position: absolute;
    inset: 0;
    z-index: 40;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 10px;
    background: rgba(0, 0, 0, 0.85);
    text-align: center;
  }

  .reader-spinner {
    color: #71717a;
    animation: reader-spin 1s linear infinite;
  }

  @keyframes reader-spin {
    to {
      transform: rotate(360deg);
    }
  }

  .reader-loading-text {
    margin: 0;
    font-size: 13px;
    color: #a1a1aa;
  }

  .reader-error-text {
    margin: 0;
    max-width: 420px;
    padding: 0 24px;
    font-size: 13.5px;
    font-weight: 600;
    color: #e4e4e7;
    overflow-wrap: anywhere;
  }

  .reader-retry {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 7px 14px;
    border: 1px solid #3f3f46;
    border-radius: 8px;
    background: #18181b;
    color: #e4e4e7;
    font: 600 12px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .reader-retry:hover {
    border-color: #52525b;
    background: #27272a;
  }

  .reader-toast {
    position: absolute;
    top: 28px;
    left: 50%;
    z-index: 60;
    transform: translateX(-50%);
    padding: 8px 18px;
    border-radius: 20px;
    background: rgba(63, 63, 70, 0.92);
    color: #fafafa;
    font-size: 12px;
    font-weight: 600;
    box-shadow: 0 8px 24px rgba(0, 0, 0, 0.5);
    animation: toast-in 0.18s ease-out;
  }

  @keyframes toast-in {
    from {
      opacity: 0;
      transform: translate(-50%, -10px);
    }
    to {
      opacity: 1;
      transform: translate(-50%, 0);
    }
  }

  /* --- boundary prompt ------------------------------------------------------------------- */

  .boundary-prompt {
    position: absolute;
    bottom: 72px;
    left: 50%;
    z-index: 55;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 2px;
    padding: 12px 20px;
    border: 1px solid #3f3f46;
    border-radius: 16px;
    background: rgba(39, 39, 42, 0.94);
    color: #fafafa;
    text-align: center;
    cursor: pointer;
    transform: translateX(-50%);
    box-shadow: 0 12px 32px rgba(0, 0, 0, 0.55);
    animation: prompt-in 0.16s ease-out;
  }

  .boundary-prompt:hover {
    background: rgba(63, 63, 70, 0.96);
  }

  @keyframes prompt-in {
    from {
      opacity: 0;
      transform: translate(-50%, 6px) scale(0.94);
    }
    to {
      opacity: 1;
      transform: translate(-50%, 0) scale(1);
    }
  }

  .prompt-title {
    font-size: 13px;
    font-weight: 700;
  }

  .prompt-subtitle {
    font-size: 11px;
    color: #a1a1aa;
  }

  /* --- immersive pill -------------------------------------------------------------------- */

  .page-pill {
    position: absolute;
    bottom: 16px;
    left: 50%;
    z-index: 30;
    padding: 6px 12px;
    border: 1px solid rgba(39, 39, 42, 0.9);
    border-radius: 8px;
    background: rgba(24, 24, 27, 0.8);
    color: #e4e4e7;
    font-size: 11.5px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
    transform: translateX(-50%);
    pointer-events: none;
  }

  /* --- overlay top bar ------------------------------------------------------------------- */

  .top-bar {
    position: absolute;
    top: 0;
    right: 0;
    left: 0;
    z-index: 50;
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 8px 16px;
    background: rgba(24, 24, 27, 0.94);
    border-bottom: 1px solid rgba(39, 39, 42, 0.9);
    backdrop-filter: blur(6px);
    animation: top-bar-in 0.18s ease-out;
  }

  @keyframes top-bar-in {
    from {
      opacity: 0;
      transform: translateY(-100%);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }

  .top-back {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border: 1px solid transparent;
    border-radius: 8px;
    background: transparent;
    color: #e4e4e7;
    cursor: pointer;
  }

  .top-back:hover {
    background: rgba(63, 63, 70, 0.5);
  }

  .top-title {
    flex: 1 1 auto;
    min-width: 0;
    margin: 0;
    padding: 0 4px;
    font-size: 13.5px;
    font-weight: 700;
    color: #f4f4f5;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .top-controls {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 8px;
  }

  .chip-group {
    display: inline-flex;
    align-items: center;
    gap: 2px;
    padding: 2px;
    border-radius: 8px;
    background: rgba(39, 39, 42, 0.6);
  }

  .chip {
    padding: 4px 10px;
    border: none;
    border-radius: 6px;
    background: transparent;
    color: #a1a1aa;
    font: 600 11.5px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .chip:hover {
    color: #f4f4f5;
  }

  .chip.active {
    background: #e4e4e7;
    color: #09090b;
  }

  .icon-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 30px;
    border: 1px solid #3f3f46;
    border-radius: 8px;
    background: transparent;
    color: #a1a1aa;
    cursor: pointer;
  }

  .icon-button:hover {
    border-color: #52525b;
    color: #f4f4f5;
  }

  /* --- overlay bottom bar ---------------------------------------------------------------- */

  .bottom-bar {
    position: absolute;
    right: 0;
    bottom: 0;
    left: 0;
    z-index: 50;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
    padding: 12px 24px;
    background: rgba(24, 24, 27, 0.94);
    border-top: 1px solid rgba(39, 39, 42, 0.9);
    backdrop-filter: blur(6px);
    animation: bottom-bar-in 0.18s ease-out;
  }

  @keyframes bottom-bar-in {
    from {
      opacity: 0;
      transform: translateY(100%);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }

  .progress-row {
    display: flex;
    align-items: center;
    width: min(680px, 100%);
    gap: 12px;
  }

  .range-text {
    flex: 0 0 auto;
    color: #e4e4e7;
    font-size: 11.5px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
  }

  .progress-slider {
    flex: 1 1 auto;
    height: 4px;
    accent-color: #d4d4d8;
    cursor: pointer;
  }

  .actions-row {
    display: flex;
    align-items: center;
    width: min(680px, 100%);
    gap: 10px;
  }

  .bar-button {
    display: inline-flex;
    flex: 1 1 0;
    align-items: center;
    justify-content: center;
    gap: 6px;
    padding: 7px 10px;
    border-radius: 8px;
    font: 600 12px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
    white-space: nowrap;
  }

  .bar-button.outlined {
    border: 1px solid #3f3f46;
    background: transparent;
    color: #e4e4e7;
  }

  .bar-button.outlined:hover:not(:disabled) {
    border-color: #52525b;
    background: rgba(39, 39, 42, 0.6);
  }

  .bar-button.filled {
    border: 1px solid #52525b;
    background: #e4e4e7;
    color: #09090b;
  }

  .bar-button.filled:hover {
    background: #fafafa;
  }

  .bar-button:disabled {
    opacity: 0.35;
    cursor: default;
  }
</style>
