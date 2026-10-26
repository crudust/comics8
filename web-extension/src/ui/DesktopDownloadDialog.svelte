<script lang="ts">
  /**
   * Offline download modal, a 1:1 port of
   * `desktop/src/main/kotlin/ui/components/DesktopDownloadDialog.kt` (lines 57-239).
   *
   * Kotlin contract, mirrored literally below:
   * - ONE `AlertDialog`: title row `Icons.Default.Download` + `strings.titleOfflineDownload`,
   *   body = the series title, the `loadingAllEpisodes` spinner row while the catalog loads, the
   *   three `OptionRow`s, the start-order field of {@link DownloadMode.CUSTOM}, the
   *   `noticeSequentialDownload` line, confirm = `actionStartDownload` and dismiss =
   *   `actionCancel`.
   * - `OptionRow(title, subtitle, selected, onClick)`: a `RadioButton` plus the two-line label on a
   *   `primaryContainer @ 0.25` surface when selected.
   * - Modes: `DownloadMode.ALL` downloads `episodes` as-is, `DownloadMode.CUSTOM` keeps the chapters
   *   whose 1-based order (`episodes.size - index`) is `>= startNumber` — the Kotlin
   *   `filterIndexed { index, _ -> episodes.size - index >= startNum }` — and {@link DownloadMode.UNREAD}
   *   keeps `!episode.isRead`.
   * - Confirm is disabled while the catalog loads or when there is nothing to download
   *   (`enabled = !catalogLoading && episodes.isNotEmpty()`).
   *
   * Port mapping notes (deliberate, not accidental):
   * - `state` + `viewModel` collapse into one `store` prop, defaulting to the shell's
   *   `desktopState`, like every other ported surface. `onConfirm` still exists for a caller that
   *   wants to intercept the selection, but the default path is the Kotlin one:
   *   `store.startDownloadEpisodes(list)`.
   * - The port request names the two modes it wants exposed ("전체 다운로드" / "범위 선택"). The
   *   Kotlin dialog has three (`ALL`, `UNREAD`, `AFTER_ORDER`); all three are kept here because the
   *   pane is a 1:1 port and dropping one would change which chapters the default mode downloads.
   *   "범위 선택" is Kotlin's `AFTER_ORDER` row, with its start-number field.
   * - Read state comes from the store projection the series pane uses (`episode.isRead`, the
   *   `historyStore` read set), so "미열람 회차만" means the same chapters on both screens.
   */

  import { Download, LoaderCircle } from 'lucide-svelte';

  import { historyStore } from '../data/history-store.svelte';
  import { desktopState } from '../state/desktop-state.svelte';
  import type { DesktopEpisodeItem, DesktopStore } from '../state/desktop-state.svelte';

  interface Props {
    /** Store to render and drive; defaults to the shell's `desktopState`. */
    store?: DesktopStore;
    /** Dismiss without downloading (`AlertDialog.onDismissRequest` / `actionCancel`). */
    onDismiss?: () => void;
    /** Overrides the default confirm path (`store.startDownloadEpisodes(list)`). */
    onConfirm?: (episodes: DesktopEpisodeItem[]) => void;
  }

  let { store = desktopState, onDismiss, onConfirm }: Props = $props();

  /** The three Kotlin modes; the port request's "범위 선택" is {@link AFTER_ORDER}. */
  type DownloadMode = 'ALL' | 'UNREAD' | 'AFTER_ORDER';

  // `KoStrings` values, inlined so the labels cannot drift from the Kotlin dialog.
  const TITLE = '오프라인 다운로드';
  const OPTION_ALL = '전체 다운로드';
  const OPTION_UNREAD = '미열람 회차만 다운로드';
  const OPTION_AFTER_ORDER = '지정 순번 이후 다운로드';
  const OPTION_AFTER_ORDER_DESC = '선택한 번호부터 최신 회차까지';
  const NOTICE_SEQUENTIAL = '※ 서버 부하 방지를 위해 회차별로 안전하게 순차 다운로드됩니다.';
  const ACTION_START = '다운로드 시작';
  const ACTION_CANCEL = '취소';
  const LOADING_ALL = '전체 회차 목록을 불러오는 중...';
  const LOADING_SIMPLE = '불러오는 중...';

  let mode = $state<DownloadMode>('UNREAD');
  let startOrderInput = $state('1');
  let dialogEl = $state<HTMLDivElement | undefined>();

  const series = $derived(store.series);
  const episodes = $derived(store.downloadCatalog);
  const catalogLoading = $derived(store.downloadCatalogLoading);

  /** `episodes.filter { !it.isRead }`, with the same `historyStore` fallback the series pane uses. */
  const unreadEpisodes = $derived(episodes.filter((episode) => !isRead(episode)));

  /** `strings.downloadTotalEpisodes(count)` / `downloadUnreadCount(count)`, or `loadingSimple`. */
  const countLabel = $derived(
    catalogLoading ? LOADING_SIMPLE : `총 ${episodes.length}개 회차`,
  );
  const unreadLabel = $derived(
    catalogLoading ? LOADING_SIMPLE : `아직 읽지 않은 ${unreadEpisodes.length}개 회차`,
  );

  /** `strings.labelDownloadStartOrder` / `labelDownloadStartOrderRange(episodes.size)`. */
  const startOrderLabel = $derived(
    catalogLoading ? '시작 번호' : `시작 번호 (1 ~ ${episodes.length})`,
  );

  const canConfirm = $derived(!catalogLoading && episodes.length > 0);

  /**
   * `RepositoryTransforms.enrichEpisodePage`'s read flag, with the `historyStore` fallback.
   *
   * Exactly the rule `views/SeriesPane.svelte` applies: a chapter the source marked read, one the
   * recorded progress points at, or one with a stored read row.
   */
  function isRead(episode: DesktopEpisodeItem): boolean {
    if (episode.isRead === true) return true;
    const current = series;
    if (!current) return false;
    const sourceId = current.sourceId ?? store.activeSourceId ?? 'local';
    if (historyStore.getProgress(sourceId, current.id)?.lastWrId === episode.wrId) return true;
    return historyStore.readEpisodes.some(
      (row) =>
        row.sourceId === sourceId &&
        row.toonId === current.id &&
        row.wrId === episode.wrId,
    );
  }

  $effect(() => {
    // Focus moves into the modal so Escape and the fields belong to the dialog rather than to
    // whatever was focused behind it.
    dialogEl?.focus();
  });

  /** `viewModel.closeDownloadOptions()` + the caller's own dismissal. */
  function dismiss(): void {
    store.closeDownloadOptions();
    onDismiss?.();
  }

  /** The Kotlin `confirmButton` body: pick the target list, then hand it to the queue. */
  function confirm(): void {
    if (!canConfirm) return;
    const target = targetEpisodes();
    if (onConfirm) {
      onConfirm(target);
      return;
    }
    store.startDownloadEpisodes(target);
  }

  /** `when (selectedMode) { ALL -> episodes; UNREAD -> unread; AFTER_ORDER -> order >= start }`. */
  function targetEpisodes(): DesktopEpisodeItem[] {
    if (mode === 'ALL') return [...episodes];
    if (mode === 'UNREAD') return [...unreadEpisodes];
    const startNumber = Math.max(1, Math.trunc(Number.parseInt(startOrderInput, 10) || 1));
    return episodes.filter((_episode, index) => episodes.length - index >= startNumber);
  }

  /** `onDismissRequest` — backdrop click, Escape, and the `취소` button all land here. */
  function onBackdropClick(event: MouseEvent): void {
    if (event.target !== event.currentTarget) return;
    dismiss();
  }

  function onKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Escape' || event.defaultPrevented) return;
    event.preventDefault();
    event.stopPropagation();
    dismiss();
  }

  /** `OutlinedTextField(onValueChange = { if (input.all { it.isDigit() }) … })`. */
  function updateStartOrder(value: string): void {
    if (!/^\d*$/.test(value)) return;
    startOrderInput = value;
  }
</script>

<svelte:window onkeydown={onKeydown} />

<div class="dialog-backdrop" role="presentation" onclick={onBackdropClick}></div>

<div
  bind:this={dialogEl}
  class="dialog"
  role="dialog"
  aria-modal="true"
  aria-labelledby="download-dialog-title"
  tabindex="-1"
  data-testid="download-dialog"
>
  <header class="dialog-head">
    <span class="dialog-glyph" aria-hidden="true"><Download size={22} /></span>
    <h2 class="dialog-title" id="download-dialog-title">{TITLE}</h2>
  </header>

  <div class="dialog-body">
    <p class="series-title" title={series?.title ?? ''}>{series?.title ?? ''}</p>

    {#if catalogLoading}
      <!-- `CircularProgressIndicator(18.dp) + strings.loadingAllEpisodes` -->
      <div class="loading-row" role="status">
        <LoaderCircle size={18} class="spin" aria-hidden="true" />
        <span>{LOADING_ALL}</span>
      </div>
    {/if}

    <!-- `OptionRow(strings.downloadOptionAll, countLabel, …)` -->
    <button
      type="button"
      class="option"
      class:selected={mode === 'ALL'}
      role="radio"
      aria-checked={mode === 'ALL'}
      data-mode="ALL"
      onclick={() => (mode = 'ALL')}
    >
      <span class="radio-dot" aria-hidden="true"></span>
      <span class="option-text">
        <span class="option-title">{OPTION_ALL}</span>
        <span class="option-subtitle">{countLabel}</span>
      </span>
    </button>

    <!-- `OptionRow(strings.downloadOptionUnread, unreadLabel, …)` -->
    <button
      type="button"
      class="option"
      class:selected={mode === 'UNREAD'}
      role="radio"
      aria-checked={mode === 'UNREAD'}
      data-mode="UNREAD"
      onclick={() => (mode = 'UNREAD')}
    >
      <span class="radio-dot" aria-hidden="true"></span>
      <span class="option-text">
        <span class="option-title">{OPTION_UNREAD}</span>
        <span class="option-subtitle">{unreadLabel}</span>
      </span>
    </button>

    <!-- `OptionRow(strings.downloadOptionAfterOrder, downloadOptionAfterOrderDesc, …)` -->
    <button
      type="button"
      class="option"
      class:selected={mode === 'AFTER_ORDER'}
      role="radio"
      aria-checked={mode === 'AFTER_ORDER'}
      data-mode="AFTER_ORDER"
      title="범위 선택"
      onclick={() => (mode = 'AFTER_ORDER')}
    >
      <span class="radio-dot" aria-hidden="true"></span>
      <span class="option-text">
        <span class="option-title">{OPTION_AFTER_ORDER}</span>
        <span class="option-subtitle">{OPTION_AFTER_ORDER_DESC}</span>
      </span>
    </button>

    {#if mode === 'AFTER_ORDER'}
      <!-- `OutlinedTextField(label = labelDownloadStartOrderRange(episodes.size))` -->
      <label class="start-order">
        <span class="start-order-label">{startOrderLabel}</span>
        <input
          class="start-order-input"
          type="text"
          inputmode="numeric"
          autocomplete="off"
          value={startOrderInput}
          aria-label={startOrderLabel}
          oninput={(event) => updateStartOrder(event.currentTarget.value)}
        />
      </label>
    {/if}

    <p class="notice">{NOTICE_SEQUENTIAL}</p>
  </div>

  <footer class="dialog-actions">
    <button type="button" class="text-button cancel" onclick={dismiss}>{ACTION_CANCEL}</button>
    <button type="button" class="text-button confirm" disabled={!canConfirm} onclick={confirm}>
      {ACTION_START}
    </button>
  </footer>
</div>

<style>
  .dialog-backdrop {
    position: fixed;
    inset: 0;
    z-index: 60;
    background: rgba(0, 0, 0, 0.6);
  }

  /* `AlertDialog`: 280-560dp wide, 24dp padding, `surface` container. */
  .dialog {
    position: fixed;
    top: 50%;
    left: 50%;
    z-index: 61;
    display: flex;
    flex-direction: column;
    width: min(440px, calc(100% - 32px));
    max-height: calc(100% - 48px);
    overflow: hidden;
    padding: 22px 24px 12px;
    border: 1px solid rgba(63, 63, 70, 0.6);
    border-radius: 28px;
    background: #18181b;
    color: #f4f4f5;
    box-shadow: 0 24px 60px rgba(0, 0, 0, 0.6);
    transform: translate(-50%, -50%);
  }

  .dialog:focus {
    outline: none;
  }

  .dialog-head {
    display: flex;
    align-items: center;
    gap: 8px;
    padding-bottom: 12px;
  }

  .dialog-glyph {
    display: inline-flex;
    flex: 0 0 auto;
    color: #fafafa;
  }

  .dialog-title {
    margin: 0;
    color: #fafafa;
    font-size: 18px;
    font-weight: 700;
    line-height: 1.3;
  }

  .dialog-body {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    min-height: 0;
    overflow-y: auto;
  }

  /* `Text(series.title, fontWeight = SemiBold, 14.sp, onSurfaceVariant)`. */
  .series-title {
    margin: 0 0 12px;
    overflow: hidden;
    color: #a1a1aa;
    font-size: 14px;
    font-weight: 600;
    line-height: 1.3;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  .loading-row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding-bottom: 12px;
    color: #a1a1aa;
    font-size: 13px;
  }

  /* `OptionRow`: `Surface(8.dp, primaryContainer @ 0.25 when selected)` + 8/6 padding. */
  .option {
    display: flex;
    align-items: center;
    gap: 8px;
    width: 100%;
    margin: 4px 0;
    padding: 6px 8px;
    border: 1px solid transparent;
    border-radius: 8px;
    background: transparent;
    color: inherit;
    text-align: left;
    cursor: pointer;
  }

  .option:hover {
    background: rgba(39, 39, 42, 0.55);
  }

  .option.selected {
    border-color: rgba(250, 250, 250, 0.28);
    background: rgba(250, 250, 250, 0.08);
  }

  /* `RadioButton(selected = …)`: 20dp ring with a filled inner dot. */
  .radio-dot {
    flex: 0 0 auto;
    width: 18px;
    height: 18px;
    border: 2px solid #52525b;
    border-radius: 50%;
    background: transparent;
  }

  .option.selected .radio-dot {
    border-color: #fafafa;
    background: radial-gradient(circle, #fafafa 0 4px, transparent 4px);
  }

  .option-text {
    display: flex;
    flex-direction: column;
    gap: 1px;
    min-width: 0;
  }

  .option-title {
    color: #f4f4f5;
    font-size: 14px;
    font-weight: 500;
    line-height: 1.3;
  }

  .option.selected .option-title {
    font-weight: 700;
  }

  .option-subtitle {
    color: #71717a;
    font-size: 12px;
    line-height: 1.3;
  }

  /* `OutlinedTextField(singleLine, Number keyboard)` with its floating label. */
  .start-order {
    display: flex;
    flex-direction: column;
    gap: 4px;
    margin: 8px 16px 0;
  }

  .start-order-label {
    color: #a1a1aa;
    font-size: 11px;
    font-weight: 600;
  }

  .start-order-input {
    padding: 9px 10px;
    border: 1px solid rgba(63, 63, 70, 0.8);
    border-radius: 8px;
    background: rgba(0, 0, 0, 0.45);
    color: #f4f4f5;
    font: 600 13px/1.3 var(--font-sans, system-ui, sans-serif);
    font-variant-numeric: tabular-nums;
  }

  .start-order-input:focus {
    outline: none;
    border-color: rgba(250, 250, 250, 0.6);
    box-shadow: 0 0 0 3px rgba(63, 63, 70, 0.5);
  }

  /* `Text(noticeSequentialDownload, 12.sp, outline)`. */
  .notice {
    margin: 12px 4px 8px;
    color: #71717a;
    font-size: 12px;
    line-height: 1.4;
  }

  /* `AlertDialog` action row: right-aligned `TextButton`s. */
  .dialog-actions {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: flex-end;
    gap: 8px;
    padding-top: 8px;
  }

  .text-button {
    padding: 9px 14px;
    border: none;
    border-radius: 8px;
    background: transparent;
    color: #d4d4d8;
    font: 600 13px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .text-button:hover:not(:disabled) {
    background: rgba(63, 63, 70, 0.6);
    color: #fafafa;
  }

  /* `Text(actionStartDownload, fontWeight = Bold)`. */
  .text-button.confirm {
    color: #fafafa;
    font-weight: 700;
  }

  .text-button:disabled {
    opacity: 0.4;
    cursor: default;
  }

  :global(.spin) {
    animation: download-dialog-spin 0.9s linear infinite;
  }

  @keyframes download-dialog-spin {
    to {
      transform: rotate(360deg);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    :global(.spin) {
      animation: none;
    }
  }
</style>
