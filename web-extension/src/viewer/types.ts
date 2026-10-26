/**
 * Shared data model for the Comics8 web viewer.
 *
 * These types are intentionally platform-agnostic so that page sources (network, ZIP/CBZ,
 * local library, plugin engine) can describe a chapter without knowing how it is rendered.
 */

/** A single page image inside a chapter, in reading order. */
export interface PageImage {
  /** Stable identity of the page (local in-chapter id, archive entry name or URL). */
  id: string;
  /** Zero-based position of the page inside the chapter, in reading order. */
  index: number;
  /** Resolved image URL (blob:, data:, http(s): or extension URL). */
  url: string;
  /** Natural pixel width. Known once the image has decoded. */
  width?: number;
  /** Natural pixel height. Known once the image has decoded. */
  height?: number;
  /**
   * True when the source image is a wide panorama (an already merged two-page spread).
   * Wide pages are always shown alone, never paired with a neighbour.
   */
  isWide?: boolean;
  /** True once the bitmap has been decoded at least once. */
  loaded?: boolean;
}

/**
 * One viewport worth of pages.
 *
 * `pages` always stays in *reading order* (page 1 before page 2). Physical placement
 * (which page sits on the left or the right) is decided by the reading direction at
 * render time.
 */
export interface SpreadPair {
  type: 'single' | 'pair';
  pages: PageImage[];
}

/** Paged dual-spread reading or continuous vertical webtoon scrolling. */
export type ViewerMode = 'dual' | 'webtoon';

/** Left-to-right (western comics) or right-to-left (manga). */
export type ReadingDirection = 'ltr' | 'rtl';

/** How a page is scaled inside its viewport slot. */
export type FitMode = 'screen' | 'width' | 'height' | 'original';
