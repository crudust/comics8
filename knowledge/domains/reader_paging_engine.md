# Reader & Paging Engine Domain Contract

## Responsibilities & Boundaries

Governs the comic/webtoon reading canvas across Android and Desktop platforms, including page virtualization, dual-spread calculations, reading directions, and reading progress updates.

## Core Invariants

- **Reading Modes**:
  - `WEBTOON` (Vertical continuous scroll): Seamless vertical image strip with preloading.
  - `PAGED_LTR` (Left-to-Right page flip): Traditional Western comic orientation.
  - `PAGED_RTL` (Right-to-Left page flip): Manga / Eastern comic orientation.
- **Dual Spread Layout (Two-Page Spread)**:
  - On widescreen displays / landscape orientations, two portrait images can be combined side-by-side into a single viewport spread.
  - Large landscape images (spread double-pages) bypass split and render full width.
  - Offset option allows shifting odd/even pairings for proper chapter cover alignment.
- **Progress & Position Synchronization (`ReaderProgress.kt`)**:
  - Exact scroll offset / page index is recorded upon page turn and viewport settlement.
  - Emits progress events to `SyncManager` only when progress advances or user explicitly pauses reading.
  - Finished status is flagged when reaching the last page threshold.
- **Shared Reader Decisions (`ReaderDomain.kt`)**:
  - One-based page clamping, sort-aware episode navigation (`nextEpisode`/`prevEpisode` respecting `EpisodeSortOrder.isAscending`), initial image position,
    aspect-ratio validation, dimension-based dual-mode selection, and scroll direction resolution (`resolveScrollDirection`) are pure `core` decisions.
  - Platform ViewModels own lifecycle, persistence, repository calls, and UI state mutation only.
- **Paging Navigation & Gesture Invariant**:
  - `pagerState.pageCount` is the sole authoritative page count across platforms. Redundant `totalPages` parameters and ad-hoc `maxOf` fallbacks are prohibited.
  - On Desktop, trackpad swipes and mouse wheel events are latched via `desktopDiscreteScrollPaging` across Reader, Browse, and Series views to convert continuous OS scroll streams into discrete page turns.
  - Paging latching enforces a strict contract: single trigger per physical gesture, mandatory cooldown (`SCROLL_LATCH_COOLDOWN_MS`), and momentum decay validation (`isScrollIdle`).
  - Gesture recognizers must be hosted at the root container level of the view to guarantee latch state persistence across child component loading/pagination unmounts.

