<script lang="ts">
  /**
   * Downloads surface, a 1:1 port of
   * `desktop/src/main/kotlin/ui/downloads/DesktopDownloadsPane.kt` (lines 63-268).
   *
   * Kotlin contract, mirrored literally below:
   * - `Column.fillMaxSize()` → the in-flight banner, then one of four whole-pane states.
   * - `state.downloadProgress.isRunning` → `DesktopDownloadProgressBanner`: primary-container
   *   surface, `Download` glyph, `downloadInProgressNamed(title)` (or `downloadInProgress`),
   *   a `Close` cancel button (`viewModel.cancelDownloads`), a `LinearProgressIndicator` and the
   *   `downloadProgressImages(current, total, queue)` / `downloadProgressPreparing(queue)` line.
   * - The `when` chain, in Kotlin order: `!packsReady` → `LoadingPane`; `activeSourceId == null` →
   *   `EmptySourcePane`; `downloadLoading && summaries.isEmpty()` → `LoadingPane`;
   *   `summaries.isEmpty()` → `ErrorPane(emptyDownloads, navBackToList, closeDownloads)`.
   * - The list is a `LazyColumn(contentPadding = 16.dp, spacedBy(8.dp))` of `DownloadedItemCard`s,
   *   de-duplicated by `workId().storageKey()` exactly like the Kotlin `distinctBy`.
   * - `DownloadedItemCard`: the title (one line, ellipsised) with the
   *   `badgeDownloadedEpisodeCount(count)` chip next to it, then a second row with
   *   `"${formatFileSize(bytes)} · ${formatRelativeTime(latestDownloadedAt)}"` and the
   *   `actionViewEpisodes` button (`MenuBook`) + the `Delete` trash icon.
   *
   * Port mapping notes (deliberate, not accidental):
   * - One `store` prop (the {@link DesktopStore}) instead of `state` + `viewModel`, the same collapse
   *   `TopBar.svelte` / `SeriesPane.svelte` do. Every action below is the store method of the same
   *   name as its `DesktopViewModel` counterpart.
   * - There is no `packsReady` / `EmptySourcePane` half: a browser extension always has its plugin
   *   host ready, and the shell's source picker is the only place a source is added. A missing
   *   active source therefore renders the same `emptyDownloads` error pane the desktop shows for an
   *   empty library, which is the only state the user can act on here.
   * - The Kotlin pane shows the *source-scoped* wording (`navStorageNamed(sourceTitle)` in the top
   *   bar, `emptyDownloads` = "이 사이트에 저장된 만화가 없습니다."). The port request asks for
   *   "저장된 만화가 없습니다." as the empty state, which is what {@link EMPTY_DOWNLOADS} carries.
   * - `DownloadedToonSummary` has no `href` on the web side (a plugin card's `href` is what the
   *   desktop stores), so {@link DesktopDownloadsPane} keeps whatever the manifest recorded and
   *   `onOpenSeries` rebuilds the `ToonItem` from the summary fields, exactly like the Kotlin card.
   */

  import { AlertCircle, BookOpen, Download, Folder, FolderSync, Trash2, X } from 'lucide-svelte';

  import type { DownloadedToonSummary } from '../data/downloads-store.svelte';
  import { downloadedWorkKey } from '../data/downloads-store.svelte';
  import { desktopState, workKey } from '../state/desktop-state.svelte';
  import type { DesktopStore } from '../state/desktop-state.svelte';
  import { formatRelativeTime } from './format';

  interface Props {
    /** Store to render; defaults to the shell's `desktopState`. */
    store?: DesktopStore;
  }

  let { store = desktopState }: Props = $props();

  /** `LocalStrings.emptyDownloads`, in the wording this port was asked to render. */
  const EMPTY_DOWNLOADS = '저장된 만화가 없습니다.';
  /** `LocalStrings.navBackToList`, the `ErrorPane` action label. */
  const NAV_BACK = '목록으로';
  /** `LocalStrings.badgeDownloadedEpisodeCount(count)`. */
  const badgeDownloadedEpisodeCount = (count: number): string => `저장됨 ${count}화`;
  /** `LocalStrings.actionViewEpisodes` / `actionDelete`. */
  const ACTION_VIEW_EPISODES = '회차 보기';
  const ACTION_DELETE = '삭제';
  /** `LocalStrings.actionCancel`. */
  const ACTION_CANCEL = '취소';

  const summaries = $derived(store.downloadSummaries);
  const progress = $derived(store.downloadProgress);

  /**
   * `state.downloadSummaries.distinctBy { it.workId().storageKey() }`.
   *
   * The manifest is keyed by work already; the de-duplication stays because a summary list can be
   * assembled from more than one page on the desktop (`loadDownloads` merges two queries).
   */
  const cards = $derived.by(() => {
    const seen = new Set<string>();
    const out: DownloadedToonSummary[] = [];
    for (const item of summaries) {
      const key = downloadedWorkKey(item.sourceId || 'local', item.toonId);
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(item);
    }
    return out;
  });

  const loading = $derived(store.downloadLoading && cards.length === 0);

  /** `formatFileSize(bytes)` of `DesktopDownloadsPane.kt:271`: GB / MB / KB / B, one decimal. */
  function formatFileSize(bytes: number): string {
    if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
    const kb = bytes / 1024;
    const mb = kb / 1024;
    const gb = mb / 1024;
    if (gb >= 1) return `${gb.toFixed(1)} GB`;
    if (mb >= 1) return `${mb.toFixed(1)} MB`;
    if (kb >= 1) return `${kb.toFixed(1)} KB`;
    return `${Math.trunc(bytes)} B`;
  }

  /** `strings.downloadInProgressNamed(title)` / `strings.downloadInProgress`. */
  const progressTitle = $derived(
    progress.activeToonTitle.trim() !== ''
      ? `${progress.activeToonTitle} 다운로드 중...`
      : '다운로드 진행 중...',
  );

  /** `strings.downloadProgressImages` / `strings.downloadProgressPreparing`. */
  const progressDetail = $derived(
    progress.totalImages > 0
      ? `${progress.currentImage}/${progress.totalImages}장 (남은 회차: ${progress.queueSize})`
      : `준비 중... (남은 회차: ${progress.queueSize})`,
  );

  /** `LinearProgressIndicator(progress = { current / total })`, indeterminate while preparing. */
  const progressFraction = $derived(
    progress.totalImages > 0
      ? Math.min(1, Math.max(0, progress.currentImage / progress.totalImages))
      : 0,
  );

  /**
   * `viewModel.openSeries(ToonItem(id, title, thumbUrl, href, sourceId))`.
   *
   * The download row is the only surface that opens a series without a catalog card, so the summary
   * is rebuilt into a `ToonItem` here — the same object the Kotlin card constructs.
   */
  function openSeries(item: DownloadedToonSummary): void {
    void store.openSeries({
      id: item.toonId,
      title: item.toonTitle,
      thumbUrl: item.toonThumbUrl,
      href: item.toonHref,
      sourceId: item.sourceId || store.activeSourceId || 'local',
    });
  }

  /** `viewModel.deleteToonDownloads(item.workId())`. */
  function deleteDownloads(item: DownloadedToonSummary): void {
    void store.deleteToonDownloads(item.sourceId || 'local', item.toonId);
  }

  /** `viewModel.closeDownloads()` — the `ErrorPane` action of the empty state. */
  function closeDownloads(): void {
    store.closeDownloads();
  }

  /** `viewModel.cancelDownloads()` — the banner's `Close` button. */
  function cancelDownloads(): void {
    store.cancelDownloads();
  }

  /** The `workId()` of a downloaded work, used for the `data-work` hook and the card key. */
  function workIdOf(item: DownloadedToonSummary): string {
    return workKey(item.sourceId || 'local', item.toonId);
  }
</script>

{#if progress.isRunning}
  <!-- `DesktopDownloadProgressBanner(progress, onCancel = viewModel::cancelDownloads)` -->
  <section class="progress-banner" role="status" aria-live="polite">
    <div class="progress-head">
      <span class="progress-icon" aria-hidden="true"><Download size={18} /></span>
      <p class="progress-title" title={progressTitle}>{progressTitle}</p>
      <button
        type="button"
        class="progress-cancel"
        title={ACTION_CANCEL}
        aria-label={ACTION_CANCEL}
        onclick={cancelDownloads}
      >
        <X size={16} aria-hidden="true" />
      </button>
    </div>

    <div
      class="progress-track"
      class:indeterminate={progress.totalImages === 0}
      role="progressbar"
      aria-valuemin="0"
      aria-valuemax="100"
      aria-valuenow={progress.totalImages > 0 ? Math.round(progressFraction * 100) : undefined}
    >
      <span
        class="progress-bar"
        style={progress.totalImages > 0 ? `width:${progressFraction * 100}%` : undefined}
      ></span>
    </div>

    <p class="progress-detail">{progressDetail}</p>
  </section>
{/if}

<section class="pane" data-count={cards.length}>
  <header class="folder-bar">
    <div class="folder-info">
      <Folder size={16} class="folder-icon" aria-hidden="true" />
      <span class="folder-label">저장 폴더:</span>
      <span class="folder-name" title={store.downloadFolderName || '선택된 폴더 없음'}>
        {store.downloadFolderName || '선택된 폴더 없음'}
      </span>
    </div>
    <button
      type="button"
      class="folder-button"
      onclick={() => store.promptDownloadFolder()}
    >
      <FolderSync size={14} aria-hidden="true" />
      <span>{store.downloadFolderName ? '폴더 변경' : '폴더 설정'}</span>
    </button>
  </header>

  {#if loading}
    <!-- `LoadingPane` -->
    <div class="state loading" role="status">
      <span class="spinner" aria-hidden="true"></span>
      <p class="state-hint">불러오는 중...</p>
    </div>
  {:else if cards.length === 0}
    <!-- `ErrorPane(emptyDownloads, navBackToList, onRetry = viewModel::closeDownloads)` -->
    <div class="state" role="alert">
      <span class="state-icon" aria-hidden="true"><AlertCircle size={36} /></span>
      <p class="state-title">{EMPTY_DOWNLOADS}</p>
      <button type="button" class="state-action" onclick={closeDownloads}>{NAV_BACK}</button>
    </div>
  {:else}
    <!-- `LazyColumn(contentPadding = 16.dp, verticalArrangement = spacedBy(8.dp))` -->
    <ul class="download-list">
      {#each cards as item (workIdOf(item))}
        {@const workId = workIdOf(item)}
        <li>
          <article class="download-card" data-work={workId}>
            <span class="card-thumb">
              {#if item.toonThumbUrl}
                <img
                  src={item.toonThumbUrl}
                  alt=""
                  loading="lazy"
                  decoding="async"
                  class="card-thumb-image"
                />
              {/if}
            </span>

            <div class="card-body">
              <div class="card-head">
                <span class="card-title" title={item.toonTitle}>{item.toonTitle}</span>
                <span class="card-badge">{badgeDownloadedEpisodeCount(item.episodeCount)}</span>
              </div>

              <div class="card-foot">
                <span class="card-meta">
                  {formatFileSize(item.totalBytes)} · {formatRelativeTime(item.latestDownloadedAt)}
                </span>

                <div class="card-actions">
                  <button
                    type="button"
                    class="card-open"
                    title={ACTION_VIEW_EPISODES}
                    onclick={() => openSeries(item)}
                  >
                    <BookOpen size={14} aria-hidden="true" />
                    <span>{ACTION_VIEW_EPISODES}</span>
                  </button>

                  <button
                    type="button"
                    class="card-delete"
                    title={ACTION_DELETE}
                    aria-label={ACTION_DELETE}
                    onclick={() => deleteDownloads(item)}
                  >
                    <Trash2 size={18} aria-hidden="true" />
                  </button>
                </div>
              </div>
            </div>
          </article>
        </li>
      {/each}
    </ul>
  {/if}
</section>

<style>
  .pane {
    display: flex;
    flex-direction: column;
    min-height: 0;
    height: 100%;
    background: #000000;
    color: #f4f4f5;
  }

  /* --- progress banner (`DesktopDownloadProgressBanner`) ------------------------------------ */

  /* Kotlin: `Surface(primaryContainer @ 0.4, 12dp, padding(horizontal = 16, vertical = 8))`. */
  .progress-banner {
    display: flex;
    flex: 0 0 auto;
    flex-direction: column;
    margin: 8px 16px 0;
    padding: 12px 16px;
    border-radius: 12px;
    background: rgba(39, 39, 42, 0.6);
  }

  .progress-head {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .progress-icon {
    display: inline-flex;
    flex: 0 0 auto;
    color: #fafafa;
  }

  .progress-title {
    flex: 1 1 auto;
    min-width: 0;
    margin: 0;
    overflow: hidden;
    color: #fafafa;
    font-size: 12.5px;
    font-weight: 700;
    line-height: 1.3;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  .progress-cancel {
    display: inline-flex;
    flex: 0 0 auto;
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

  .progress-cancel:hover {
    background: rgba(63, 63, 70, 0.7);
    color: #fafafa;
  }

  /* `LinearProgressIndicator().height(6.dp).clip(RoundedCornerShape(3.dp))`. */
  .progress-track {
    position: relative;
    overflow: hidden;
    height: 6px;
    margin-top: 6px;
    border-radius: 3px;
    background: rgba(255, 255, 255, 0.12);
  }

  .progress-bar {
    display: block;
    height: 100%;
    border-radius: 3px;
    background: #fafafa;
    transition: width 0.2s linear;
  }

  .progress-track.indeterminate .progress-bar {
    width: 40%;
    animation: progress-slide 1.1s ease-in-out infinite;
  }

  @keyframes progress-slide {
    0% {
      transform: translateX(-100%);
    }
    100% {
      transform: translateX(250%);
    }
  }

  .progress-detail {
    margin: 4px 0 0;
    color: #a1a1aa;
    font-size: 11px;
    line-height: 1.3;
    font-variant-numeric: tabular-nums;
  }

  /* --- whole-pane states -------------------------------------------------------------------- */

  .state {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 8px;
    min-height: 200px;
    padding: 32px 16px;
    text-align: center;
  }

  .state-icon {
    display: inline-flex;
    color: #52525b;
  }

  .state-title {
    margin: 0;
    color: #f4f4f5;
    font-size: 13.5px;
    font-weight: 700;
    overflow-wrap: anywhere;
  }

  .state-hint {
    margin: 0;
    color: rgba(255, 255, 255, 0.65);
    font-size: 12px;
  }

  .state-action {
    margin-top: 2px;
    padding: 6px 12px;
    border: 1px solid rgba(63, 63, 70, 0.8);
    border-radius: 7px;
    background: rgba(24, 24, 27, 0.9);
    color: #e4e4e7;
    font: 600 12px/1.2 var(--font-sans, system-ui, sans-serif);
    cursor: pointer;
  }

  .state-action:hover {
    border-color: rgba(113, 113, 122, 0.9);
  }

  .spinner {
    width: 20px;
    height: 20px;
    border: 2px solid rgba(255, 255, 255, 0.16);
    border-top-color: #fafafa;
    border-radius: 50%;
    animation: downloads-spin 0.8s linear infinite;
  }

  @keyframes downloads-spin {
    to {
      transform: rotate(360deg);
    }
  }

  /* --- folder bar -------------------------------------------------------------------------- */

  .folder-bar {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 10px 16px;
    border-bottom: 1px solid rgba(63, 63, 70, 0.4);
    background: rgba(24, 24, 27, 0.7);
  }

  .folder-info {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }

  :global(.folder-icon) {
    flex-shrink: 0;
    color: #a1a1aa;
  }

  .folder-label {
    flex-shrink: 0;
    color: #71717a;
    font-size: 12px;
    font-weight: 500;
  }

  .folder-name {
    overflow: hidden;
    color: #f4f4f5;
    font-size: 12px;
    font-weight: 600;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  .folder-button {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 5px 10px;
    border: 1px solid rgba(63, 63, 70, 0.6);
    border-radius: 6px;
    background: #27272a;
    color: #e4e4e7;
    font-size: 11px;
    font-weight: 600;
    cursor: pointer;
    white-space: nowrap;
    transition: background 0.15s, border-color 0.15s;
  }

  .folder-button:hover {
    background: #3f3f46;
    border-color: rgba(161, 161, 170, 0.6);
    color: #ffffff;
  }

  /* --- download list ------------------------------------------------------------------------ */

  .download-list {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    gap: 8px;
    min-height: 0;
    margin: 0;
    padding: 16px;
    overflow-y: auto;
    list-style: none;
  }

  /* `Surface(12dp, surfaceVariant @ 0.45f)` + `padding(horizontal = 16, vertical = 12)`. */
  .download-card {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px 16px;
    border: 1px solid rgba(63, 63, 70, 0.35);
    border-radius: 12px;
    background: rgba(24, 24, 27, 0.45);
  }

  /* Poster thumbnail of the downloaded work (the web library card's crop). */
  .card-thumb {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    justify-content: center;
    width: 48px;
    height: 64px;
    overflow: hidden;
    border: 0.5px solid rgba(63, 63, 70, 0.55);
    border-radius: 8px;
    background: #27272a;
  }

  .card-thumb-image {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .card-body {
    display: flex;
    flex: 1 1 auto;
    flex-direction: column;
    min-width: 0;
  }

  .card-head {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }

  .card-title {
    flex: 0 1 auto;
    min-width: 0;
    overflow: hidden;
    color: #f4f4f5;
    font-size: 14px;
    font-weight: 700;
    line-height: 1.3;
    white-space: nowrap;
    text-overflow: ellipsis;
  }

  /* `badgeDownloadedEpisodeCount(count)`: primaryContainer @ 0.85, labelSmall bold, 4dp radius. */
  .card-badge {
    flex: 0 0 auto;
    padding: 2px 6px;
    border-radius: 4px;
    background: rgba(250, 250, 250, 0.85);
    color: #000000;
    font-size: 11px;
    font-weight: 700;
    line-height: 1.25;
    white-space: nowrap;
    font-variant-numeric: tabular-nums;
  }

  .card-foot {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 8px;
  }

  /* `labelSmall` in `outline`: "<size> · <relative time>". */
  .card-meta {
    flex: 1 1 auto;
    min-width: 0;
    overflow: hidden;
    color: #71717a;
    font-size: 11px;
    line-height: 1.3;
    white-space: nowrap;
    text-overflow: ellipsis;
    font-variant-numeric: tabular-nums;
  }

  .card-actions {
    display: flex;
    flex: 0 0 auto;
    align-items: center;
    gap: 8px;
  }

  /* `FilledTonalButton(shape = 8.dp, contentPadding = 10/4, height = 32.dp)`. */
  .card-open {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    height: 32px;
    padding: 4px 10px;
    border: none;
    border-radius: 8px;
    background: rgba(63, 63, 70, 0.85);
    color: #f4f4f5;
    font: 600 11px/1.2 var(--font-sans, system-ui, sans-serif);
    white-space: nowrap;
    cursor: pointer;
  }

  .card-open:hover {
    background: rgba(82, 82, 91, 0.95);
  }

  /* `IconButton(32.dp)` with `Icons.Outlined.Delete` tinted `error @ 0.8`. */
  .card-delete {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    padding: 0;
    border: none;
    border-radius: 8px;
    background: transparent;
    color: rgba(248, 113, 113, 0.8);
    cursor: pointer;
  }

  .card-delete:hover {
    background: rgba(127, 29, 29, 0.35);
    color: #fca5a5;
  }

  @media (prefers-reduced-motion: reduce) {
    .spinner,
    .progress-track.indeterminate .progress-bar {
      animation: none;
    }

    .progress-bar {
      transition: none;
    }
  }

  @media (max-width: 480px) {
    .card-foot {
      flex-direction: column;
      align-items: stretch;
      gap: 6px;
    }

    .card-actions {
      justify-content: flex-end;
    }
  }
</style>
