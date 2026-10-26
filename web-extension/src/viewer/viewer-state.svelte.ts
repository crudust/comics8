import type {
  FitMode,
  PageImage,
  ReadingDirection,
  SpreadPair,
  ViewerMode,
} from './types';

/**
 * Aspect ratio (width / height) above which a page is treated as an already merged
 * two-page panorama. Such pages are always shown alone ("Smart Wide Spread").
 */
export const WIDE_PAGE_ASPECT_RATIO = 1.25;

/** Default auto-scroll speed in CSS pixels per second. */
export const DEFAULT_AUTO_SCROLL_SPEED = 60;

/** Lower / upper clamp for the user-selectable auto-scroll speed (px/s). */
export const MIN_AUTO_SCROLL_SPEED = 10;
export const MAX_AUTO_SCROLL_SPEED = 600;

const clamp = (value: number, min: number, max: number): number =>
  Math.min(Math.max(value, min), max);

/**
 * Measured aspect ratio (width / height), or `null` while the bitmap is still undecoded.
 */
export function pageAspectRatio(page: PageImage): number | null {
  const { width, height } = page;
  if (!width || !height || width <= 0 || height <= 0) return null;
  return width / height;
}

/**
 * True when a page is a wide panorama that must never be paired with a neighbour.
 * Explicit source metadata wins over measured dimensions; unknown dimensions are
 * assumed portrait so a freshly opened chapter still pairs pages immediately.
 */
export function isWidePage(page: PageImage, threshold = WIDE_PAGE_ASPECT_RATIO): boolean {
  if (page.isWide === true) return true;
  const ratio = pageAspectRatio(page);
  return ratio !== null && ratio > threshold;
}

/**
 * Groups pages into spreads, preserving reading order inside each spread.
 *
 * Mirrors the canonical Kotlin implementation (`buildDualSpreads`): consecutive portrait
 * pages are paired, a wide page is emitted alone, and a portrait page that is followed by
 * a wide page (or is the last page) is emitted alone so it never gets straddled.
 */
export function buildSpreads(
  pages: PageImage[],
  threshold = WIDE_PAGE_ASPECT_RATIO,
): SpreadPair[] {
  const spreads: SpreadPair[] = [];
  let i = 0;

  while (i < pages.length) {
    const current = pages[i];
    if (!current) break;

    if (isWidePage(current, threshold)) {
      spreads.push({ type: 'single', pages: [current] });
      i += 1;
      continue;
    }

    const next = pages[i + 1];
    if (next && !isWidePage(next, threshold)) {
      spreads.push({ type: 'pair', pages: [current, next] });
      i += 2;
    } else {
      spreads.push({ type: 'single', pages: [current] });
      i += 1;
    }
  }

  return spreads;
}

/**
 * A pending request for the webtoon scroller to move to a page.
 *
 * `token` is monotonic, so navigating to the page the reader is already on still reaches
 * the scroller (and a stale request can be recognised and dropped).
 */
export interface ScrollRequest {
  page: number;
  token: number;
}

export interface ViewerStateOptions {
  pages?: PageImage[];
  currentIndex?: number;
  mode?: ViewerMode;
  direction?: ReadingDirection;
  fitMode?: FitMode;
  autoScrollSpeed?: number;
}

/**
 * Reactive viewer state shared by the reader components and the control bar.
 *
 * `currentIndex` is always a **page** index (0-based, reading order) rather than a spread
 * index, so it survives spread recalculation when pages report their natural dimensions
 * and re-group from pairs to singles (or back).
 */
export class ViewerState {
  pages = $state<PageImage[]>([]);
  currentIndex = $state(0);
  mode = $state<ViewerMode>('dual');
  direction = $state<ReadingDirection>('ltr');
  fitMode = $state<FitMode>('screen');
  isFullscreen = $state(false);
  autoScroll = $state(false);
  autoScrollSpeed = $state(DEFAULT_AUTO_SCROLL_SPEED);

  /** Set by {@link goToPage} so the webtoon scroller can follow programmatic navigation. */
  scrollRequest = $state<ScrollRequest | null>(null);

  #scrollToken = 0;
  #onFullscreenChange: (() => void) | null = null;

  /** All spreads, recomputed whenever pages or their measured dimensions change. */
  spreads = $derived.by<SpreadPair[]>(() => buildSpreads(this.pages));

  totalPages = $derived(this.pages.length);

  /** Index of the spread currently being read. */
  spreadIndex = $derived.by(() => {
    if (this.spreads.length === 0) return -1;
    const exact = this.spreads.findIndex((spread) =>
      spread.pages.some((page) => page.index === this.currentIndex),
    );
    if (exact !== -1) return exact;

    // currentIndex may point past the last known page while a chapter is still loading.
    for (let i = this.spreads.length - 1; i >= 0; i -= 1) {
      const first = this.spreads[i]?.pages[0];
      if (first && first.index <= this.currentIndex) return i;
    }
    return 0;
  });

  /** The one- or two-page spread that should currently be on screen. */
  currentSpread = $derived.by<SpreadPair | null>(() => {
    const index = this.spreadIndex;
    return index >= 0 ? (this.spreads[index] ?? null) : null;
  });

  /**
   * Pages of the current spread in physical left-to-right order.
   * For RTL the reading order is mirrored: page 1 is placed on the right.
   */
  visiblePages = $derived.by<PageImage[]>(() => {
    const spread = this.currentSpread;
    if (!spread) return [];
    return this.direction === 'rtl' ? [...spread.pages].reverse() : [...spread.pages];
  });

  /** 1-based page number of the first page of the current spread. */
  currentPageNumber = $derived(
    this.totalPages === 0 ? 0 : Math.min(this.currentIndex + 1, this.totalPages),
  );

  /** Human readable page label, e.g. `12-13 / 120` (mirrored for RTL spreads). */
  pageLabel = $derived.by(() => {
    const total = this.totalPages;
    // Continuous scrolling has no spread: report the single page under the reader.
    if (this.mode === 'webtoon') {
      return total === 0 ? `0 / ${total}` : `${this.currentPageNumber} / ${total}`;
    }
    const spread = this.currentSpread;
    const first = spread?.pages[0];
    if (!spread || !first) return `0 / ${total}`;
    if (spread.type === 'single') {
      return `${first.index + 1} / ${total}`;
    }
    const indexes = spread.pages.map((page) => page.index + 1);
    const lo = Math.min(...indexes);
    const hi = Math.max(...indexes);
    // RTL spreads read right-to-left, so the label follows the visual order.
    return this.direction === 'rtl' ? `${hi}-${lo} / ${total}` : `${lo}-${hi} / ${total}`;
  });

  /** Whether advancing is possible (webtoon advances by page, dual by spread). */
  canGoNext = $derived.by(() => {
    if (this.totalPages === 0) return false;
    return this.mode === 'webtoon'
      ? this.currentIndex < this.totalPages - 1
      : this.spreadIndex < this.spreads.length - 1;
  });

  /** Whether retreating is possible (webtoon retreats by page, dual by spread). */
  canGoPrev = $derived.by(() => {
    if (this.totalPages === 0) return false;
    return this.mode === 'webtoon' ? this.currentIndex > 0 : this.spreadIndex > 0;
  });

  constructor(options: ViewerStateOptions = {}) {
    if (options.pages) this.pages = options.pages;
    if (options.mode) this.mode = options.mode;
    if (options.direction) this.direction = options.direction;
    if (options.fitMode) this.fitMode = options.fitMode;
    if (typeof options.autoScrollSpeed === 'number') {
      this.autoScrollSpeed = clamp(options.autoScrollSpeed, MIN_AUTO_SCROLL_SPEED, MAX_AUTO_SCROLL_SPEED);
    }
    this.currentIndex = clamp(options.currentIndex ?? 0, 0, Math.max(this.pages.length - 1, 0));

    // Keep `isFullscreen` truthful when the platform exits fullscreen on its own (Esc, F11).
    if (typeof document !== 'undefined') {
      this.#onFullscreenChange = () => {
        this.isFullscreen = document.fullscreenElement !== null;
      };
      document.addEventListener('fullscreenchange', this.#onFullscreenChange);
    }
  }

  /** Replaces the chapter contents and keeps the reader on a valid page. */
  setPages(pages: PageImage[]): void {
    this.pages = pages;
    this.currentIndex = clamp(this.currentIndex, 0, Math.max(pages.length - 1, 0));
  }

  /**
   * Records the natural dimensions decoded for a page and re-groups spreads.
   * Called by the image load handlers in both readers.
   */
  updatePageMetrics(index: number, width: number, height: number): void {
    const page = this.pages.find((candidate) => candidate.index === index) ?? this.pages[index];
    if (!page || width <= 0 || height <= 0) return;
    page.width = width;
    page.height = height;
    // Same predicate the spread builder uses, so layout never disagrees with the flag.
    page.isWide = width / height > WIDE_PAGE_ASPECT_RATIO;
    page.loaded = true;
  }

  /**
   * Programmatic navigation (slider, keyboard, buttons). Also asks the webtoon scroller to
   * move to the requested page.
   */
  goToPage(page: number): void {
    if (this.totalPages === 0) {
      this.currentIndex = 0;
      return;
    }
    const target = clamp(Math.trunc(page), 0, this.totalPages - 1);
    this.currentIndex = target;
    this.#scrollToken += 1;
    this.scrollRequest = { page: target, token: this.#scrollToken };
  }

  /**
   * Reports the page that became visible through user scrolling without echoing a scroll
   * request back to the scroller (prevents scroll → state → scroll feedback loops).
   */
  setCurrentIndex(page: number, options: { silent?: boolean } = {}): void {
    if (this.totalPages === 0) {
      this.currentIndex = 0;
      return;
    }
    this.currentIndex = clamp(Math.trunc(page), 0, this.totalPages - 1);
    if (!options.silent) {
      this.#scrollToken += 1;
      this.scrollRequest = { page: this.currentIndex, token: this.#scrollToken };
    }
  }

  nextPage(): void {
    if (this.totalPages === 0) return;
    if (this.mode === 'webtoon') {
      this.goToPage(this.currentIndex + 1);
      return;
    }
    const index = this.spreadIndex;
    const next = this.spreads[index + 1];
    const first = next?.pages[0];
    if (first) this.goToPage(first.index);
  }

  prevPage(): void {
    if (this.totalPages === 0) return;
    if (this.mode === 'webtoon') {
      this.goToPage(this.currentIndex - 1);
      return;
    }
    const index = this.spreadIndex;
    const prev = this.spreads[index - 1];
    const first = prev?.pages[0];
    if (first) this.goToPage(first.index);
  }

  setMode(mode: ViewerMode): void {
    if (this.mode === mode) return;
    this.mode = mode;
    // Auto-scroll only makes sense in the continuous webtoon strip.
    if (mode === 'dual') this.autoScroll = false;
  }

  toggleMode(): void {
    this.setMode(this.mode === 'dual' ? 'webtoon' : 'dual');
  }

  setDirection(direction: ReadingDirection): void {
    this.direction = direction;
  }

  toggleDirection(): void {
    this.direction = this.direction === 'ltr' ? 'rtl' : 'ltr';
  }

  setFitMode(fitMode: FitMode): void {
    this.fitMode = fitMode;
  }

  toggleFullscreen(): void {
    if (typeof document === 'undefined') {
      this.isFullscreen = !this.isFullscreen;
      return;
    }

    if (document.fullscreenElement) {
      void document.exitFullscreen?.().catch(() => {});
      return;
    }

    // Extension surfaces and iframes often cannot use the Fullscreen API. Fall back to the
    // CSS-only immersive layout instead of refusing to change anything.
    const request = document.documentElement.requestFullscreen?.();
    if (request) {
      request.catch(() => {
        this.isFullscreen = true;
      });
    } else {
      this.isFullscreen = true;
    }
  }

  /** Detaches document listeners; call when the reader is torn down for good. */
  destroy(): void {
    if (this.#onFullscreenChange) {
      document.removeEventListener('fullscreenchange', this.#onFullscreenChange);
      this.#onFullscreenChange = null;
    }
  }

  toggleAutoScroll(): void {
    if (this.mode !== 'webtoon') {
      // Enabling auto-scroll implies the continuous strip.
      this.setMode('webtoon');
    }
    this.autoScroll = !this.autoScroll;
  }

  setAutoScrollSpeed(speed: number): void {
    this.autoScrollSpeed = clamp(speed, MIN_AUTO_SCROLL_SPEED, MAX_AUTO_SCROLL_SPEED);
  }

  /** Maps keyboard/UI intent to a physical direction, honouring RTL and webtoon. */
  goForward(): void {
    if (this.mode === 'webtoon') {
      this.goToPage(this.currentIndex + 1);
    } else if (this.direction === 'rtl') {
      this.prevPage();
    } else {
      this.nextPage();
    }
  }

  goBackward(): void {
    if (this.mode === 'webtoon') {
      this.goToPage(this.currentIndex - 1);
    } else if (this.direction === 'rtl') {
      this.nextPage();
    } else {
      this.prevPage();
    }
  }
}

/** Creates an independent viewer state (one per open chapter). */
export function createViewerState(options: ViewerStateOptions = {}): ViewerState {
  return new ViewerState(options);
}

/** Process-wide default state used by the extension's single reader surface. */
export const viewerState = new ViewerState();
