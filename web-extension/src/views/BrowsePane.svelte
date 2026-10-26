<script lang="ts">
  /**
   * Browse surface, ported 1:1 from `desktop/src/main/kotlin/ui/browse/BrowsePane.kt`.
   *
   * Mapping notes (deliberate, not accidental):
   * - The pane renders one `DesktopStore` (`src/state/desktop-state.svelte.ts`), the web twin of
   *   `DesktopUiState`; actions go through the store methods ported from `DesktopViewModel`, exactly
   *   like the Kotlin pane receives `state` + `viewModel`. It defaults to the process-wide
   *   `desktopState`; the `store` prop only exists so a test or a nested surface can render the pane
   *   against its own store. It is not named `state` because Svelte would read `$state(...)` as a
   *   store subscription of that local binding.
   * - The catalog `FilterChip` row lives here rather than in a shell top bar: `TopBar.kt` renders it
   *   (`BrowseTab.displayLabel`, selected chip = primary container), and the web pane is the surface
   *   that owns the grid it filters. Chips are hidden while searching, exactly like the Kotlin
   *   `if (state.screen == Screen.Browse && !state.isSearch)` guard.
   * - `state.tab?.paginated != true` disables page turns (`DesktopViewModel.goToPage` returns
   *   early), so the bottom bar appears for every paginated catalog (search results have no page
   *   bar, matching the Kotlin `state.isSearch` guard). The `1 / N` label is the
   *   `FilledTonalButton` that opens `PageJumpDialog` via `viewModel.togglePageJump(true)`; a plugin
   *   that reports no total shows `1 / ?` instead of a made-up denominator, and the next arrow stays
   *   enabled so the listing can still be walked one page at a time.
   * - `ToonCard` mirrors the Kotlin card: 3:4 cover crop, 2-line title, `NEW` / `readProgress`
   *   badges and the gold star toggle. The Kotlin subtitle row prints `genre` on the left and
   *   `updatedAt` on the right; when a plugin exposes `artistChoices`, the author line replaces the
   *   genre (the only author data a `ToonItem` carries) and the genre stays in the tooltip.
   * - Two Kotlin branches have no web twin and are intentionally absent: the `packsReady` loading
   *   gate (the store has no JS pack preload phase) and the `LocalSource` library-roots prompts (the
   *   web shell has no local folder picker). The favorite tab's empty state is kept verbatim.
   * - `LazyVerticalGrid` → one CSS grid with `auto-fill, minmax(160px, 1fr)`, the web spelling of
   *   `GridCells.Adaptive(minSize = 160.dp)`; card padding 16dp, gaps 12/16dp as in Kotlin.
   */

  import { Star } from 'lucide-svelte';

  import { desktopState } from '../state/desktop-state.svelte';
  import type { DesktopBrowseTab, DesktopStore, DesktopToonItem } from '../state/desktop-state.svelte';
  import PageJumpDialog from '../ui/PageJumpDialog.svelte';
  import { seriesCardKey } from './display';

  interface Props {
    /** Store to render; defaults to the shell's `desktopState`. */
    store?: DesktopStore;
    /** Optional source-manager hook for the "no source installed" state. */
    onAddSource?: () => void;
  }

  let { store = desktopState, onAddSource }: Props = $props();

  /** `LocalStrings.emptyFavorites` (ko). */
  const EMPTY_FAVORITES = '즐겨찾기한 작품이 없습니다.';
  /** `LocalStrings.navBackToLibrary` (ko) — the favorite tab's action label. */
  const NAV_BACK_TO_LIBRARY = '보관함으로';
  /** `LocalStrings.actionRetry` (ko). */
  const ACTION_RETRY = '다시 시도';
  /** `LocalStrings.actionPrev` / `actionNext` (ko) — the pager's accessible names. */
  const ACTION_PREV = '이전';
  const ACTION_NEXT = '다음';
  /** `LocalStrings.promptAddFolder` / `actionAddSource` (ko). */
  const PROMPT_ADD_SOURCE = '폴더를 추가하세요';
  const ACTION_ADD_SOURCE = '소스 추가';

  const tabs = $derived(store.browseTabs);
  const activeTab = $derived(store.activeTab);
  const isFavoriteTab = $derived(activeTab?.kind === 'favorite');
  const isSearch = $derived(store.isSearch);

  /** `state.items.distinctBy { it.listingKey() }` — a sloppy page must not repeat a card. */
  const cards = $derived.by(() => {
    const seen = new Set<string>();
    const out: DesktopToonItem[] = [];
    for (const item of store.items) {
      const key = seriesCardKey(item);
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(item);
    }
    return out;
  });

  /**
   * `BrowsePageBar` guard: every paginated catalog gets the bar.
   *
   * Kotlin reads `if (state.tab?.paginated != true || state.isSearch || state.lastPage <= 1) return`;
   * the web port keeps the bar for a single-page catalog too, because a plugin that reports no total
   * (`lastPageKnown === false`) must still be able to turn pages — and `1 / ?` would otherwise never
   * be reachable. Search results are not a catalog listing, so the bar stays hidden there.
   */
  const showPageBar = $derived(activeTab !== null && activeTab.paginated !== false && !isSearch);

  /** `lastPage` may be unknown: label it `?` rather than invent a denominator. */
  const lastPageLabel = $derived(store.lastPageKnown ? String(store.lastPage) : '?');

  /** `PageJumpDialog(last = …)`: without a reported total the field cannot be bounded. */
  const jumpLast = $derived(
    store.lastPageKnown ? Math.max(1, store.lastPage) : Math.max(1, store.page),
  );

  /** Kotlin renders `LoadingPane` when nothing is on screen yet. */
  const showLoading = $derived(store.loading && store.items.length === 0);
  /** Kotlin keeps already-rendered cards when a refresh fails (`error != null && items.isEmpty`). */
  const showError = $derived(store.error !== null && store.items.length === 0);
  /** `state.tab is BrowseTab.Favorite && state.items.isEmpty() && !state.isSearch`. */
  const showEmptyFavorites = $derived(isFavoriteTab && store.items.length === 0 && !isSearch);

  const canPrev = $derived(!store.loading && store.page > 1);
  /** Enabled while the total is unknown (there may be another page). */
  const canNext = $derived(
    !store.loading && (!store.lastPageKnown || store.page < store.lastPage),
  );

  // ---------------------------------------------------------------------------------------------
  // actions (DesktopViewModel equivalents)
  // ---------------------------------------------------------------------------------------------

  function selectTab(tab: DesktopBrowseTab): void {
    void store.selectTab(tab);
  }

  function openSeries(item: DesktopToonItem): void {
    void store.openSeries(item);
  }

  function toggleFavorite(event: MouseEvent, item: DesktopToonItem): void {
    event.stopPropagation();
    void store.toggleFavorite(item);
  }

  function retry(): void {
    void store.refresh();
  }

  /** `ErrorPane(onRetry = { selectTab(first Remote tab) })` — leaves the empty favorite tab. */
  function backToLibrary(): void {
    const remote = store.browseTabs.find((tab) => tab.kind === 'catalog') ?? null;
    if (remote) void store.selectTab(remote);
  }

  function prevPage(): void {
    if (!canPrev) return;
    void store.prevPage();
  }

  function nextPage(): void {
    if (!canNext) return;
    void store.nextPage();
  }

  /** `onClick = { viewModel.togglePageJump(true) }` on the `1 / N` label. */
  function openPageJump(): void {
    store.togglePageJump(true);
  }

  function closePageJump(): void {
    store.togglePageJump(false);
  }

  /** `PageJumpDialog(onConfirm = { viewModel.goToPage(it) })`. */
  function jumpToPage(page: number): void {
    void store.goToPage(page);
  }

  function onCardKeydown(event: KeyboardEvent, item: DesktopToonItem): void {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    openSeries(item);
  }

  /** Author line: `ToonItem.artistChoices` is the only author data the model carries. */
  function authorOf(item: DesktopToonItem): string {
    const names = (item.artistChoices ?? [])
      .map((choice) => choice.displayName || choice.slug)
      .filter((name) => name.trim() !== '');
    return names.join(' · ');
  }

  function subtitleOf(item: DesktopToonItem): string {
    const author = authorOf(item);
    return author !== '' ? author : (item.genre ?? '');
  }
</script>

<section class="pane">
  {#if tabs.length > 0 && !isSearch}
    <!-- `TopBar.kt` FilterChip row: horizontal, selected = primary container -->
    <nav class="tabs" aria-label="카탈로그">
      {#each tabs as tab (tab.id)}
        <button
          type="button"
          class="chip"
          class:selected={activeTab?.id === tab.id}
          aria-pressed={activeTab?.id === tab.id}
          onclick={() => selectTab(tab)}
        >
          {tab.label}
        </button>
      {/each}
    </nav>
  {/if}

  {#if store.activeSourceId === null && !store.loading}
    <!-- `EmptySourcePane`: no source is installed, so there is no catalog to show. -->
    <div class="state">
      <p class="state-title">{PROMPT_ADD_SOURCE}</p>
      {#if onAddSource}
        <button type="button" class="state-action" onclick={onAddSource}>{ACTION_ADD_SOURCE}</button>
      {/if}
    </div>
  {:else if showLoading}
    <!-- `LoadingPane` (`state.loading && state.items.isEmpty()`) -->
    <div class="state loading" role="status">
      <span class="spinner" aria-hidden="true"></span>
    </div>
  {:else if showError}
    <!-- `ErrorPane(message = state.error, onRetry = viewModel::refresh)` -->
    <div class="state" role="alert">
      <p class="state-title error">{store.error}</p>
      <button type="button" class="state-action" onclick={retry}>{ACTION_RETRY}</button>
    </div>
  {:else if showEmptyFavorites}
    <!-- `ErrorPane(strings.emptyFavorites, strings.navBackToLibrary)` -->
    <div class="state">
      <p class="state-title">{EMPTY_FAVORITES}</p>
      <button type="button" class="state-action" onclick={backToLibrary}>
        {NAV_BACK_TO_LIBRARY}
      </button>
    </div>
  {:else}
    <!-- `LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 160.dp))` -->
    <div class="grid">
      {#each cards as item (seriesCardKey(item))}
        {@const subtitle = subtitleOf(item)}
        <div
          class="card"
          role="button"
          tabindex="0"
          aria-label={item.title}
          title={item.genre ? `${item.title} — ${item.genre}` : item.title}
          onclick={() => openSeries(item)}
          onkeydown={(event) => onCardKeydown(event, item)}
        >
          <div class="cover">
            {#if item.thumbUrl}
              <img
                src={item.thumbUrl}
                alt=""
                loading="lazy"
                decoding="async"
                class="cover-image"
              />
            {/if}

            {#if item.isNew}
              <span class="badge new">NEW</span>
            {/if}

            {#if item.readProgress}
              <span class="badge progress">{item.readProgress}</span>
            {/if}

            <!-- `IconButton(onClick = onToggleFavorite)`: gold `MonochromeTheme.Gold` -->
            <button
              type="button"
              class="star"
              class:on={item.isFavorite === true}
              aria-pressed={item.isFavorite === true}
              aria-label={item.isFavorite ? `${item.title} 즐겨찾기 해제` : `${item.title} 즐겨찾기`}
              title={item.isFavorite ? '즐겨찾기 해제' : '즐겨찾기'}
              onclick={(event) => toggleFavorite(event, item)}
            >
              <Star size={14} fill={item.isFavorite ? 'currentColor' : 'none'} aria-hidden="true" />
            </button>
          </div>

          <p class="card-title">{item.title}</p>

          {#if subtitle !== '' || item.updatedAt}
            <div class="card-meta">
              {#if subtitle !== ''}
                <span class="card-sub" title={item.genre ?? undefined}>{subtitle}</span>
              {/if}
              {#if item.updatedAt}
                <span class="card-updated">{item.updatedAt}</span>
              {/if}
            </div>
          {/if}
        </div>
      {/each}
    </div>
  {/if}

  {#if showPageBar}
    <!-- `BrowsePageBar` (`if (state.tab?.paginated != true || state.isSearch) return`) -->
    <nav class="pager" aria-label="페이지 이동">
      <button
        type="button"
        class="page-button"
        title={ACTION_PREV}
        aria-label={ACTION_PREV}
        disabled={!canPrev}
        onclick={prevPage}
      >
        <span aria-hidden="true">‹</span>
      </button>

      <!-- `FilledTonalButton(onClick = { viewModel.togglePageJump(true) })` -->
      <button
        type="button"
        class="page-label page-jump"
        aria-live="polite"
        aria-haspopup="dialog"
        aria-label={`페이지 이동 (${store.page} / ${lastPageLabel})`}
        title="페이지 이동"
        disabled={store.loading}
        onclick={openPageJump}
      >
        {store.page} / {lastPageLabel}
      </button>

      <button
        type="button"
        class="page-button"
        title={ACTION_NEXT}
        aria-label={ACTION_NEXT}
        disabled={!canNext}
        onclick={nextPage}
      >
        <span aria-hidden="true">›</span>
      </button>
    </nav>
  {/if}

  {#if showPageBar && store.showPageJump}
    <!-- `PageJumpDialog(current, last, onDismiss, onConfirm)` from `Main.kt` -->
    <PageJumpDialog
      current={store.page}
      last={jumpLast}
      onDismiss={closePageJump}
      onConfirm={jumpToPage}
    />
  {/if}
</section>

<style>
  /* --- frame: pure black surface, Zinc borders -------------------------------------------- */

  .pane {
    display: flex;
    flex-direction: column;
    min-height: 0;
    height: 100%;
    background: #000000;
    color: #f4f4f5;
  }

  /* --- catalog chips (FilterChip row) ----------------------------------------------------- */

  .tabs {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 8px;
    padding: 10px 16px 4px;
    overflow-x: auto;
    overscroll-behavior-x: contain;
    scrollbar-width: thin;
  }

  .tabs::-webkit-scrollbar {
    height: 4px;
  }

  .tabs::-webkit-scrollbar-thumb {
    border-radius: 2px;
    background: rgba(63, 63, 70, 0.8);
  }

  /* containerColor = surfaceVariant @ 0.45; selected = primary container. */
  .chip {
    flex: 0 0 auto;
    padding: 6px 13px;
    border: 1px solid rgba(63, 63, 70, 0.55);
    border-radius: 10px;
    background: rgba(39, 39, 42, 0.45);
    color: #e4e4e7;
    font: 400 12.5px/1.2 var(--font-sans, system-ui, sans-serif);
    white-space: nowrap;
    cursor: pointer;
    transition: background 0.15s, border-color 0.15s, color 0.15s;
  }

  .chip:hover {
    border-color: rgba(113, 113, 122, 0.9);
    background: rgba(39, 39, 42, 0.8);
  }

  /* selected: primary container + bold label (Kotlin `FontWeight.Bold`). */
  .chip.selected {
    border-color: rgba(96, 165, 250, 0.55);
    background: rgba(30, 58, 138, 0.85);
    color: #eff6ff;
    font-weight: 700;
  }

  .chip:focus-visible {
    outline: 2px solid #60a5fa;
    outline-offset: 2px;
  }

  /* --- poster grid (GridCells.Adaptive(minSize = 160.dp)) --------------------------------- */

  .grid {
    display: grid;
    flex: 1 1 auto;
    grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
    gap: 16px 12px;
    align-content: start;
    min-height: 0;
    padding: 16px;
    overflow-y: auto;
  }

  /* `Column(clip 12dp).clickable(...).padding(4dp)` */
  .card {
    display: flex;
    flex-direction: column;
    min-width: 0;
    padding: 4px;
    border-radius: 12px;
    cursor: pointer;
    transition: background 0.15s;
  }

  .card:hover {
    background: rgba(24, 24, 27, 0.55);
  }

  .card:focus-visible {
    outline: 2px solid #60a5fa;
    outline-offset: 2px;
  }

  /* `aspectRatio(0.72f)` → the 3:4 cover the spec asks for; surfaceVariant + outlineVariant. */
  .cover {
    position: relative;
    aspect-ratio: 3 / 4;
    overflow: hidden;
    border: 1px solid rgba(63, 63, 70, 0.35);
    border-radius: 12px;
    background: #27272a;
  }

  .cover-image {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover; /* ContentScale.Crop */
  }

  /* `Badge`: error container, labelSmall bold (the Kotlin badge is the theme's error colour). */
  .badge {
    position: absolute;
    padding: 2px 6px;
    border-radius: 6px;
    font-size: 10.5px;
    font-weight: 700;
    line-height: 1.25;
    white-space: nowrap;
  }

  .badge.new {
    top: 6px;
    left: 6px;
    background: #dc2626;
    color: #fff;
  }

  /* readProgress: surfaceContainer @ 0.92, primary text, 6dp rounded. */
  .badge.progress {
    bottom: 6px;
    left: 6px;
    /* Above the cover bitmap and the NEW chip, below the star button. */
    z-index: 2;
    border: 0.5px solid rgba(96, 165, 250, 0.35);
    background: rgba(24, 24, 27, 0.92);
    color: #93c5fd;
    font-size: 11px;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
    pointer-events: none;
  }

  /* `IconButton` + `MonochromeTheme.Gold` (#FFB800) when starred. */
  .star {
    position: absolute;
    top: 4px;
    right: 4px;
    /* Above the readProgress badge (z-index 2) when the two ever overlap. */
    z-index: 3;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    padding: 3px;
    border: 0;
    border-radius: 6px;
    background: rgba(24, 24, 27, 0.82);
    color: rgba(244, 244, 245, 0.55);
    cursor: pointer;
    transition: color 0.15s, background 0.15s;
  }

  .star:hover {
    background: rgba(39, 39, 42, 0.95);
    color: #f4f4f5;
  }

  .star.on {
    color: #ffb800;
  }

  .star.on:hover {
    color: #ffc93a;
  }

  .star:focus-visible {
    outline: 2px solid #60a5fa;
    outline-offset: 1px;
  }

  /* bodyMedium semibold, 2 lines, ellipsis (`maxLines = 2, TextOverflow.Ellipsis`). */
  .card-title {
    display: -webkit-box;
    margin: 6px 0 0;
    overflow: hidden;
    font-size: 13px;
    font-weight: 600;
    line-height: 1.35;
    color: #f4f4f5;
    overflow-wrap: anywhere;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    line-clamp: 2;
  }

  .card-meta {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 6px;
    margin-top: 2px;
  }

  /* labelSmall: onSurfaceVariant (author/genre) and outline (updatedAt). */
  .card-sub,
  .card-updated {
    overflow: hidden;
    font-size: 11px;
    line-height: 1.3;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  .card-sub {
    min-width: 0;
    color: rgba(244, 244, 245, 0.64);
  }

  .card-updated {
    flex: 0 0 auto;
    color: #71717a;
    font-variant-numeric: tabular-nums;
  }

  /* --- loading / error / empty panes ------------------------------------------------------ */

  .state {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 16px;
    min-height: 200px;
    padding: 24px;
    text-align: center;
  }

  /* `bodyMedium` + `colorScheme.error`. */
  .state-title {
    margin: 0;
    font-size: 13.5px;
    font-weight: 600;
    color: #e4e4e7;
    overflow-wrap: anywhere;
  }

  .state-title.error {
    color: #f87171;
  }

  /* `Button(actionLabel ?: actionRetry)`. */
  .state-action {
    padding: 6px 14px;
    border: 1px solid rgba(63, 63, 70, 0.8);
    border-radius: 8px;
    background: rgba(24, 24, 27, 0.9);
    color: #e4e4e7;
    font: 600 12px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .state-action:hover {
    border-color: rgba(113, 113, 122, 0.9);
    background: rgba(39, 39, 42, 0.9);
  }

  .spinner {
    width: 28px;
    height: 28px;
    border: 2.5px solid rgba(255, 255, 255, 0.16);
    border-top-color: #60a5fa;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
  }

  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }

  /* --- bottom pagination bar -------------------------------------------------------------- */

  /* surfaceVariant @ 0.3, 8dp vertical padding, centered row. */
  .pager {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    gap: 12px;
    padding: 8px;
    border-top: 1px solid rgba(63, 63, 70, 0.55);
    background: rgba(39, 39, 42, 0.3);
  }

  .page-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 30px;
    height: 28px;
    padding: 0;
    border: 1px solid rgba(63, 63, 70, 0.7);
    border-radius: 8px;
    background: transparent;
    color: #e4e4e7;
    font-size: 17px;
    line-height: 1;
    cursor: pointer;
  }

  .page-button:hover:not(:disabled) {
    border-color: rgba(113, 113, 122, 0.9);
    background: rgba(39, 39, 42, 0.8);
  }

  .page-button:disabled {
    opacity: 0.4;
    cursor: default;
  }

  /* `FilledTonalButton`, 8dp rounded, labelMedium bold; now a button (page jump trigger). */
  .page-label {
    padding: 6px 14px;
    border: 0;
    border-radius: 8px;
    background: rgba(39, 39, 42, 0.95);
    color: #e4e4e7;
    font: 700 12px/1.2 var(--font-sans, system-ui, sans-serif);
    font-variant-numeric: tabular-nums;
    cursor: pointer;
    transition: background 0.15s;
  }

  .page-label:hover:not(:disabled) {
    background: rgba(63, 63, 70, 0.95);
  }

  .page-label:disabled {
    opacity: 0.4;
    cursor: default;
  }

  .page-label:focus-visible {
    outline: 2px solid #60a5fa;
    outline-offset: 2px;
  }

  @media (prefers-reduced-motion: reduce) {
    .spinner {
      animation: none;
    }

    .card,
    .chip,
    .star {
      transition: none;
    }
  }
</style>
