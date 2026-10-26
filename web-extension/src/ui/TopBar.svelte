<script lang="ts">
  /**
   * Shell top bar, ported from `desktop/src/main/kotlin/ui/components/TopBar.kt` (lines 89-460).
   *
   * Mapping notes (deliberate, not accidental):
   * - `state: DesktopUiState` + `viewModel: DesktopViewModel` collapse into one `store`
   *   ({@link DesktopStore}), exactly like `BrowsePane.svelte` / `SeriesPane.svelte` /
   *   `ReaderPane.svelte`. It defaults to the process-wide `desktopState`; the `store` prop only
   *   exists so a test or a nested surface can render the bar against its own store. It is not named
   *   `state` because Svelte would read `$state(...)` as a store subscription of that local binding.
   * - `inOverlay` is the reader's immersive variant: Kotlin paints
   *   `surface.copy(alpha = 0.94f)` over the pages there, so the bar floats above the artwork; the
   *   docked bar is the opaque window background (`PureBlack`, `Main.kt` `DarkColors`).
   * - The bar is presentational: it reads the store, calls the ported view-model methods and owns
   *   nothing but its popup/menu visibility. The catalog `FilterChip` row of `TopBar.kt` lives in
   *   `views/BrowsePane.svelte` (it filters the grid that pane owns), the category chips and search
   *   suggestions are likewise left to the surface that renders their results, and `updateState`
   *   has no web twin (the extension is updated by the browser store, not by a self-updater).
   * - Download entry points follow `TopBar.kt` exactly, including the *absence* of a sync button on
   *   Browse: the `CloudSync` glyph belongs to the settings hub (`SyncBackupSettingsPane`), not to
   *   the library row. Browse gets `Download` → `viewModel.openDownloads` (title `navDownloads`,
   *   "다운로드 보관함") while `writesDownloads`; Series gets `Download` →
   *   `viewModel.openDownloadOptions` (title `titleOfflineDownload`, "오프라인 다운로드");
   *   Downloads gets its own back arrow and the `navStorageNamed(source)` title.
   * - `screen == 'Settings'` has no back target of its own on the web shell: settings is a leaf
   *   screen like in `DesktopViewModel.closeSettings`, which always returns to Browse.
   */

  import { tick } from 'svelte';
  import { slide } from 'svelte/transition';
  import {
    ArrowLeft,
    BookOpen,
    Check,
    Download,
    FileText,
    History,
    MoveVertical,
    Play,
    RefreshCw,
    Search,
    Star,
    Trash2,
    X,
  } from 'lucide-svelte';

  import {
    desktopState,
    resolveEpisodeTotal,
    workKey,
  } from '../state/desktop-state.svelte';
  import type {
    DesktopStore,
    EpisodeSortOrder,
    ReadDirection,
    SplitMode,
    ViewMode,
  } from '../state/desktop-state.svelte';
  import SourceTitleDropdown from './SourceTitleDropdown.svelte';

  interface Props {
    /** Store to render; defaults to the shell's `desktopState`. */
    store?: DesktopStore;
    /** Immersive (reader) variant: translucent background instead of the opaque window one. */
    inOverlay?: boolean;
    /** Opens the source manager modal. */
    onOpenSourceManager?: () => void;
    /** Opens settings (`DesktopViewModel.openSettings`). */
    onOpenSettings?: () => void;
  }

  let {
    store = desktopState,
    inOverlay = false,
    onOpenSourceManager,
    onOpenSettings,
  }: Props = $props();

  /** `EpisodeSortOrder` menu labels (`DropdownMenuItem` order of the Kotlin sort menu). */
  const SORT_OPTIONS: { order: EpisodeSortOrder; label: string }[] = [
    { order: 'DEFAULT', label: '기본순' },
    { order: 'NAME_ASC', label: '이름 오름차순' },
    { order: 'NAME_DESC', label: '이름 내림차순' },
    { order: 'DATE_DESC', label: '최신순' },
    { order: 'DATE_ASC', label: '오래된순' },
  ];

  /** `ViewModeChip` row: SCROLL / PAGE / DUAL, with the Kotlin icons (SwapVert / Description / MenuBook). */
  const VIEW_MODE_OPTIONS: { mode: ViewMode; label: string; icon: typeof MoveVertical }[] = [
    { mode: 'SCROLL', label: '웹툰', icon: MoveVertical },
    { mode: 'PAGE', label: '한쪽', icon: FileText },
    { mode: 'DUAL', label: '양면', icon: BookOpen },
  ];

  /** `DirectionChip` row, only rendered when the mode is not SCROLL. */
  const READ_DIRECTION_OPTIONS: { direction: ReadDirection; label: string }[] = [
    { direction: 'RIGHT_TO_LEFT', label: '우→좌' },
    { direction: 'LEFT_TO_RIGHT', label: '좌→우' },
  ];

  /** `SplitModeChip` row, only rendered in PAGE mode. */
  const SPLIT_MODE_OPTIONS: { mode: SplitMode; label: string }[] = [
    { mode: 'FIT', label: 'Fit' },
    { mode: 'SLICE', label: 'Slice' },
  ];

  let sortMenuOpen = $state(false);
  let searchInput: HTMLInputElement | undefined = $state();

  const screen = $derived(store.screen);
  const canRefresh = $derived(store.activeSourceId !== null);

  // `AnimatedVisibility` reveals the field already focusable; the web bar hands it the caret too.
  $effect(() => {
    if (!store.searchExpanded) return;
    void tick().then(() => searchInput?.focus());
  });

  /** Row-1 title for every screen; Browse renders {@link SourceTitleDropdown} instead. */
  const title = $derived.by(() => {
    switch (store.screen) {
      case 'Reader':
        return store.currentEpisode?.title || '뷰어';
      case 'Series':
        return store.series?.title || '';
      case 'History':
        return '최근 본 작품';
      case 'Downloads':
        return '저장된 만화';
      case 'Settings':
        return '설정';
      default:
        return '';
    }
  });

  /**
   * Episode total of the open series, or `null` when nothing is known yet.
   *
   * Same authority order as `views/SeriesPane.svelte` (`DesktopStore.totalEpisodesOf` → counted
   * `catalogTotals` → loaded page length), so the bar and the pane always print the same number.
   * The loaded page fallback is skipped while the first page is still loading: an empty list there
   * is "not known yet", not "no episodes".
   */
  const seriesTotal = $derived.by(() => {
    const current = store.series;
    if (store.screen !== 'Series' || !current) return null;
    const key = workKey(current.sourceId ?? store.activeSourceId ?? 'local', current.id);
    const total = resolveEpisodeTotal(
      store.totalEpisodesOf(key),
      store.catalogTotals[key]?.count,
      store.episodeLoading && store.episodes.length === 0 ? null : store.episodes.length,
    );
    return total > 0 ? total : null;
  });

  /** `strings.episodeItemCount(total)` — the same wording the series pane prints. */
  const seriesTotalLabel = $derived(seriesTotal === null ? null : `총 ${seriesTotal}화`);

  /**
   * `strings.actionResumeSeriesWithProgress(state.progressLabel(hist))` → `이어보기 (12화)`.
   *
   * A record the source cannot describe (no `catalogTotals` entry yet, `READ_COUNT` mode with no
   * read rows) yields an empty label on the desktop too; the parentheses are then dropped rather
   * than printing `이어보기 ()`.
   */
  const resumeLabel = $derived.by(() => {
    const history = store.seriesHistory;
    if (!history) return null;
    const progress = store.progressLabel(history).trim();
    return progress === '' ? '이어보기' : `이어보기 (${progress})`;
  });

  /**
   * `ComicSource.displaySearchPlaceholder(strings)`: the active plugin's own hint, `파일명 검색`
   * for the local library, `제목 검색` when the plugin does not declare one.
   */
  const searchPlaceholder = $derived.by(() => {
    const sourceId = store.activeSourceId;
    if (!sourceId || sourceId === 'local') return '파일명 검색';
    const placeholder = store.activeSource?.meta?.searchPlaceholder ?? '';
    return placeholder.trim() === '' ? '제목 검색' : placeholder;
  });

  // ---------------------------------------------------------------------------------------------
  // actions (DesktopViewModel equivalents)
  // ---------------------------------------------------------------------------------------------

  /** `IconButton(onClick = closeReader / closeSeries / closeHistory / closeDownloads / closeSettings)`. */
  function goBack(): void {
    switch (store.screen) {
      case 'Reader':
        store.closeReader();
        break;
      case 'Series':
        void store.closeSeries();
        break;
      case 'History':
        store.closeHistory();
        break;
      case 'Downloads':
        store.closeDownloads();
        break;
      case 'Settings':
        store.closeSettings();
        break;
      default:
        break;
    }
  }

  function selectSortOrder(order: EpisodeSortOrder): void {
    store.setEpisodeSortOrder(order);
    sortMenuOpen = false;
  }

  function selectViewMode(mode: ViewMode): void {
    store.setViewMode(mode);
  }

  function selectReadDirection(direction: ReadDirection): void {
    store.setReadDirection(direction);
  }

  function selectSplitMode(mode: SplitMode): void {
    store.setSplitMode(mode);
  }

  function isBackScreen(): boolean {
    return (
      screen === 'Reader' ||
      screen === 'Series' ||
      screen === 'History' ||
      screen === 'Downloads' ||
      screen === 'Settings'
    );
  }

  // --- search bar -------------------------------------------------------------------------------

  /** `DesktopViewModel.updateSearchInput` without the suggestion request (no `suggest()` host). */
  function updateSearchInput(value: string): void {
    store.searchInput = value;
  }

  /** Kotlin clears the field, then restores the catalog the user was on when a search was active. */
  function clearSearchInput(): void {
    store.searchInput = '';
    if (store.isSearch) void store.clearSearch();
  }

  function submitSearch(): void {
    void store.search(store.searchInput);
  }

  function onSearchKeydown(event: KeyboardEvent): void {
    // `KeyboardOptions(imeAction = ImeAction.Search)` + `KeyboardActions(onSearch = …)`.
    if (event.key !== 'Enter') return;
    event.preventDefault();
    submitSearch();
  }
</script>

<div class="top-bar" class:overlay={inOverlay} data-screen={screen}>
  <div class="row">
    {#if isBackScreen()}
      <button type="button" class="icon-button" title="뒤로" aria-label="뒤로" onclick={goBack}>
        <ArrowLeft size={20} aria-hidden="true" />
      </button>
    {/if}

    {#if screen === 'Browse'}
      <SourceTitleDropdown
        {store}
        {onOpenSourceManager}
        onOpenSettings={onOpenSettings ?? (() => store.openSettings())}
      />
    {:else}
      <div class="title-group">
        <h1 class="title" title={title}>{title}</h1>
        {#if seriesTotalLabel}
          <span class="series-total" title={seriesTotalLabel}>{seriesTotalLabel}</span>
        {/if}
      </div>
    {/if}

    {#if screen === 'Browse'}
      {#if store.writesDownloads}
        <button
          type="button"
          class="icon-button download"
          title="다운로드 보관함"
          aria-label="다운로드 보관함"
          onclick={() => store.openDownloads()}
        >
          <Download size={20} aria-hidden="true" />
        </button>
      {/if}

      <button
        type="button"
        class="icon-button"
        title="히스토리"
        aria-label="히스토리"
        onclick={() => void store.openHistory()}
      >
        <History size={20} aria-hidden="true" />
      </button>

      <button
        type="button"
        class="icon-button"
        class:active={store.searchExpanded}
        title={store.searchExpanded ? '검색 닫기' : '검색'}
        aria-label={store.searchExpanded ? '검색 닫기' : '검색'}
        aria-expanded={store.searchExpanded}
        disabled={!canRefresh}
        onclick={() => store.toggleSearchBar()}
      >
        {#if store.searchExpanded}
          <X size={20} aria-hidden="true" />
        {:else}
          <Search size={20} aria-hidden="true" />
        {/if}
      </button>
    {:else if screen === 'History'}
      {#if store.historyItems.length > 0}
        <button
          type="button"
          class="icon-button"
          title="기록 전체 삭제"
          aria-label="기록 전체 삭제"
          onclick={() => void store.clearAllHistory()}
        >
          <Trash2 size={20} aria-hidden="true" />
        </button>
      {/if}
    {:else if screen === 'Series'}
      {#if store.seriesHistory}
        <button
          type="button"
          class="action-button resume"
          title={resumeLabel ?? '이어보기'}
          onclick={() => void store.resumeSeries()}
        >
          <Play size={16} aria-hidden="true" />
          <span>{resumeLabel}</span>
        </button>
      {:else}
        <button
          type="button"
          class="action-button resume"
          title="처음부터 읽기"
          onclick={() => void store.openFirstEpisode()}
        >
          <Play size={16} aria-hidden="true" />
          <span>처음부터 읽기</span>
        </button>
      {/if}

      {#if store.writesDownloads}
        <button
          type="button"
          class="icon-button download"
          title="오프라인 다운로드"
          aria-label="오프라인 다운로드"
          onclick={() => store.openDownloadOptions()}
        >
          <Download size={20} aria-hidden="true" />
        </button>
      {/if}

      <div class="menu-anchor">
        <button
          type="button"
          class="icon-button"
          title="회차 정렬"
          aria-label="회차 정렬"
          aria-haspopup="menu"
          aria-expanded={sortMenuOpen}
          onclick={() => (sortMenuOpen = !sortMenuOpen)}
        >
          <MoveVertical size={20} aria-hidden="true" />
        </button>

        {#if sortMenuOpen}
          <button
            type="button"
            class="menu-backdrop"
            aria-label="회차 정렬 메뉴 닫기"
            onclick={() => (sortMenuOpen = false)}
          ></button>
          <div class="menu sort-menu" role="menu">
            {#each SORT_OPTIONS as option (option.order)}
              <button
                type="button"
                class="menu-item"
                role="menuitemradio"
                aria-checked={store.episodeSortOrder === option.order}
                class:selected={store.episodeSortOrder === option.order}
                onclick={() => selectSortOrder(option.order)}
              >
                <span>{option.label}</span>
                {#if store.episodeSortOrder === option.order}
                  <Check size={16} aria-hidden="true" />
                {/if}
              </button>
            {/each}
          </div>
        {/if}
      </div>

      <button
        type="button"
        class="icon-button favorite"
        class:favorited={store.seriesFavorited}
        title={store.seriesFavorited ? '즐겨찾기 해제' : '즐겨찾기'}
        aria-label={store.seriesFavorited ? '즐겨찾기 해제' : '즐겨찾기'}
        aria-pressed={store.seriesFavorited}
        onclick={() => void store.toggleFavorite()}
      >
        <Star
          size={20}
          fill={store.seriesFavorited ? '#FFB800' : 'none'}
          aria-hidden="true"
        />
      </button>
    {/if}

    {#if screen === 'Browse' || screen === 'Series' || screen === 'Reader'}
      <button
        type="button"
        class="icon-button"
        title="새로고침"
        aria-label="새로고침"
        disabled={!canRefresh}
        onclick={() => void store.refresh()}
      >
        <RefreshCw size={20} aria-hidden="true" />
      </button>
    {/if}
  </div>

  {#if screen === 'Reader'}
    <!-- Reader options bar (Kotlin `Row` 2): view mode, then direction, then split mode. -->
    <div class="row options" role="group" aria-label="보기 옵션">
      <div class="chip-group">
        {#each VIEW_MODE_OPTIONS as option (option.mode)}
          {@const Icon = option.icon}
          <button
            type="button"
            class="chip view-mode"
            class:selected={store.viewMode === option.mode}
            aria-pressed={store.viewMode === option.mode}
            onclick={() => selectViewMode(option.mode)}
          >
            <Icon size={14} aria-hidden="true" />
            <span>{option.label}</span>
          </button>
        {/each}
      </div>

      {#if store.viewMode !== 'SCROLL'}
        <div class="chip-group">
          {#each READ_DIRECTION_OPTIONS as option (option.direction)}
            <button
              type="button"
              class="chip direction"
              class:selected={store.readDirection === option.direction}
              aria-pressed={store.readDirection === option.direction}
              onclick={() => selectReadDirection(option.direction)}
            >
              <span>{option.label}</span>
            </button>
          {/each}
        </div>
      {/if}

      {#if store.viewMode === 'PAGE'}
        <div class="chip-group">
          {#each SPLIT_MODE_OPTIONS as option (option.mode)}
            <button
              type="button"
              class="chip split-mode"
              class:selected={store.splitMode === option.mode}
              aria-pressed={store.splitMode === option.mode}
              onclick={() => selectSplitMode(option.mode)}
            >
              <span>{option.label}</span>
            </button>
          {/each}
        </div>
      {/if}
    </div>
  {/if}

  {#if store.searchExpanded && screen === 'Browse'}
    <!-- `AnimatedVisibility(visible = state.searchExpanded && state.screen == Screen.Browse)` -->
    <div class="search-row" transition:slide={{ duration: 170 }}>
      <div class="search-field">
        <span class="search-icon">
          <Search size={16} aria-hidden="true" />
        </span>
        <input
          bind:this={searchInput}
          type="search"
          autocomplete="off"
          spellcheck="false"
          placeholder={searchPlaceholder}
          value={store.searchInput}
          aria-label="검색어"
          oninput={(event) => updateSearchInput(event.currentTarget.value)}
          onkeydown={onSearchKeydown}
        />
        {#if store.searchInput !== ''}
          <button
            type="button"
            class="clear-button"
            title="검색어 지우기"
            aria-label="검색어 지우기"
            onclick={clearSearchInput}
          >
            <X size={14} aria-hidden="true" />
          </button>
        {/if}
      </div>

      <button
        type="button"
        class="icon-button"
        title="검색 실행"
        aria-label="검색 실행"
        onclick={submitSearch}
      >
        <Search size={18} aria-hidden="true" />
      </button>
    </div>
  {/if}
</div>

<style>
  /* Kotlin: `Column.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)`. */
  .top-bar {
    display: flex;
    flex: 0 0 auto;
    flex-direction: column;
    gap: 6px;
    width: 100%;
    padding: 8px 16px;
    box-sizing: border-box;
    background: #000000;
    color: #fafafa;
    z-index: 30;
  }

  /* Immersive variant: `surface.copy(alpha = 0.94f)` over the reader's pages. */
  .top-bar.overlay {
    background: rgba(0, 0, 0, 0.94);
  }

  .row {
    display: flex;
    align-items: center;
    gap: 6px;
    min-height: 40px;
  }

  .row.options {
    gap: 8px;
    min-height: 0;
    padding-top: 2px;
    flex-wrap: wrap;
  }

  .title {
    flex: 1 1 auto;
    min-width: 0;
    margin: 0;
    padding: 0 4px;
    font-size: 17px;
    font-weight: 700;
    letter-spacing: -0.01em;
    line-height: 1.3;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  /* Series title + its episode total; the badge is dropped rather than truncating the title. */
  .title-group {
    display: flex;
    flex: 1 1 auto;
    align-items: baseline;
    gap: 8px;
    min-width: 0;
  }

  /* Row 1 keeps the title and the total on one line: the title ellipsises, the badge never does. */
  .title-group .title {
    flex: 0 1 auto;
    min-width: 0;
  }

  .series-total {
    flex: 0 0 auto;
    min-width: 0;
    padding: 3px 8px;
    border-radius: 6px;
    background: #27272a;
    color: #fafafa;
    font: 700 12px/1.25 var(--font-sans, system-ui, sans-serif);
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }

  /* `IconButton` — a square hit target with a zinc hover wash. */
  .icon-button {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    width: 34px;
    height: 34px;
    padding: 0;
    border: none;
    border-radius: 8px;
    background: transparent;
    color: #a1a1aa;
    cursor: pointer;
    transition: background 0.15s, color 0.15s;
  }

  .icon-button:hover:not(:disabled) {
    background: rgba(39, 39, 42, 0.85);
    color: #fafafa;
  }

  .icon-button.active {
    color: #fafafa;
  }

  .icon-button:disabled {
    opacity: 0.4;
    cursor: default;
  }

  /* `MonochromeTheme.Gold` when the series is favorited (`Icons.Filled.Star`). */
  .icon-button.favorite.favorited {
    color: #ffb800;
  }

  /* `Icon(tint = MaterialTheme.colorScheme.primary)` — the Series download entry point. */
  .icon-button.download {
    color: #fafafa;
  }

  /* `TextButton(shape = RoundedCornerShape(8.dp), contentPadding = 10dp/4dp)`. */
  .action-button {
    display: inline-flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 4px;
    min-width: 0;
    max-width: 45%;
    padding: 4px 10px;
    border: none;
    border-radius: 8px;
    background: transparent;
    color: #fafafa;
    font: 700 12px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
    transition: background 0.15s, color 0.15s;
  }

  .action-button span {
    min-width: 0;
    overflow: hidden;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  .action-button:hover {
    background: rgba(39, 39, 42, 0.85);
  }

  /* --- chip groups (reader options) ------------------------------------------------------- */

  /* Kotlin: `clip(RoundedCornerShape(8.dp)).background(surfaceVariant.copy(alpha = 0.6f))`. */
  .chip-group {
    display: inline-flex;
    align-items: center;
    gap: 2px;
    padding: 2px;
    border-radius: 8px;
    background: rgba(24, 24, 27, 0.6);
  }

  .chip {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 4px 8px;
    border: none;
    border-radius: 6px;
    background: transparent;
    color: #a1a1aa;
    font: 400 11px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
    transition: background 0.15s, color 0.15s;
  }

  .chip:hover:not(.selected) {
    color: #fafafa;
  }

  .chip.selected {
    font-weight: 700;
  }

  /* primary = White / onPrimary = Pure Black */
  .chip.view-mode.selected {
    background: #ffffff;
    color: #000000;
  }

  /* secondary = Zinc 200 / onSecondary = Pure Black */
  .chip.direction.selected {
    background: #e4e4e7;
    color: #000000;
  }

  /* tertiary = Zinc 300 / onTertiary = Pure Black */
  .chip.split-mode.selected {
    background: #d4d4d8;
    color: #000000;
  }

  /* --- menus ----------------------------------------------------------------------------- */

  .menu-anchor {
    position: relative;
    display: inline-flex;
  }

  .menu-backdrop {
    position: fixed;
    inset: 0;
    z-index: 40;
    border: none;
    padding: 0;
    background: transparent;
    cursor: default;
  }

  .menu {
    position: absolute;
    top: calc(100% + 4px);
    right: 0;
    z-index: 41;
    display: flex;
    flex-direction: column;
    min-width: 168px;
    padding: 4px 0;
    border: 1px solid rgba(39, 39, 42, 0.6);
    border-radius: 12px;
    background: #18181b;
    box-shadow: 0 16px 40px rgba(0, 0, 0, 0.55);
  }

  .menu-item {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    padding: 9px 14px;
    border: none;
    background: transparent;
    color: #fafafa;
    font: 400 12.5px/1.2 var(--font-sans, system-ui, sans-serif);
    text-align: left;
    white-space: nowrap;
    cursor: pointer;
  }

  .menu-item:hover {
    background: rgba(39, 39, 42, 0.9);
  }

  .menu-item.selected {
    font-weight: 700;
    color: #ffffff;
  }

  /* --- search bar ------------------------------------------------------------------------ */

  .search-row {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    padding-top: 2px;
  }

  .search-field {
    display: flex;
    align-items: center;
    gap: 8px;
    flex: 1 1 auto;
    min-width: 0;
    padding: 0 8px 0 10px;
    border: 1px solid #3f3f46;
    border-radius: 8px;
    background: #18181b;
  }

  .search-field:focus-within {
    border-color: #71717a;
    box-shadow: 0 0 0 2px rgba(113, 113, 122, 0.25);
  }

  .search-icon {
    flex: 0 0 auto;
    color: #a1a1aa;
  }

  .search-field input {
    flex: 1 1 auto;
    min-width: 0;
    padding: 9px 0;
    border: none;
    background: transparent;
    color: #fafafa;
    font: 400 13px/1.3 var(--font-sans, system-ui, sans-serif);
  }

  .search-field input::placeholder {
    color: #71717a;
  }

  .search-field input:focus {
    outline: none;
  }

  /* `input[type=search]` draws a platform clear glyph in Chrome; the bar ships its own. */
  .search-field input::-webkit-search-cancel-button {
    display: none;
  }

  .clear-button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    padding: 0;
    border: none;
    border-radius: 6px;
    background: transparent;
    color: #a1a1aa;
    cursor: pointer;
  }

  .clear-button:hover {
    background: rgba(63, 63, 70, 0.6);
    color: #fafafa;
  }
</style>
