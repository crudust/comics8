<script lang="ts">
  /**
   * History surface, ported 1:1 from `desktop/src/main/kotlin/ui/history/HistoryPane.kt`.
   *
   * Mapping notes (deliberate, not accidental):
   * - Driven by the ported store (`src/state/desktop-state.svelte.ts`) instead of props, exactly
   *   like the Kotlin pane receives `DesktopUiState` + `DesktopViewModel`. `store.historyItems` is
   *   the web twin of `state.historyItems`; the rows are `HistoryRecord`s (the web history row),
   *   and `store.progressLabel(record)` is `DesktopUiState.progressLabel(history)`.
   * - `HistoryItemCard` mirrors the Kotlin card: one rounded `surfaceVariant @ 0.45` surface, the
   *   last-read chapter title (bold, 1 line), the progress badge (`primaryContainer @ 0.85`), the
   *   relative time and the three actions. Clicking the card opens the series through
   *   `openSeriesFromHistory` — the Kotlin `onOpenSeries`, not the "continue" button.
   * - The thumbnail is 72x54 (`Modifier.size(72.dp, 54.dp)` in the episode card metric): the web
   *   history row has no chapter thumbnail, so the series cover is cropped into the same box.
   * - Relative time comes from `views/format.ts::formatRelativeTime`, which mirrors
   *   `core/src/main/kotlin/i18n/I18nExtensions.kt::formatRelativeTime`
   *   (방금 전 / N분 전 / N시간 전 / N일 전 / yyyy.MM.dd).
   * - The `NEW` badge of the Kotlin card has no web twin: `HistoryRecord` does not track `hasNew`
   *   (that flag is derived from a counted total on the desktop). Nothing is invented in its place.
   * - "Continue" (`continueHistoryEpisode`), "delete" (`deleteHistory`) and the empty state's
   *   `closeHistory` action all go through the store, so the pane stays a pure renderer.
   */

  import { desktopState, workKey } from '../state/desktop-state.svelte';
  import type { DesktopStore } from '../state/desktop-state.svelte';
  import type { HistoryRecord } from '../data/history-store.svelte';
  import { formatRelativeTime } from './format';

  interface Props {
    /** Store to render; defaults to the shell's `desktopState`. */
    store?: DesktopStore;
  }

  let { store = desktopState }: Props = $props();

  /** `LocalStrings.emptyHistory` (ko) — shown verbatim by the Kotlin `ErrorPane`. */
  const EMPTY_HISTORY = '최근 본 작품이 없습니다.';
  /** `LocalStrings.navBackToList` (ko) — the empty state's action label. */
  const NAV_BACK = '목록으로';
  /** `LocalStrings.actionTableOfContents` / `actionResume` (ko). */
  const ACTION_CONTENTS = '목차';
  const ACTION_RESUME = '이어보기';
  /** `LocalStrings.actionDelete` (ko). */
  const ACTION_DELETE = '삭제';

  /** `state.historyItems.distinctBy { it.workId().storageKey() }`. */
  const rows = $derived.by(() => {
    const seen = new Set<string>();
    const out: HistoryRecord[] = [];
    for (const record of store.historyItems) {
      const key = workKey(record.sourceId, record.toonId);
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(record);
    }
    return out;
  });

  const loading = $derived(store.historyLoading && store.historyItems.length === 0);
  const isEmpty = $derived(!loading && store.historyItems.length === 0);

  // ---------------------------------------------------------------------------------------------
  // actions (DesktopViewModel equivalents)
  // ---------------------------------------------------------------------------------------------

  function openSeries(record: HistoryRecord): void {
    void store.openSeriesFromHistory(record);
  }

  function onRowKeydown(event: KeyboardEvent, record: HistoryRecord): void {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    openSeries(record);
  }

  /** `DesktopViewModel.continueHistoryEpisode(record)`: reopen the chapter where the user stopped. */
  function continueSeries(event: MouseEvent, record: HistoryRecord): void {
    event.stopPropagation();
    void store.continueHistoryEpisode(record);
  }

  /** `DesktopViewModel.deleteHistory(record.workId())`. */
  function removeRecord(event: MouseEvent, record: HistoryRecord): void {
    event.stopPropagation();
    void store.deleteHistory(record);
  }

  /** `ErrorPane(onRetry = viewModel::closeHistory)` — the empty state's way back to browsing. */
  function backToBrowse(): void {
    store.closeHistory();
  }

  function progressText(record: HistoryRecord): string {
    return store.progressLabel(record);
  }

  function relativeTime(record: HistoryRecord): string {
    return formatRelativeTime(record.updatedAt);
  }
</script>

<section class="pane">
  {#if loading}
    <!-- `state.historyLoading && state.historyItems.isEmpty()` → LoadingPane -->
    <div class="state loading" role="status">
      <span class="spinner" aria-hidden="true"></span>
    </div>
  {:else if isEmpty}
    <!-- `ErrorPane(strings.emptyHistory, strings.navBackToList, onRetry = viewModel::closeHistory)` -->
    <div class="state">
      <p class="state-title">{EMPTY_HISTORY}</p>
      <button type="button" class="state-action" onclick={backToBrowse}>{NAV_BACK}</button>
    </div>
  {:else}
    <!-- `LazyColumn(contentPadding = 16.dp, spacedBy(8.dp))` -->
    <ul class="history-list">
      {#each rows as record (workKey(record.sourceId, record.toonId))}
        {@const progress = progressText(record)}
        <li>
          <div
            class="history-card"
            role="button"
            tabindex="0"
            aria-label={`${record.title} — ${record.lastEpisodeTitle || '회차 정보 없음'}`}
            title={record.title}
            onclick={() => openSeries(record)}
            onkeydown={(event) => onRowKeydown(event, record)}
          >
            <div class="card-head">
              <span class="thumb">
                {#if record.thumbUrl}
                  <img
                    src={record.thumbUrl}
                    alt=""
                    loading="lazy"
                    decoding="async"
                    class="thumb-image"
                  />
                {/if}
              </span>

              <span class="head-text">
                <span class="title" title={record.title}>{record.title}</span>
                <span class="episode" title={record.lastEpisodeTitle}>
                  {record.lastEpisodeTitle || '회차 정보 없음'}
                </span>
              </span>

              {#if progress}
                <span class="progress-badge" aria-label={`진행도 ${progress}`}>{progress}</span>
              {/if}
            </div>

            <div class="card-foot">
              <span class="read-at">{relativeTime(record)}</span>

              <span class="actions">
                <button
                  type="button"
                  class="action tonal"
                  title={ACTION_CONTENTS}
                  onclick={(event) => {
                    event.stopPropagation();
                    openSeries(record);
                  }}
                >
                  {ACTION_CONTENTS}
                </button>

                <button
                  type="button"
                  class="action primary"
                  title={ACTION_RESUME}
                  onclick={(event) => continueSeries(event, record)}
                >
                  {ACTION_RESUME}
                </button>

                <button
                  type="button"
                  class="action danger"
                  title={ACTION_DELETE}
                  aria-label={`${record.title} ${ACTION_DELETE}`}
                  onclick={(event) => removeRecord(event, record)}
                >
                  {ACTION_DELETE}
                </button>
              </span>
            </div>
          </div>
        </li>
      {/each}
    </ul>
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

  .history-list {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    gap: 8px;
    margin: 0;
    min-height: 0;
    padding: 16px;
    overflow-y: auto;
    list-style: none;
  }

  /* `Surface(shape = 12.dp, color = surfaceVariant @ 0.45)`. */
  .history-card {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 12px 16px;
    border: 1px solid rgba(63, 63, 70, 0.45);
    border-radius: 12px;
    background: rgba(24, 24, 27, 0.45);
    cursor: pointer;
    transition: background 0.15s, border-color 0.15s;
  }

  .history-card:hover {
    border-color: rgba(113, 113, 122, 0.7);
    background: rgba(39, 39, 42, 0.6);
  }

  .history-card:focus-visible {
    outline: 2px solid #60a5fa;
    outline-offset: 2px;
  }

  .card-head {
    display: flex;
    align-items: center;
    gap: 10px;
    min-width: 0;
  }

  /* 72 x 54, 8dp rounded, ContentScale.Crop. */
  .thumb {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    width: 72px;
    height: 54px;
    overflow: hidden;
    border: 0.5px solid rgba(63, 63, 70, 0.55);
    border-radius: 8px;
    background: #27272a;
  }

  .thumb-image {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .head-text {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  /* titleMedium bold, 1 line, ellipsis. */
  .title {
    overflow: hidden;
    font-size: 14px;
    font-weight: 700;
    line-height: 1.3;
    color: #f4f4f5;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  /* labelSmall, onSurfaceVariant. */
  .episode {
    overflow: hidden;
    font-size: 11.5px;
    line-height: 1.3;
    color: rgba(244, 244, 245, 0.64);
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  /* primaryContainer @ 0.85, labelSmall bold, 4dp rounded. */
  .progress-badge {
    flex: 0 0 auto;
    padding: 2px 6px;
    border-radius: 4px;
    background: rgba(30, 58, 138, 0.85);
    color: #eff6ff;
    font-size: 11px;
    font-weight: 700;
    line-height: 1.25;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }

  .card-foot {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }

  /* labelSmall, outline. */
  .read-at {
    flex: 1 1 auto;
    min-width: 0;
    overflow: hidden;
    font-size: 11px;
    line-height: 1.3;
    color: #71717a;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  .actions {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 8px;
  }

  /* 32dp-high buttons, 8dp rounded (`Modifier.height(32.dp)`). */
  .action {
    height: 32px;
    padding: 0 10px;
    border-radius: 8px;
    font: 600 11.5px/1 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
    transition: background 0.15s, border-color 0.15s, color 0.15s;
  }

  /* `FilledTonalButton` (목차). */
  .action.tonal {
    border: 1px solid rgba(63, 63, 70, 0.7);
    background: rgba(39, 39, 42, 0.95);
    color: #e4e4e7;
  }

  .action.tonal:hover {
    border-color: rgba(113, 113, 122, 0.9);
    background: rgba(63, 63, 70, 0.95);
  }

  /* `Button(containerColor = primary, contentColor = onPrimary)` (이어보기). */
  .action.primary {
    border: 1px solid rgba(96, 165, 250, 0.55);
    background: rgba(37, 99, 235, 0.95);
    color: #eff6ff;
    font-weight: 700;
  }

  .action.primary:hover {
    background: rgba(59, 130, 246, 1);
  }

  /* `IconButton(Icon(Delete, tint = error @ 0.8))`. */
  .action.danger {
    border: 0;
    background: transparent;
    color: rgba(248, 113, 113, 0.8);
  }

  .action.danger:hover {
    background: rgba(127, 29, 29, 0.35);
    color: #fca5a5;
  }

  .action:focus-visible {
    outline: 2px solid #60a5fa;
    outline-offset: 2px;
  }

  /* --- loading / empty states ------------------------------------------------------------- */

  .state {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 14px;
    min-height: 200px;
    padding: 24px;
    text-align: center;
  }

  .state-title {
    margin: 0;
    font-size: 13.5px;
    font-weight: 600;
    color: #e4e4e7;
    overflow-wrap: anywhere;
  }

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

  @media (prefers-reduced-motion: reduce) {
    .spinner {
      animation: none;
    }

    .history-card,
    .action {
      transition: none;
    }
  }
</style>
